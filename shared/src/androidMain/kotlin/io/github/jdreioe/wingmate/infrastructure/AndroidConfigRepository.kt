package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class AndroidConfigRepository(private val context: Context) : ConfigRepository {
    private val prefs by lazy { context.getSharedPreferences("wingmate_prefs", Context.MODE_PRIVATE) }
    private val json = Json { prettyPrint = true }

    override suspend fun getSpeechConfig(): SpeechServiceConfig? = withContext(Dispatchers.IO) {
        val log = LoggerFactory.getLogger("AndroidConfigRepository")
        val text = prefs.getString("speech_config", null)
        if (text.isNullOrBlank()) {
            log.info("No speech config found in SharedPreferences")
            return@withContext null
        }
        return@withContext try {
            val cfg = json.decodeFromString(SpeechServiceConfig.serializer(), text)
            log.info("Loaded speech config from SharedPreferences: {}", cfg)
            cfg
        } catch (t: Throwable) {
            log.warn("Failed to decode speech config from SharedPreferences", t)
            null
        }
    }

    override suspend fun saveSpeechConfig(config: SpeechServiceConfig) = withContext(Dispatchers.IO) {
        val log = LoggerFactory.getLogger("AndroidConfigRepository")
        val text = json.encodeToString(SpeechServiceConfig.serializer(), config)
        prefs.edit().putString("speech_config", text).apply()
        log.info("Saved speech config to SharedPreferences: {}", config)
    }
}
