package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class DesktopConfigRepository : ConfigRepository {
    private val configPath: Path by lazy {
        val home = System.getProperty("user.home")
        val dir = Paths.get(home, ".config", "wingmate")
        if (!Files.exists(dir)) Files.createDirectories(dir)
        dir.resolve("azure_config.json")
    }

    private val json = Json { prettyPrint = true }

    override suspend fun getSpeechConfig(): SpeechServiceConfig? = withContext(Dispatchers.IO) {
        if (!Files.exists(configPath)) return@withContext null
        try {
            val text = Files.readString(configPath)
            json.decodeFromString(SpeechServiceConfig.serializer(), text)
        } catch (t: Throwable) {
            null
        }
    }

    override suspend fun saveSpeechConfig(config: SpeechServiceConfig) = withContext(Dispatchers.IO) {
        try {
            val text = json.encodeToString(SpeechServiceConfig.serializer(), config)
            // debug log to stdout so we can see when save is invoked
            println("[DesktopConfigRepository] writing config to: ${'$'}configPath")
            println(text)
            Files.writeString(configPath, text)
            println("[DesktopConfigRepository] write successful")
        } catch (t: Throwable) {
            System.err.println("[DesktopConfigRepository] failed to write config: ${'$'}t")
            t.printStackTrace()
            throw t
        }
    }
}
