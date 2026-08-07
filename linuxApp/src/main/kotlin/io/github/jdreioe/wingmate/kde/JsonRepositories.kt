package io.github.jdreioe.wingmate.kde

import io.github.jdreioe.wingmate.domain.*
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
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
    private var cached: SpeechServiceConfig? = null

    init {
        println("[PERSISTENCE] JsonFileConfigRepository init. File: ${file.absolutePath}")
        if (file.exists()) {
            try {
                val text = file.readText()
                cached = json.decodeFromString<SpeechServiceConfig>(text)
                println("[PERSISTENCE] Loaded config from disk.")
            } catch (e: Exception) {
                println("[PERSISTENCE] Error loading config: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    override suspend fun getSpeechConfig(): SpeechServiceConfig? = withContext(Dispatchers.IO) {
        println("[PERSISTENCE] getSpeechConfig called. Result found: ${cached != null}")
        cached
    }

    override suspend fun saveSpeechConfig(config: SpeechServiceConfig) = withContext(Dispatchers.IO) {
        println("[PERSISTENCE] saveSpeechConfig called.")
        cached = config
        try {
            file.writeText(json.encodeToString(config))
            println("[PERSISTENCE] Saved config to disk.")
        } catch (e: Exception) {
            println("[PERSISTENCE] Error saving config: ${e.message}")
            e.printStackTrace()
        }
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
