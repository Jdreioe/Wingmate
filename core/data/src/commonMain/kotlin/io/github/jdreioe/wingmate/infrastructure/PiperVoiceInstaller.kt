package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.PiperDownloadFailure
import io.github.jdreioe.wingmate.domain.PiperFile
import io.github.jdreioe.wingmate.domain.PiperVoicePack
import io.github.jdreioe.wingmate.domain.PiperVoiceState
import io.github.jdreioe.wingmate.domain.loggingClassName
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.HashingSink
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.blackholeSink
import okio.buffer
import okio.openZip
import okio.use

/** Files the Piper engine loads for one installed voice. */
data class PiperVoiceFiles(val model: Path, val tokens: Path, val phonemizerData: Path)

/** Thrown when an installed voice cannot be used; the voice must be downloaded again. */
class PiperVoiceUnavailableException(message: String) : IllegalStateException(message)

/**
 * Downloads, verifies, and removes Piper voices under [root].
 *
 * Each voice lives in `voices/<key>/`. Files are written as `<name>.part` and only renamed
 * once their pinned size and SHA-256 match, so a finished name always means a verified file.
 * A `.part` left by a dropped connection or closed app is resumed with an HTTP Range request.
 * The espeak-ng phonemizer data every voice needs is downloaded with the first voice and
 * removed with the last one.
 *
 * Downloads run in the installer's own scope so they continue when the screen closes.
 */
class PiperVoiceInstaller(
    private val root: Path,
    private val fileSystem: FileSystem,
    private val httpClient: HttpClient,
    val catalog: List<PiperVoicePack> = PiperVoiceCatalog,
    private val phonemizer: PiperFile = PiperPhonemizerData,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val mutableStates = MutableStateFlow<Map<String, PiperVoiceState>>(emptyMap())

    /** Download state per catalog key; filled from disk by [refresh]. */
    val states: StateFlow<Map<String, PiperVoiceState>> = mutableStates.asStateFlow()

    /** Size of the shared phonemizer data the first download adds. */
    val sharedDataBytes: Long get() = phonemizer.sizeBytes

    private val jobs = MutableStateFlow<Map<String, Job>>(emptyMap())
    private val phonemizerLock = Mutex()
    private val phonemizerDir: Path get() = root / PHONEMIZER_DIR

    /** Reads every voice's state from disk, leaving running downloads alone. */
    suspend fun refresh() {
        val fromDisk = withContext(Dispatchers.IO) { catalog.associate { it.key to diskState(it) } }
        mutableStates.update { current ->
            fromDisk.mapValues { (key, state) ->
                current[key]?.takeIf { it is PiperVoiceState.Downloading && jobs.value[key]?.isActive == true } ?: state
            }
        }
    }

    /** Starts or resumes downloading [key]. Does nothing while it is already downloading. */
    fun download(key: String) {
        val pack = pack(key) ?: return
        val job = scope.launch(start = CoroutineStart.LAZY) { install(pack) }
        var started = false
        jobs.update { current ->
            if (current[key]?.isActive == true) {
                current
            } else {
                started = true
                current + (key to job)
            }
        }
        if (started) job.start() else job.cancel()
    }

    /** Stops a download and discards its partial files. */
    suspend fun cancel(key: String) {
        val pack = pack(key) ?: return
        jobs.value[key]?.cancelAndJoin()
        withContext(Dispatchers.IO) {
            pack.files.forEach { fileSystem.delete(partOf(voiceDir(key) / it.name)) }
            if (jobs.value.values.none { it.isActive }) fileSystem.delete(partOf(root / phonemizer.name))
        }
        setState(key, withContext(Dispatchers.IO) { diskState(pack) })
    }

    /** Removes [key] and, when no voice remains, the shared phonemizer data. */
    suspend fun delete(key: String) {
        pack(key) ?: return
        jobs.value[key]?.cancelAndJoin()
        withContext(Dispatchers.IO) {
            fileSystem.deleteRecursively(voiceDir(key))
            // A cancelled download can leave an empty folder; only folders with files count.
            val voicesLeft = fileSystem.listOrNull(root / VOICES_DIR).orEmpty()
                .any { fileSystem.listOrNull(it).orEmpty().isNotEmpty() }
            if (!voicesLeft) {
                fileSystem.deleteRecursively(phonemizerDir)
                fileSystem.delete(partOf(root / phonemizer.name))
            }
        }
        setState(key, PiperVoiceState.NotInstalled)
    }

    /**
     * Returns [key]'s files after checking them against their pinned checksums, so a damaged
     * model never reaches the native engine. A damaged file is deleted and the voice is marked
     * [PiperDownloadFailure.Damaged].
     */
    suspend fun verifiedFiles(key: String): PiperVoiceFiles = withContext(Dispatchers.IO) {
        val pack = pack(key) ?: throw PiperVoiceUnavailableException("Unknown Piper voice")
        if (!phonemizerInstalled()) throw PiperVoiceUnavailableException("Piper voice is not downloaded")
        for (file in pack.files) {
            val path = voiceDir(key) / file.name
            if (!fileSystem.exists(path)) throw PiperVoiceUnavailableException("Piper voice is not downloaded")
            if (!matches(path, file)) {
                fileSystem.delete(path)
                setState(key, PiperVoiceState.Incomplete(voiceBytesOnDisk(pack), totalBytes(pack), PiperDownloadFailure.Damaged))
                throw PiperVoiceUnavailableException("Piper voice files are damaged")
            }
        }
        PiperVoiceFiles(
            model = voiceDir(key) / pack.files.first { it.name.endsWith(".onnx") }.name,
            tokens = voiceDir(key) / "tokens.txt",
            phonemizerData = phonemizerDir,
        )
    }

    fun pack(key: String): PiperVoicePack? = catalog.firstOrNull { it.key == key }

    private suspend fun install(pack: PiperVoicePack) {
        val total = totalBytes(pack)
        var downloaded = voiceBytesOnDisk(pack) +
            if (phonemizerInstalled()) 0L else sizeOf(partOf(root / phonemizer.name))
        setState(pack.key, PiperVoiceState.Downloading(downloaded, total))
        val onBytes: (Long) -> Unit = { delta ->
            downloaded += delta
            setState(pack.key, PiperVoiceState.Downloading(downloaded, total))
        }
        try {
            phonemizerLock.withLock {
                if (!phonemizerInstalled()) {
                    val zip = root / phonemizer.name
                    fetch(phonemizer, zip, onBytes)
                    extractPhonemizer(zip)
                }
            }
            pack.files.forEach { fetch(it, voiceDir(pack.key) / it.name, onBytes) }
            setState(pack.key, PiperVoiceState.Installed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val reason = (failure as? DownloadFailed)?.reason ?: PiperDownloadFailure.Network
            // Literal tags keep the calls checkable by SpeechLogPrivacyTest.
            when (reason) {
                PiperDownloadFailure.Network ->
                    OperationalLogger.warn("piper.download", "network_failed", exceptionClass = failure.loggingClassName())
                PiperDownloadFailure.Damaged -> OperationalLogger.warn("piper.download", "checksum_failed")
                PiperDownloadFailure.Storage -> OperationalLogger.warn("piper.download", "storage_failed")
            }
            setState(pack.key, PiperVoiceState.Incomplete(voiceBytesOnDisk(pack), total, reason))
        }
    }

    /** Downloads [file] to [target], resuming a `.part` file, then verifies and renames it. */
    private suspend fun fetch(file: PiperFile, target: Path, onBytes: (Long) -> Unit) {
        if (fileSystem.exists(target)) return
        val part = partOf(target)
        storage { fileSystem.createDirectories(target.parent!!) }
        var have = fileSystem.metadataOrNull(part)?.size ?: 0L
        if (have > file.sizeBytes) {
            storage { fileSystem.delete(part) }
            onBytes(-have)
            have = 0L
        }
        if (have < file.sizeBytes) {
            httpClient.prepareGet(file.url) {
                if (have > 0) header(HttpHeaders.Range, "bytes=$have-")
            }.execute { response ->
                val append = when (response.status) {
                    HttpStatusCode.PartialContent -> true
                    HttpStatusCode.OK -> false
                    else -> throw DownloadFailed(PiperDownloadFailure.Network)
                }
                if (!append && have > 0) {
                    onBytes(-have)
                    have = 0L
                }
                val channel = response.bodyAsChannel()
                val sink = storage { if (append) fileSystem.appendingSink(part) else fileSystem.sink(part) }.buffer()
                sink.use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = channel.readAvailable(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        storage { out.write(buffer, 0, read) }
                        have += read
                        onBytes(read.toLong())
                        if (have > file.sizeBytes) break
                    }
                }
            }
        }
        if (!matches(part, file)) {
            storage { fileSystem.delete(part) }
            onBytes(-have)
            throw DownloadFailed(PiperDownloadFailure.Damaged)
        }
        storage { fileSystem.atomicMove(part, target) }
    }

    private fun extractPhonemizer(zip: Path) {
        val staging = root / "$PHONEMIZER_DIR.tmp"
        storage {
            fileSystem.deleteRecursively(staging)
            val archive = fileSystem.openZip(zip)
            val archiveRoot = "/".toPath()
            archive.listRecursively(archiveRoot).forEach { entry ->
                val destination = staging / entry.relativeTo(archiveRoot)
                if (archive.metadata(entry).isDirectory) {
                    fileSystem.createDirectories(destination)
                } else {
                    fileSystem.createDirectories(destination.parent!!)
                    archive.source(entry).use { source ->
                        fileSystem.sink(destination).buffer().use { it.writeAll(source) }
                    }
                }
            }
        }
        val extracted = staging / PHONEMIZER_DIR
        if (!fileSystem.exists(extracted)) {
            fileSystem.deleteRecursively(staging)
            fileSystem.delete(zip)
            throw DownloadFailed(PiperDownloadFailure.Damaged)
        }
        storage {
            fileSystem.atomicMove(extracted, phonemizerDir)
            fileSystem.deleteRecursively(staging)
            fileSystem.delete(zip)
        }
    }

    private fun diskState(pack: PiperVoicePack): PiperVoiceState {
        val installed = phonemizerInstalled() && pack.files.all { file ->
            fileSystem.metadataOrNull(voiceDir(pack.key) / file.name)?.size == file.sizeBytes
        }
        if (installed) return PiperVoiceState.Installed
        val kept = voiceBytesOnDisk(pack)
        return if (kept > 0) PiperVoiceState.Incomplete(kept, totalBytes(pack), failure = null) else PiperVoiceState.NotInstalled
    }

    /** Bytes still needed from scratch: the voice, plus the phonemizer data if it is missing. */
    private fun totalBytes(pack: PiperVoicePack): Long =
        pack.sizeBytes + if (phonemizerInstalled()) 0L else phonemizer.sizeBytes

    /** Verified and partial bytes of [pack]'s own files; the shared phonemizer is not counted. */
    private fun voiceBytesOnDisk(pack: PiperVoicePack): Long = pack.files.sumOf { file ->
        val target = voiceDir(pack.key) / file.name
        if (fileSystem.exists(target)) file.sizeBytes else sizeOf(partOf(target))
    }

    private fun matches(path: Path, file: PiperFile): Boolean {
        if (fileSystem.metadataOrNull(path)?.size != file.sizeBytes) return false
        val hashing = HashingSink.sha256(blackholeSink())
        fileSystem.source(path).buffer().use { source -> hashing.buffer().use { it.writeAll(source) } }
        return hashing.hash.hex() == file.sha256
    }

    private fun phonemizerInstalled(): Boolean = fileSystem.metadataOrNull(phonemizerDir)?.isDirectory == true

    private fun sizeOf(path: Path): Long = fileSystem.metadataOrNull(path)?.size ?: 0L

    private fun voiceDir(key: String): Path = root / VOICES_DIR / key

    private fun partOf(path: Path): Path = path.parent!! / "${path.name}.part"

    private fun setState(key: String, state: PiperVoiceState) {
        mutableStates.update { it + (key to state) }
    }

    private class DownloadFailed(val reason: PiperDownloadFailure) : Exception("Piper download failed: $reason")

    /** Runs a local file operation, reporting I/O failures as [PiperDownloadFailure.Storage]. */
    private inline fun <T> storage(block: () -> T): T = try {
        block()
    } catch (failure: IOException) {
        throw DownloadFailed(PiperDownloadFailure.Storage)
    }

    private companion object {
        const val VOICES_DIR = "voices"
        const val PHONEMIZER_DIR = "espeak-ng-data"
    }
}
