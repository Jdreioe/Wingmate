package io.github.jdreioe.wingmate.kde

import io.github.jdreioe.wingmate.domain.*
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private val configDir = File(System.getProperty("user.home"), ".config/wingmate").apply { mkdirs() }
private val json = Json { 
    ignoreUnknownKeys = true 
    prettyPrint = true 
    encodeDefaults = true
}

class JsonFileSettingsRepository : SettingsRepository {
    private val file = File(configDir, "settings.json")
    private var cached: Settings = Settings()

    init {
        println("[PERSISTENCE] JsonFileSettingsRepository init. File: ${file.absolutePath}")
        if (file.exists()) {
            try {
                val text = file.readText()
                println("[PERSISTENCE] Read settings content: $text")
                cached = json.decodeFromString<Settings>(text)
                println("[PERSISTENCE] Decoded settings successfully.")
            } catch (e: Exception) {
                println("[PERSISTENCE] Error reading settings: ${e.message}")
                e.printStackTrace()
            }
        } else {
            println("[PERSISTENCE] Settings file does not exist, using defaults.")
        }
    }

    override suspend fun get(): Settings = withContext(Dispatchers.IO) {
        println("[PERSISTENCE] getSettings called. Current value: $cached")
        cached
    }

    override suspend fun update(settings: Settings): Settings = withContext(Dispatchers.IO) {
        println("[PERSISTENCE] updateSettings called with: $settings")
        cached = settings
        try {
            val text = json.encodeToString(settings)
            file.writeText(text)
            println("[PERSISTENCE] Saved settings to disk: $text")
        } catch (e: Exception) {
            println("[PERSISTENCE] Error saving settings: ${e.message}")
            e.printStackTrace()
        }
        settings
    }
}

class JsonFileConfigRepository : ConfigRepository {
    private val file = File(configDir, "config.json")
    private val secureStore = LinuxAzureConfigStore()
    private val mutex = Mutex()

    override suspend fun getSpeechConfig(): SpeechServiceConfig? = mutex.withLock {
        withContext(Dispatchers.IO) {
            secureStore.read()?.let(::decode)?.also {
                check(file.delete() || !file.exists()) { "Could not delete legacy plaintext Azure configuration" }
                return@withContext it
            }
            if (!file.exists()) return@withContext null
            val legacy = decode(file.readText())
            secureStore.write(json.encodeToString(legacy))
            check(secureStore.read()?.let(::decode) == legacy) {
                "Secure Azure credential migration could not be verified"
            }
            check(file.delete() || !file.exists()) { "Could not delete legacy plaintext Azure configuration" }
            println("[PERSISTENCE] Migrated Azure speech configuration to the desktop keyring.")
            legacy
        }
    }

    override suspend fun saveSpeechConfig(config: SpeechServiceConfig) = mutex.withLock {
        withContext(Dispatchers.IO) {
            require(config.subscriptionKey.isNotBlank()) { "Azure subscription key must not be blank" }
            secureStore.write(json.encodeToString(config))
            check(secureStore.read()?.let(::decode) == config) { "Secure Azure credential write could not be verified" }
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

    private fun decode(value: String): SpeechServiceConfig =
        json.decodeFromString<SpeechServiceConfig>(value)
}

/**
 * Uses Secret Service when available, otherwise KWallet. There is intentionally
 * no file fallback: callers receive an error and can keep the key in memory.
 */
private class LinuxAzureConfigStore {
    private enum class Backend { SecretService, KWallet }
    private val backend: Backend? by lazy {
        when {
            commandExists("secret-tool") -> Backend.SecretService
            commandExists("kwallet-query") -> Backend.KWallet
            else -> null
        }
    }

    fun read(): String? = when (backend) {
        Backend.SecretService -> run(listOf("secret-tool", "lookup", "application", APP_ID, "purpose", PURPOSE))
            .takeIf { it.exitCode == 0 }?.output?.trim()?.takeIf(String::isNotEmpty)
        Backend.KWallet -> run(listOf("kwallet-query", "-r", ENTRY, "-f", FOLDER, walletName()))
            .takeIf { it.exitCode == 0 }?.output?.trim()?.takeIf(String::isNotEmpty)
        null -> null
    }

    fun write(value: String) {
        val result = when (backend) {
            Backend.SecretService -> run(
                listOf("secret-tool", "store", "--label=Wingmate Azure Speech configuration", "application", APP_ID, "purpose", PURPOSE),
                value
            )
            Backend.KWallet -> run(listOf("kwallet-query", "-w", ENTRY, "-f", FOLDER, walletName()), value)
            null -> error("Secure credential storage is unavailable; install Secret Service/libsecret or KWallet")
        }
        check(result.exitCode == 0) { result.output.ifBlank { "Could not save Azure configuration securely" } }
    }

    fun delete() {
        if (read() == null) return
        val result = when (backend) {
            Backend.SecretService -> run(listOf("secret-tool", "clear", "application", APP_ID, "purpose", PURPOSE))
            Backend.KWallet -> run(listOf("kwallet-query", "-w", ENTRY, "-f", FOLDER, walletName()), "")
            null -> return
        }
        check(result.exitCode == 0) { result.output.ifBlank { "Could not clear Azure configuration" } }
        check(read() == null) { "Secure Azure configuration deletion could not be verified" }
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
        const val PURPOSE = "azure-speech"
        const val FOLDER = "Wingmate"
        const val ENTRY = "azure-speech-config"
    }
}

class JsonFileVoiceRepository : VoiceRepository {
    private val file = File(configDir, "voices.json")
    private val selectedFile = File(configDir, "selected-voice.json")
    private val voices = mutableListOf<Voice>()
    private var selectedVoice: Voice? = null

    init {
        println("[PERSISTENCE] JsonFileVoiceRepository init. File: ${file.absolutePath}")
        if (file.exists()) {
            try {
                val list = json.decodeFromString<List<Voice>>(file.readText())
                voices.addAll(list)
                println("[PERSISTENCE] Loaded ${voices.size} voices from disk.")
            } catch (e: Exception) {
                println("[PERSISTENCE] Error loading voices: ${e.message}")
                e.printStackTrace()
            }
        }
        if (selectedFile.exists()) {
            try {
                selectedVoice = json.decodeFromString<Voice>(selectedFile.readText())
                println("[PERSISTENCE] Loaded selected voice: ${selectedVoice?.name}")
            } catch (e: Exception) {
                println("[PERSISTENCE] Error loading selected voice: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    override suspend fun getVoices(): List<Voice> = withContext(Dispatchers.IO) {
        println("[PERSISTENCE] getVoices called. Count: ${voices.size}")
        voices.toList()
    }

    override suspend fun saveVoices(list: List<Voice>) = withContext(Dispatchers.IO) {
        println("[PERSISTENCE] saveVoices called with ${list.size} voices.")
        voices.clear()
        voices.addAll(list)
        try {
            file.writeText(json.encodeToString(voices))
            println("[PERSISTENCE] Saved voices to disk.")
        } catch (e: Exception) {
            println("[PERSISTENCE] Error saving voices: ${e.message}")
            e.printStackTrace()
        }
    }

    override suspend fun saveSelected(voice: Voice) = withContext(Dispatchers.IO) {
        println("[PERSISTENCE] saveSelected voice: ${voice.name}")
        selectedVoice = voice
        try {
            selectedFile.writeText(json.encodeToString(voice))
        } catch (e: Exception) {
            println("[PERSISTENCE] Error saving selected voice: ${e.message}")
        }
    }

    override suspend fun getSelected(): Voice? = withContext(Dispatchers.IO) {
        selectedVoice
    }
}

class JsonFilePronunciationDictionaryRepository(
    private val file: File = File(configDir, "pronunciations.json"),
) : PronunciationDictionaryRepository {
    private val mutex = Mutex()
    private val entries = runCatching {
        if (file.exists()) {
            json.decodeFromString<List<PronunciationEntry>>(file.readText())
                .associateBy { it.word.lowercase() }
                .toMutableMap()
        } else {
            mutableMapOf()
        }
    }.getOrElse { error ->
        System.err.println("[PERSISTENCE] Could not load pronunciation dictionary: ${error.message}")
        mutableMapOf()
    }

    override suspend fun getAll(): List<PronunciationEntry> = mutex.withLock {
        entries.values.sortedBy { it.word.lowercase() }
    }

    override suspend fun add(entry: PronunciationEntry) = mutex.withLock {
        entries[entry.word.lowercase()] = entry
        save()
    }

    override suspend fun delete(word: String) = mutex.withLock {
        if (entries.remove(word.lowercase()) != null) save()
    }

    override suspend fun clear() = mutex.withLock {
        entries.clear()
        save()
    }

    private suspend fun save() = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(json.encodeToString(entries.values.sortedBy { it.word.lowercase() }))
        check(temporary.renameTo(file) || runCatching {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
            true
        }.getOrDefault(false)) {
            "Could not save pronunciation dictionary"
        }
    }
}

class JsonFilePhraseRepository(
    private val file: File = File(configDir, "phrases.json"),
) : PhraseRepository {
    private val mutex = Mutex()
    private val phrases = runCatching {
        if (file.exists()) json.decodeFromString<List<Phrase>>(file.readText()).toMutableList()
        else mutableListOf()
    }.getOrElse { error ->
        System.err.println("[PERSISTENCE] Could not load phrases: ${error.message}")
        mutableListOf()
    }

    override suspend fun getAll(): List<Phrase> = mutex.withLock { phrases.toList() }

    override suspend fun add(phrase: Phrase): Phrase = mutex.withLock {
        val stored = phrase.copy(
            id = phrase.id.ifBlank { "phrase-${java.util.UUID.randomUUID()}" },
            createdAt = phrase.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
        )
        phrases += stored
        save()
        stored
    }

    override suspend fun update(phrase: Phrase): Phrase = mutex.withLock {
        val index = phrases.indexOfFirst { it.id == phrase.id }
        if (index >= 0) phrases[index] = phrase else phrases += phrase
        save()
        phrase
    }

    override suspend fun delete(id: String) = mutex.withLock {
        if (phrases.removeAll { it.id == id }) save()
    }

    override suspend fun move(fromIndex: Int, toIndex: Int) = mutex.withLock {
        if (fromIndex !in phrases.indices) return@withLock
        val item = phrases.removeAt(fromIndex)
        phrases.add(toIndex.coerceIn(0, phrases.size), item)
        save()
    }

    private suspend fun save() = writeJsonAtomically(file, phrases)
}

class JsonFileCategoryRepository(
    private val file: File = File(configDir, "categories.json"),
) : CategoryRepository {
    private val mutex = Mutex()
    private val categories = runCatching {
        if (file.exists()) json.decodeFromString<List<CategoryItem>>(file.readText()).toMutableList()
        else mutableListOf()
    }.getOrElse { error ->
        System.err.println("[PERSISTENCE] Could not load categories: ${error.message}")
        mutableListOf()
    }

    override suspend fun getAll(): List<CategoryItem> = mutex.withLock { categories.toList() }

    override suspend fun add(category: CategoryItem): CategoryItem = mutex.withLock {
        val stored = category.copy(id = category.id.ifBlank { "category-${java.util.UUID.randomUUID()}" })
        categories += stored
        save()
        stored
    }

    override suspend fun update(category: CategoryItem): CategoryItem = mutex.withLock {
        val index = categories.indexOfFirst { it.id == category.id }
        if (index >= 0) categories[index] = category else categories += category
        save()
        category
    }

    override suspend fun delete(id: String) = mutex.withLock {
        if (categories.removeAll { it.id == id }) save()
    }

    override suspend fun move(fromIndex: Int, toIndex: Int) = mutex.withLock {
        if (fromIndex !in categories.indices) return@withLock
        val item = categories.removeAt(fromIndex)
        categories.add(toIndex.coerceIn(0, categories.size), item)
        save()
    }

    private suspend fun save() = writeJsonAtomically(file, categories)
}

private suspend inline fun <reified T> writeJsonAtomically(file: File, value: T) =
    withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(json.encodeToString(value))
        check(temporary.renameTo(file) || runCatching {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
            true
        }.getOrDefault(false)) { "Could not save ${file.name}" }
    }

class JsonFileBoardRepository : BoardRepository {
    private val file = File(configDir, "boards.json")
    private val boards = runCatching {
        if (file.exists()) json.decodeFromString<List<ObfBoard>>(file.readText()).associateBy { it.id }.toMutableMap()
        else mutableMapOf()
    }.getOrElse { mutableMapOf() }

    override suspend fun getBoard(id: String): ObfBoard? = withContext(Dispatchers.IO) { boards[id] }

    override suspend fun saveBoard(board: ObfBoard) = withContext(Dispatchers.IO) {
        boards[board.id] = board
        file.writeText(json.encodeToString(boards.values.toList()))
    }

    override suspend fun listBoards(): List<ObfBoard> = withContext(Dispatchers.IO) { boards.values.toList() }

    override suspend fun deleteBoard(id: String) = withContext(Dispatchers.IO) {
        boards.remove(id)
        file.writeText(json.encodeToString(boards.values.toList()))
    }
}

class JsonFileBoardSetRepository : BoardSetRepository {
    private val file = File(configDir, "board-sets.json")
    private val boardSets = runCatching {
        if (file.exists()) json.decodeFromString<List<ObfBoardSet>>(file.readText()).associateBy { it.id }.toMutableMap()
        else mutableMapOf()
    }.getOrElse { mutableMapOf() }

    override suspend fun getBoardSet(id: String): ObfBoardSet? = withContext(Dispatchers.IO) { boardSets[id] }

    override suspend fun saveBoardSet(boardSet: ObfBoardSet) = withContext(Dispatchers.IO) {
        boardSets[boardSet.id] = boardSet
        file.writeText(json.encodeToString(boardSets.values.toList()))
    }

    override suspend fun listBoardSets(): List<ObfBoardSet> = withContext(Dispatchers.IO) {
        boardSets.values.sortedByDescending { it.updatedAt }
    }

    override suspend fun deleteBoardSet(id: String) = withContext(Dispatchers.IO) {
        boardSets.remove(id)
        file.writeText(json.encodeToString(boardSets.values.toList()))
    }
}
