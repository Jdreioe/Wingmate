package io.github.jdreioe.wingmate.kde

import io.github.jdreioe.wingmate.domain.*
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.infrastructure.normalizedIfValid
import io.github.jdreioe.wingmate.infrastructure.validatedForStorage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private val configDir = File(System.getProperty("user.home"), ".config/wingmate").apply { mkdirs() }
private val json = Json { 
    ignoreUnknownKeys = true 
    prettyPrint = true 
    encodeDefaults = true
}

class JsonFileSettingsRepository(
    private val file: File = File(configDir, "settings.json"),
    private val writer: suspend (File, String) -> Unit = ::writeTextAtomically,
) : SettingsRepository {
    private val mutex = Mutex()

    override suspend fun get(): Settings = mutex.withLock {
        readJson<Settings>(file).valueOr(::Settings)
    }

    override suspend fun update(settings: Settings): Settings = mutex.withLock {
        readJson<Settings>(file).valueOr(::Settings) // Never replace an unreadable store.
        writeJson(file, settings, writer)
        settings
    }
}

class JsonFileConfigRepository : ConfigRepository {
    private val file = File(configDir, "config.json")
    private val secureStore = LinuxSpeechCredentialStore()
    private val googleSecureStore = LinuxSpeechCredentialStore(
        purpose = "google-speech",
        entry = "google-speech-config-v1",
        legacyEntry = null,
        label = "Wingmate Google Cloud Speech configuration",
    )
    private val mutex = Mutex()

    override suspend fun getSpeechConfig(): SpeechServiceConfig? = mutex.withLock {
        withContext(Dispatchers.IO) {
            // A legacy file is authoritative until it has been verified in
            // secure storage. This avoids silently selecting an older keyring
            // value if an earlier migration was interrupted or mismatched.
            if (file.exists()) {
                val legacy = decode(file.readText()).normalizedIfValid()
                secureStore.write(json.encodeToString(legacy))
                val stored = secureStore.readAfterWrite()?.let(::decode)
                check(stored == legacy) {
                    "Secure Azure credential migration could not be verified " +
                        "(stored=${stored != null}, endpointMatches=${stored?.endpoint == legacy.endpoint}, " +
                        "credentialMatches=${stored?.subscriptionKey == legacy.subscriptionKey}, " +
                        "secureStore=${secureStore.lastReadDiagnostic})"
                }
                check(file.delete() || !file.exists()) { "Could not delete legacy plaintext Azure configuration" }
                println("[PERSISTENCE] Migrated Azure speech configuration to the desktop keyring.")
                return@withContext legacy
            }
            secureStore.read()?.let(::decode)?.normalizedIfValid()
        }
    }

    override suspend fun saveSpeechConfig(config: SpeechServiceConfig) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val normalized = config.validatedForStorage()
            secureStore.write(json.encodeToString(normalized))
            val stored = secureStore.readAfterWrite()?.let(::decode)
            check(stored == normalized) {
                "Secure Azure credential write could not be verified " +
                    "(stored=${stored != null}, endpointMatches=${stored?.endpoint == normalized.endpoint}, " +
                    "credentialMatches=${stored?.subscriptionKey == normalized.subscriptionKey})"
            }
            check(file.delete() || !file.exists()) { "Could not delete legacy plaintext Azure configuration" }
            println("[PERSISTENCE] Saved Azure speech configuration securely; credentialConfigured=true")
        }
    }

    override suspend fun clearSpeechConfig() = mutex.withLock {
        withContext(Dispatchers.IO) {
            secureStore.delete()
            check(file.delete() || !file.exists()) { "Could not delete legacy plaintext Azure configuration" }
            println("[PERSISTENCE] Cleared Azure speech configuration; credentialConfigured=false")
        }
    }

    override suspend fun getGoogleSpeechConfig(): GoogleSpeechConfig? = mutex.withLock {
        withContext(Dispatchers.IO) {
            googleSecureStore.read()?.let { json.decodeFromString<GoogleSpeechConfig>(it) }
        }
    }

    override suspend fun saveGoogleSpeechConfig(config: GoogleSpeechConfig) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val normalized = config.copy(apiKey = config.apiKey.trim())
            require(normalized.apiKey.isNotEmpty()) { "Google Cloud API key is required" }
            googleSecureStore.write(json.encodeToString(normalized))
            val stored = googleSecureStore.readAfterWrite()
                ?.let { json.decodeFromString<GoogleSpeechConfig>(it) }
            check(stored == normalized) { "Secure Google credential write could not be verified" }
            println("[PERSISTENCE] Saved Google speech configuration securely; credentialConfigured=true")
        }
    }

    override suspend fun clearGoogleSpeechConfig() = mutex.withLock {
        withContext(Dispatchers.IO) {
            googleSecureStore.delete()
            println("[PERSISTENCE] Cleared Google speech configuration; credentialConfigured=false")
        }
    }

    private fun decode(value: String): SpeechServiceConfig =
        json.decodeFromString<SpeechServiceConfig>(value)
}

/**
 * Uses Secret Service when available, otherwise KWallet. There is intentionally
 * no file fallback: callers receive an error and can keep the key in memory.
 */
private class LinuxSpeechCredentialStore(
    private val purpose: String = "azure-speech",
    private val entry: String = "azure-speech-config-v2",
    private val legacyEntry: String? = "azure-speech-config",
    private val label: String = "Wingmate Azure Speech configuration",
) {
    private enum class Backend { SecretService, KWallet }
    private val backend: Backend? by lazy {
        when {
            commandExists("secret-tool") -> Backend.SecretService
            commandExists("kwallet-query") -> Backend.KWallet
            else -> null
        }
    }

    @Volatile var lastReadDiagnostic: String = "not attempted"
        private set

    fun read(): String? = when (backend) {
        Backend.SecretService -> run(listOf("secret-tool", "lookup", "application", APP_ID, "purpose", purpose))
            .also { lastReadDiagnostic = "Secret Service lookup exit=${it.exitCode}" }
            .takeIf { it.exitCode == 0 }?.output?.trim()?.takeIf(String::isNotEmpty)
        Backend.KWallet -> readKWallet()
        null -> null.also { lastReadDiagnostic = "no secure-store command found" }
    }

    fun readAfterWrite(): String? = read()

    fun write(value: String) {
        val result = when (backend) {
            // `secret-tool store` can leave multiple items with the same
            // attributes behind. A later lookup is then allowed to return an
            // older value, making a successful migration look unverified.
            // Clear matching entries first so the value we verify is the one
            // just written. The legacy plaintext file is retained until that
            // verification succeeds.
            Backend.SecretService -> {
                val clear = run(listOf("secret-tool", "clear", "application", APP_ID, "purpose", purpose))
                check(clear.exitCode == 0) { clear.output.ifBlank { "Could not replace speech credentials securely" } }
                run(
                    listOf("secret-tool", "store", "--label=$label", "application", APP_ID, "purpose", purpose),
                    value
                )
            }
            Backend.KWallet -> writeKWallet(value)
            null -> error("Secure credential storage is unavailable; install Secret Service/libsecret or KWallet")
        }
        check(result.exitCode == 0) { result.output.ifBlank { "Could not save speech credentials securely" } }
    }

    fun delete() {
        if (read() == null) return
        val result = when (backend) {
            Backend.SecretService -> run(listOf("secret-tool", "clear", "application", APP_ID, "purpose", purpose))
            Backend.KWallet -> removeKWalletEntry()
            null -> return
        }
        check(result.exitCode == 0) { result.output.ifBlank { "Could not clear speech credentials" } }
        check(read() == null) { "Secure speech credential deletion could not be verified" }
    }

private fun readKWallet(): String? =
        runCatching { withKWallet { wallet, handle -> wallet.readPassword(handle, FOLDER, entry, APP_ID) } }
            .getOrNull()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: readLegacyKWalletEntry()

    private fun readLegacyKWalletEntry(): String? {
        val legacy = legacyEntry ?: return null
        val result = run(listOf("kwallet-query", "-r", legacy, "-f", FOLDER, walletName()))
        lastReadDiagnostic = if (result.exitCode == 0) {
            "KWallet legacy entry read successfully"
        } else {
            "KWallet legacy entry read exit=${result.exitCode}: ${result.output.take(160)}"
        }
        return result.takeIf { it.exitCode == 0 }?.output?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun writeKWallet(value: String): Result = runCatching {
        withKWallet { wallet, handle ->
            if (!wallet.hasFolder(handle, FOLDER, APP_ID)) {
                check(wallet.createFolder(handle, FOLDER, APP_ID)) { "Could not create the KWallet folder" }
            }
            val result = wallet.writePassword(handle, FOLDER, entry, value, APP_ID)
            check(result == 0) { "KWallet write failed with code $result" }
        }
        Result(0, "")
    }.getOrElse { Result(-1, it.message.orEmpty()) }

    private fun removeKWalletEntry(): Result = runCatching {
        withKWallet { wallet, handle ->
            for (candidate in listOfNotNull(entry, legacyEntry)) {
                if (wallet.hasEntry(handle, FOLDER, candidate, APP_ID)) {
                    val result = wallet.removeEntry(handle, FOLDER, candidate, APP_ID)
                    check(result == 0) { "KWallet removal failed with code $result" }
                }
            }
        }
        Result(0, "")
    }.getOrElse { Result(-1, it.message.orEmpty()) }

    private fun <T> withKWallet(block: (KWalletDbus, Int) -> T): T {
        DBusConnectionBuilder.forSessionBus().withShared(false).build().use { connection ->
            var failure: Throwable? = null
            for ((service, path) in KWALLET_ENDPOINTS) {
                try {
                    val wallet = connection.getRemoteObject(service, path, KWalletDbus::class.java)
                    val handle = wallet.open(walletName(), 0L, APP_ID)
                    check(handle >= 0) { "Could not open wallet ${walletName()}" }
                    return block(wallet, handle)
                } catch (error: Throwable) {
                    failure = error
                }
            }
            throw IllegalStateException("Could not connect to the KWallet secure store", failure)
        }
    }

    private fun walletName() = System.getenv("WINGMATE_KWALLET")?.takeIf(String::isNotBlank) ?: "kdewallet"

    private data class Result(val exitCode: Int, val output: String)
    private fun run(command: List<String>, input: String? = null): Result = runCatching {
        val process = ProcessBuilder(command).start()
        if (input != null) process.outputStream.bufferedWriter().use { it.write(input) } else process.outputStream.close()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val error = process.errorStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        Result(exitCode, if (exitCode == 0) output else error.ifBlank { output })
    }.getOrElse { Result(-1, it.message.orEmpty()) }
    private fun commandExists(command: String) = run(listOf("which", command)).exitCode == 0

    private companion object {
        const val APP_ID = "io.github.jdreioe.wingmate"
        const val FOLDER = "Wingmate"
        val KWALLET_ENDPOINTS = listOf(
            "org.kde.kwalletd6" to "/modules/kwalletd6",
            "org.kde.kwalletd5" to "/modules/kwalletd5",
            "org.kde.kwalletd" to "/modules/kwalletd",
        )
    }
}

@DBusInterfaceName("org.kde.KWallet")
interface KWalletDbus : DBusInterface {
    fun open(wallet: String, windowId: Long, appId: String): Int
    fun hasFolder(handle: Int, folder: String, appId: String): Boolean
    fun createFolder(handle: Int, folder: String, appId: String): Boolean
    fun hasEntry(handle: Int, folder: String, entry: String, appId: String): Boolean
    fun readPassword(handle: Int, folder: String, entry: String, appId: String): String
    fun writePassword(handle: Int, folder: String, entry: String, value: String, appId: String): Int
    fun removeEntry(handle: Int, folder: String, entry: String, appId: String): Int
}

class JsonFileVoiceRepository(
    private val file: File = File(configDir, "voices.json"),
    private val selectedFile: File = File(configDir, "selected-voice.json"),
    private val writer: suspend (File, String) -> Unit = ::writeTextAtomically,
) : VoiceRepository {
    private val mutex = Mutex()

    override suspend fun getVoices(): List<Voice> = mutex.withLock {
        readJson<List<Voice>>(file).valueOr(::emptyList)
    }

    override suspend fun saveVoices(list: List<Voice>) = mutex.withLock {
        readJson<List<Voice>>(file).valueOr(::emptyList)
        writeJson(file, list, writer)
    }

    override suspend fun saveSelected(voice: Voice) = mutex.withLock {
        readJson<Voice>(selectedFile).nullableValue()
        writeJson(selectedFile, voice, writer)
    }

    override suspend fun getSelected(): Voice? = mutex.withLock {
        readJson<Voice>(selectedFile).nullableValue()
    }
}

class JsonFilePronunciationDictionaryRepository(
    private val file: File = File(configDir, "pronunciations.json"),
    private val writer: suspend (File, String) -> Unit = ::writeTextAtomically,
) : PronunciationDictionaryRepository {
    private val mutex = Mutex()

    override suspend fun getAll(): List<PronunciationEntry> = mutex.withLock {
        readEntries().values.sortedBy { it.word.lowercase() }
    }

    override suspend fun add(entry: PronunciationEntry) = mutex.withLock {
        val entries = readEntries().toMutableMap()
        entries[entry.word.lowercase()] = entry
        save(entries.values)
    }

    override suspend fun delete(word: String) = mutex.withLock {
        val entries = readEntries().toMutableMap()
        if (entries.remove(word.lowercase()) != null) save(entries.values)
    }

    override suspend fun clear() = mutex.withLock {
        readEntries()
        save(emptyList())
    }

    private fun readEntries(): Map<String, PronunciationEntry> =
        readJson<List<PronunciationEntry>>(file)
            .valueOr(::emptyList)
            .associateBy { it.word.lowercase() }

    private suspend fun save(entries: Collection<PronunciationEntry>) =
        writeJson(file, entries.sortedBy { it.word.lowercase() }, writer)
}

class JsonFilePhraseRepository(
    private val file: File = File(configDir, "phrases.json"),
    private val writer: suspend (File, String) -> Unit = ::writeTextAtomically,
) : PhraseRepository {
    private val mutex = Mutex()

    override suspend fun getAll(): List<Phrase> = mutex.withLock { readPhrases() }

    override suspend fun add(phrase: Phrase): Phrase = mutex.withLock {
        val phrases = readPhrases().toMutableList()
        val stored = phrase.copy(
            id = phrase.id.ifBlank { "phrase-${java.util.UUID.randomUUID()}" },
            createdAt = phrase.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
        )
        phrases += stored
        save(phrases)
        stored
    }

    override suspend fun update(phrase: Phrase): Phrase = mutex.withLock {
        val phrases = readPhrases().toMutableList()
        val index = phrases.indexOfFirst { it.id == phrase.id }
        if (index >= 0) phrases[index] = phrase else phrases += phrase
        save(phrases)
        phrase
    }

    override suspend fun delete(id: String) = mutex.withLock {
        val phrases = readPhrases().toMutableList()
        if (phrases.removeAll { it.id == id }) save(phrases)
    }

    override suspend fun move(fromIndex: Int, toIndex: Int) = mutex.withLock {
        val phrases = readPhrases().toMutableList()
        if (fromIndex !in phrases.indices) return@withLock
        val item = phrases.removeAt(fromIndex)
        phrases.add(toIndex.coerceIn(0, phrases.size), item)
        save(phrases)
    }

    private fun readPhrases() = readJson<List<Phrase>>(file).valueOr(::emptyList)
    private suspend fun save(phrases: List<Phrase>) = writeJson(file, phrases, writer)
}

class JsonFileBoardRepository(
    private val file: File = File(configDir, "boards.json"),
    private val writer: suspend (File, String) -> Unit = ::writeTextAtomically,
) : BoardRepository {
    private val mutex = Mutex()

    override suspend fun getBoard(id: String): ObfBoard? = mutex.withLock { readBoards()[id] }

    override suspend fun saveBoard(board: ObfBoard) = mutex.withLock {
        val boards = readBoards().toMutableMap()
        boards[board.id] = board
        save(boards.values)
    }

    override suspend fun saveBoards(boards: List<ObfBoard>) = mutex.withLock {
        if (boards.isEmpty()) return@withLock
        val updated = readBoards().toMutableMap()
        boards.forEach { updated[it.id] = it }
        save(updated.values)
    }

    override suspend fun listBoards(): List<ObfBoard> = mutex.withLock { readBoards().values.toList() }

    override suspend fun deleteBoard(id: String) = mutex.withLock {
        val boards = readBoards().toMutableMap()
        if (boards.remove(id) != null) save(boards.values)
    }

    private fun readBoards() = readJson<List<ObfBoard>>(file).valueOr(::emptyList).associateBy { it.id }
    private suspend fun save(boards: Collection<ObfBoard>) = writeJson(file, boards.toList(), writer)
}

class JsonFileBoardSetRepository(
    private val file: File = File(configDir, "board-sets.json"),
    private val writer: suspend (File, String) -> Unit = ::writeTextAtomically,
) : BoardSetRepository {
    private val mutex = Mutex()

    override suspend fun getBoardSet(id: String): ObfBoardSet? = mutex.withLock { readBoardSets()[id] }

    override suspend fun saveBoardSet(boardSet: ObfBoardSet) = mutex.withLock {
        val boardSets = readBoardSets().toMutableMap()
        boardSets[boardSet.id] = boardSet
        save(boardSets.values)
    }

    override suspend fun listBoardSets(): List<ObfBoardSet> = mutex.withLock {
        readBoardSets().values.sortedByDescending { it.updatedAt }
    }

    override suspend fun deleteBoardSet(id: String) = mutex.withLock {
        val boardSets = readBoardSets().toMutableMap()
        if (boardSets.remove(id) != null) save(boardSets.values)
    }

    private fun readBoardSets() =
        readJson<List<ObfBoardSet>>(file).valueOr(::emptyList).associateBy { it.id }

    private suspend fun save(boardSets: Collection<ObfBoardSet>) = writeJson(file, boardSets.toList(), writer)
}

private inline fun <reified T> readJson(file: File): PersistenceRead<T> {
    if (!file.exists()) return PersistenceRead.Absent
    val text = try {
        file.readText()
    } catch (error: Exception) {
        return PersistenceRead.Failed(PersistenceError.Io, error)
    }
    return try {
        PersistenceRead.Loaded(json.decodeFromString<T>(text))
    } catch (error: Exception) {
        quarantineCorruptFile(file)
        PersistenceRead.Failed(PersistenceError.CorruptOrUnsupported, error)
    }
}

private fun <T> PersistenceRead<T>.nullableValue(): T? = when (this) {
    PersistenceRead.Absent -> null
    is PersistenceRead.Loaded -> value
    is PersistenceRead.Failed -> throw PersistenceException(error, cause)
}

private fun quarantineCorruptFile(file: File) {
    runCatching {
        val prefix = "${file.name}.corrupt-"
        if (file.parentFile?.listFiles().orEmpty().any { it.name.startsWith(prefix) }) return
        val quarantine = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
        Files.copy(file.toPath(), quarantine.toPath())
    }
}

private suspend inline fun <reified T> writeJson(
    file: File,
    value: T,
    noinline writer: suspend (File, String) -> Unit,
) {
    val encoded = try {
        json.encodeToString(value)
    } catch (error: Exception) {
        throw PersistenceException(PersistenceError.Io, error)
    }
    try {
        writer(file, encoded)
    } catch (error: PersistenceException) {
        throw error
    } catch (error: Exception) {
        throw PersistenceException(PersistenceError.Io, error)
    }
}

private suspend fun writeTextAtomically(file: File, text: String) = withContext(Dispatchers.IO) {
    val parent = file.absoluteFile.parentFile
    try {
        check(parent.mkdirs() || parent.isDirectory) { "Could not create persistence directory" }
        val temporary = Files.createTempFile(parent.toPath(), ".${file.name}.", ".tmp").toFile()
        try {
            FileOutputStream(temporary).use { output ->
                output.write(text.encodeToByteArray())
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    } catch (error: AtomicMoveNotSupportedException) {
        throw PersistenceException(PersistenceError.Io, error)
    } catch (error: PersistenceException) {
        throw error
    } catch (error: Exception) {
        throw PersistenceException(PersistenceError.Io, error)
    }
}
