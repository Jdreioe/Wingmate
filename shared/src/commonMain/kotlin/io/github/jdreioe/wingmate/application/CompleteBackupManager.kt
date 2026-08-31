package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.BoardRepository
import io.github.jdreioe.wingmate.domain.BoardSetRepository
import io.github.jdreioe.wingmate.domain.CategoryItem
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.FolderPhrase
import io.github.jdreioe.wingmate.domain.isFolderPhrase
import io.github.jdreioe.wingmate.domain.CommunicationSessionDataSource
import io.github.jdreioe.wingmate.domain.CommunicationSessionSnapshot
import io.github.jdreioe.wingmate.domain.CommunicationStorageResult
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.Message
import io.github.jdreioe.wingmate.domain.PhraseRepository
import io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository
import io.github.jdreioe.wingmate.domain.PronunciationEntry
import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SettingsRepository
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.VoiceRepository
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.domain.obf.BoardSetGraph
import io.github.jdreioe.wingmate.domain.obf.ScreenKind
import io.github.jdreioe.wingmate.domain.obf.requireValid
import io.github.jdreioe.wingmate.domain.obf.ZipBuilder
import io.github.jdreioe.wingmate.platform.FilePicker
import io.github.jdreioe.wingmate.platform.ArchiveEntry
import io.github.jdreioe.wingmate.platform.readEntryBytes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString
import kotlin.time.Clock

interface BackupMediaAccess {
    suspend fun read(path: String): ByteArray?
    suspend fun restore(archiveName: String, bytes: ByteArray): String
    suspend fun deleteRestored(path: String)
}

class UnavailableBackupMediaAccess : BackupMediaAccess {
    override suspend fun read(path: String): ByteArray? = null
    override suspend fun restore(archiveName: String, bytes: ByteArray): String = error("Backup media storage unavailable")
    override suspend fun deleteRestored(path: String) = Unit
}

@Serializable
data class WingmateBackupPayload(
    val boards: List<ObfBoard>,
    val boardSets: List<ObfBoardSet>,
    val phrases: List<Phrase>,
    val categories: List<CategoryItem> = emptyList(),
    val settings: Settings,
    val voices: List<Voice>,
    val selectedVoice: Voice?,
    val history: List<SaidText>,
    val dictionary: List<PronunciationEntry>,
    val communicationSession: CommunicationSessionSnapshot = CommunicationSessionSnapshot(),
    /** Read-only compatibility with legacy backups. Credentials are never exported or restored. */
    val azureSpeechConfig: SpeechServiceConfig? = null
)

@Serializable
data class WingmateBackupFile(
    val path: String,
    val size: Long,
    val sha256: String
)

@Serializable
data class WingmateBackupManifest(
    val format: String = "wingmate-backup",
    val version: Int = 1,
    val createdAt: Long,
    val payload: WingmateBackupFile,
    val media: List<WingmateBackupFile>
)

enum class BackupFailureKind {
    Unavailable,
    NotFound,
    Validation,
    Persistence,
    Network,
}

sealed class BackupRestoreResult {
    data class Success(val payload: WingmateBackupPayload) : BackupRestoreResult()
    data class Failure(
        val kind: BackupFailureKind,
        val message: String,
        val isRetryable: Boolean,
    ) : BackupRestoreResult()
}

interface BackupManager {
    suspend fun exportBackup(): ByteArray
    suspend fun restoreBackup(path: String): BackupRestoreResult
}

internal class BackupMediaNotFoundException : Exception()

class CompleteBackupManager(
    private val boardRepository: BoardRepository,
    private val boardSetRepository: BoardSetRepository,
    private val phraseRepository: PhraseRepository,
    private val settingsRepository: SettingsRepository,
    private val voiceRepository: VoiceRepository,
    private val saidTextRepository: SaidTextRepository,
    private val communicationSessionDataSource: CommunicationSessionDataSource,
    private val dictionaryRepository: PronunciationDictionaryRepository,
    private val configRepository: ConfigRepository,
    private val filePicker: FilePicker?,
    private val mediaAccess: BackupMediaAccess
) : BackupManager {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }
    private val restoreMutex = Mutex()
    private val mutableRestoreRevision = MutableStateFlow(0L)
    val restoreRevision = mutableRestoreRevision.asStateFlow()

    override suspend fun exportBackup(): ByteArray {
        val original = snapshot()
        val sourcePaths = linkedMapOf<String, String>()
        fun archivePath(path: String?): String? {
            val source = path?.takeIf(String::isNotBlank) ?: return null
            sourcePaths.entries.firstOrNull { it.value == source }?.let { return it.key }
            val safeName = source.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "asset" }
            val name = "media/${source.encodeToByteArray().sha256().take(16)}-$safeName"
            sourcePaths[name] = source
            return name
        }
        fun archiveLocalUrl(url: String?): String? {
            val value = url?.takeIf(String::isNotBlank) ?: return null
            return if (value.startsWith("file://") || value.startsWith('/')) archivePath(value) else value
        }
        fun Message.archiveMedia(): Message = copy(
            parts = parts.map { part -> part.copy(recordingPath = archivePath(part.recordingPath)) },
            editProvenance = editProvenance.map { provenance ->
                provenance.copy(
                    originalPart = provenance.originalPart.copy(
                        recordingPath = archivePath(provenance.originalPart.recordingPath)
                    )
                )
            },
        )
        val portable = original.copy(
            boards = original.boards.map { board ->
                board.copy(
                    images = board.images.map { it.copy(path = archivePath(it.path), url = archiveLocalUrl(it.url)) },
                    sounds = board.sounds.map { it.copy(path = archivePath(it.path), url = archiveLocalUrl(it.url)) }
                )
            },
            phrases = original.phrases.map {
                it.copy(recordingPath = archivePath(it.recordingPath), imageUrl = archiveLocalUrl(it.imageUrl))
            },
            history = original.history.map { it.copy(audioFilePath = archivePath(it.audioFilePath)) },
            communicationSession = original.communicationSession.copy(
                activeMessage = original.communicationSession.activeMessage.archiveMedia(),
                heldMessage = original.communicationSession.heldMessage?.archiveMedia(),
            ),
        )
        val payloadBytes = json.encodeToString(portable).encodeToByteArray()
        val mediaEntries = sourcePaths.map { (archiveName, sourcePath) ->
            archiveName to (mediaAccess.read(sourcePath)
                ?: throw BackupMediaNotFoundException())
        }
        val manifest = WingmateBackupManifest(
            createdAt = Clock.System.now().toEpochMilliseconds(),
            payload = payloadBytes.fileDescription(PAYLOAD_PATH),
            media = mediaEntries.map { (name, bytes) -> bytes.fileDescription(name) }
        )
        val entries = listOf(
            MANIFEST_PATH to json.encodeToString(manifest).encodeToByteArray(),
            PAYLOAD_PATH to payloadBytes
        ) + mediaEntries
        return ZipBuilder.build(entries).getOrThrow()
    }

    override suspend fun restoreBackup(path: String): BackupRestoreResult = restoreMutex.withLock {
        val picker = filePicker ?: return@withLock BackupRestoreResult.Failure(
            kind = BackupFailureKind.Unavailable,
            message = "File import is unavailable",
            isRetryable = false,
        )
        val archive = try {
            picker.openArchive(path)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            null
        }
            ?: return@withLock BackupRestoreResult.Failure(
                kind = BackupFailureKind.NotFound,
                message = "Could not open backup archive",
                isRetryable = false,
            )
        val restoredMedia = mutableListOf<String>()
        var failureKind = BackupFailureKind.Validation
        try {
            val entries = archive.entries()
            validateEntries(entries)
            val manifestBytes = archive.readEntryBytes(MANIFEST_PATH, MAX_JSON_BYTES)
            val manifest = json.decodeFromString<WingmateBackupManifest>(manifestBytes.decodeToString())
            require(manifest.format == "wingmate-backup") { "This is not a Wingmate backup" }
            require(manifest.version == 1) { "Backup version ${manifest.version} is not supported" }
            val declaredPaths = listOf(manifest.payload.path) + manifest.media.map { it.path }
            require(declaredPaths.distinct().size == declaredPaths.size) { "Backup manifest contains duplicate files" }
            val availablePaths = entries.map { it.name }.toSet()
            require(manifest.payload.path == PAYLOAD_PATH && declaredPaths.all { it in availablePaths }) {
                "Backup manifest references a missing file"
            }
            val payloadBytes = archive.readEntryBytes(manifest.payload.path, MAX_JSON_BYTES)
            require(payloadBytes.matches(manifest.payload)) { "Backup data checksum does not match" }
            var payload = json.decodeFromString<WingmateBackupPayload>(payloadBytes.decodeToString())
                // Legacy backups may contain a plaintext key. Never return or import it.
                .copy(azureSpeechConfig = null)

            val restoredPaths = mutableMapOf<String, String>()
            manifest.media.forEach { file ->
                val bytes = archive.readEntryBytes(file.path, MAX_MEDIA_BYTES)
                require(bytes.matches(file)) { "Media checksum does not match: ${file.path}" }
                failureKind = BackupFailureKind.Persistence
                val restored = mediaAccess.restore(file.path, bytes)
                restoredMedia += restored
                restoredPaths[file.path] = restored
                failureKind = BackupFailureKind.Validation
            }
            fun restored(path: String?): String? = path?.let { restoredPaths[it] ?: error("Missing media: $it") }
            fun restoredLocalUrl(url: String?): String? = url?.let { value ->
                restoredPaths[value]?.let { "file://$it" } ?: value
            }
            fun Message.restoreMedia(): Message = copy(
                parts = parts.map { part -> part.copy(recordingPath = restored(part.recordingPath)) },
                editProvenance = editProvenance.map { provenance ->
                    provenance.copy(
                        originalPart = provenance.originalPart.copy(
                            recordingPath = restored(provenance.originalPart.recordingPath)
                        )
                    )
                },
            )
            payload = payload.copy(
                boards = payload.boards.map { board ->
                    board.copy(
                        images = board.images.map { it.copy(path = restored(it.path), url = restoredLocalUrl(it.url)) },
                        sounds = board.sounds.map { it.copy(path = restored(it.path), url = restoredLocalUrl(it.url)) }
                    )
                },
                phrases = payload.phrases.map {
                    it.copy(recordingPath = restored(it.recordingPath), imageUrl = restoredLocalUrl(it.imageUrl))
                },
                history = payload.history.map { it.copy(audioFilePath = restored(it.audioFilePath)) },
                communicationSession = payload.communicationSession.copy(
                    activeMessage = payload.communicationSession.activeMessage.restoreMedia(),
                    heldMessage = payload.communicationSession.heldMessage?.restoreMedia(),
                ),
            )
            validatePayload(payload)
            failureKind = BackupFailureKind.Persistence
            val previous = snapshot()
            try {
                replaceAll(payload)
            } catch (error: Throwable) {
                runCatching { replaceAll(previous) }
                throw error
            }
            mutableRestoreRevision.value += 1
            BackupRestoreResult.Success(payload)
        } catch (failure: CancellationException) {
            restoredMedia.forEach { runCatching { mediaAccess.deleteRestored(it) } }
            throw failure
        } catch (_: Exception) {
            restoredMedia.forEach { runCatching { mediaAccess.deleteRestored(it) } }
            BackupRestoreResult.Failure(
                kind = failureKind,
                message = if (failureKind == BackupFailureKind.Validation) {
                    "Backup is invalid or unsupported"
                } else {
                    "Backup could not be restored safely"
                },
                isRetryable = failureKind == BackupFailureKind.Persistence || failureKind == BackupFailureKind.Network,
            )
        } finally {
            archive.close()
        }
    }

    private suspend fun snapshot() = WingmateBackupPayload(
        boards = boardRepository.listBoards(),
        boardSets = boardSetRepository.listBoardSets(),
        phrases = phraseRepository.getAll(),
        // categories is read-compat only (Q2=a); new backups write empty list and phrases contain folder-Phrases
        categories = emptyList(),
        settings = settingsRepository.get(),
        voices = voiceRepository.getVoices(),
        selectedVoice = voiceRepository.getSelected(),
        history = saidTextRepository.list(),
        dictionary = dictionaryRepository.getAll(),
        communicationSession = communicationSessionDataSource.load().valueOrThrow(),
        azureSpeechConfig = null
    )

    private suspend fun replaceAll(payload: WingmateBackupPayload) {
        boardSetRepository.listBoardSets().forEach { boardSetRepository.deleteBoardSet(it.id) }
        boardRepository.listBoards().forEach { boardRepository.deleteBoard(it.id) }
        phraseRepository.getAll().forEach { phraseRepository.delete(it.id) }
        saidTextRepository.deleteAll()
        dictionaryRepository.clear()

        boardRepository.saveBoards(payload.boards)
        payload.boardSets.forEach { boardSetRepository.saveBoardSet(it) }
        // Q2=a migration: old backups with flat categories → folder-Phrases
        val migratedCategories = payload.categories.mapNotNull { cat ->
            val id = cat.id.ifBlank { return@mapNotNull null }
            // skip if a phrase with same id already exists (new backup or duplicate)
            if (payload.phrases.any { it.id == id }) return@mapNotNull null
            Phrase(
                id = id,
                text = cat.name?.trim().orEmpty().ifEmpty { "Category" },
                linkedBoardId = id,
                isGridItem = false,
                createdAt = Clock.System.now().toEpochMilliseconds(),
            )
        }
        val allPhrases = payload.phrases + migratedCategories
        allPhrases.forEach { phraseRepository.add(it) }
        settingsRepository.update(payload.settings)
        voiceRepository.saveVoices(payload.voices)
        payload.selectedVoice?.let { voiceRepository.saveSelected(it) }
        saidTextRepository.addAll(payload.history)
        payload.dictionary.forEach { dictionaryRepository.add(it) }
        communicationSessionDataSource.save(payload.communicationSession).valueOrThrow()
    }

    private fun validatePayload(payload: WingmateBackupPayload) {
        require(payload.boards.map { it.id }.distinct().size == payload.boards.size) { "Duplicate board IDs" }
        require(payload.boardSets.map { it.id }.distinct().size == payload.boardSets.size) { "Duplicate screen IDs" }
        val boardsById = payload.boards.associateBy { it.id }
        payload.boardSets.forEach { screen ->
            val pages = screen.boardIds.map { pageId ->
                requireNotNull(boardsById[pageId]) { "Screen '${screen.name}' references a missing Page" }
            }
            BoardSetGraph(screen, pages).requireValid()
        }
        require(payload.boardSets.count { it.kind == ScreenKind.Typing } <= 1) {
            "Backup contains more than one Typing Screen"
        }
        payload.boardSets.filter { it.kind == ScreenKind.Typing }.forEach { typingScreen ->
            require(typingScreen.boardIds.size == 1) { "Typing Screen must contain one template page" }
            require(typingScreen.rootBoardId == typingScreen.boardIds.single()) {
                "Typing Screen template must be its starting Page"
            }
        }
    }

    private fun validateEntries(entries: List<ArchiveEntry>) {
        require(entries.size <= MAX_ENTRIES) { "Backup contains too many files" }
        require(entries.map { it.name }.distinct().size == entries.size) { "Backup contains duplicate file names" }
        require(entries.any { it.name == MANIFEST_PATH }) { "Backup manifest is missing" }
        var total = 0L
        entries.forEach { entry ->
            val name = entry.name
            val size = entry.uncompressedSize
            require(!entry.isEncrypted) { "Encrypted backup entries are not supported: $name" }
            require(
                name.isNotBlank() && !name.startsWith('/') && '\\' !in name && '\u0000' !in name &&
                    name.split('/').none { it == ".." }
            ) {
                "Unsafe backup path: $name"
            }
            require(size >= 0 && size <= MAX_MEDIA_BYTES) { "Backup entry is too large: $name" }
            require(total <= Long.MAX_VALUE - size) { "Backup size overflows the supported limit" }
            total += size
        }
        require(total <= MAX_TOTAL_BYTES) { "Backup is too large" }
    }

    private fun ByteArray.fileDescription(path: String) = WingmateBackupFile(path, size.toLong(), sha256())
    private fun ByteArray.matches(file: WingmateBackupFile): Boolean = size.toLong() == file.size && sha256() == file.sha256
    private fun ByteArray.sha256(): String = toByteString().sha256().hex()

    private fun <T> CommunicationStorageResult<T>.valueOrThrow(): T = when (this) {
        is CommunicationStorageResult.Success -> value
        is CommunicationStorageResult.Failure -> error("Communication session could not be persisted")
    }

    private companion object {
        const val MANIFEST_PATH = "manifest.json"
        const val PAYLOAD_PATH = "data/user-data.json"
        const val MAX_ENTRIES = 4096
        const val MAX_JSON_BYTES = 64L * 1024 * 1024
        const val MAX_MEDIA_BYTES = 512L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
    }
}
