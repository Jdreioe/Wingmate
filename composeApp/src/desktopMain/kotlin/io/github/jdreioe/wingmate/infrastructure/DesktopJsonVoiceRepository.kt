package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.VoiceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class DesktopJsonVoiceRepository : VoiceRepository {
    private val dbDir: Path by lazy {
        val home = System.getProperty("user.home")
        val dir = Paths.get(home, ".config", "wingmate")
        if (!Files.exists(dir)) Files.createDirectories(dir)
        dir
    }

    private val file: Path by lazy { dbDir.resolve("selected_voice.json") }
    private val voicesFile: Path by lazy { dbDir.resolve("voice_list.json") }
    private val json = Json { prettyPrint = true }
    
    // Simple logging replacement for multiplatform compatibility
    private fun logInfo(message: String, vararg args: Any) {
        println("[INFO] DesktopJsonVoiceRepository: ${String.format(message, *args)}")
    }
    
    private fun logError(message: String, throwable: Throwable? = null) {
        println("[ERROR] DesktopJsonVoiceRepository: $message")
        throwable?.printStackTrace()
    }

    override suspend fun getVoices(): List<Voice> = withContext(Dispatchers.IO) {
        try {
            if (!Files.exists(voicesFile)) return@withContext emptyList()
            val text = Files.readString(voicesFile)
            if (text.isBlank()) return@withContext emptyList()
            return@withContext json.decodeFromString(ListSerializer(Voice.serializer()), text)
        } catch (t: Throwable) {
            logError("Failed to read voices list JSON", t)
            return@withContext emptyList()
        }
    }

    override suspend fun saveVoices(list: List<Voice>) = withContext(Dispatchers.IO) {
        try {
            val text = json.encodeToString(ListSerializer(Voice.serializer()), list)
            Files.writeString(voicesFile, text)
            logInfo("Saved voices list to JSON file: %s (%d items)", voicesFile.toAbsolutePath(), list.size)
        } catch (t: Throwable) {
            logError("Failed to save voices list to JSON", t)
            throw t
        }
    }

    override suspend fun saveSelected(voice: Voice) = withContext(Dispatchers.IO) {
        try {
            val text = json.encodeToString(Voice.serializer(), voice)
            Files.writeString(file, text)
            logInfo("Saved selected voice to JSON file: %s", file.toAbsolutePath())
        } catch (t: Throwable) {
            logError("Failed to save selected voice to JSON", t)
            throw t
        }
    }

    override suspend fun getSelected(): Voice? = withContext(Dispatchers.IO) {
        try {
            if (!Files.exists(file)) {
                logInfo("No selected voice JSON file found: %s", file.toAbsolutePath())
                return@withContext null
            }
            val text = Files.readString(file)
            if (text.isBlank()) return@withContext null
            val v = json.decodeFromString(Voice.serializer(), text)
            logInfo("Loaded selected voice from JSON: %s", v.name ?: "Unknown")
            return@withContext v
        } catch (t: Throwable) {
            logError("Failed to read selected voice JSON", t)
            return@withContext null
        }
    }
}
