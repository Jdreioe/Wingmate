package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.VoiceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
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
    private val json = Json { prettyPrint = true }
    private val log = LoggerFactory.getLogger("DesktopJsonVoiceRepository")

    override suspend fun getVoices(): List<Voice> = withContext(Dispatchers.IO) {
        // No local catalog persisted here; rely on AzureVoiceCatalog for listings
        emptyList()
    }

    override suspend fun saveSelected(voice: Voice) = withContext(Dispatchers.IO) {
        try {
            val text = json.encodeToString(Voice.serializer(), voice)
            Files.writeString(file, text)
            log.info("Saved selected voice to JSON file: {}", file.toAbsolutePath())
        } catch (t: Throwable) {
            log.error("Failed to save selected voice to JSON", t)
            throw t
        }
    }

    override suspend fun getSelected(): Voice? = withContext(Dispatchers.IO) {
        try {
            if (!Files.exists(file)) {
                log.info("No selected voice JSON file found: {}", file.toAbsolutePath())
                return@withContext null
            }
            val text = Files.readString(file)
            if (text.isBlank()) return@withContext null
            val v = json.decodeFromString(Voice.serializer(), text)
            log.info("Loaded selected voice from JSON: {}", v.name)
            return@withContext v
        } catch (t: Throwable) {
            log.error("Failed to read selected voice JSON", t)
            return@withContext null
        }
    }
}
