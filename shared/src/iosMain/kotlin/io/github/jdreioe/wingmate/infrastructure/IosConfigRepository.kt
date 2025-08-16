package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.cinterop.autoreleasepool
import platform.Foundation.NSUserDefaults
import org.slf4j.LoggerFactory

class IosConfigRepository : ConfigRepository {
    private val defaults by lazy { NSUserDefaults.standardUserDefaults() }
    private val json = Json { prettyPrint = true }

    override suspend fun getSpeechConfig(): SpeechServiceConfig? = withContext(Dispatchers.Default) {
        val log = LoggerFactory.getLogger("IosConfigRepository")
        autoreleasepool {
            val text = defaults.stringForKey("speech_config")
            if (text == null) {
                log.info("No speech config found in NSUserDefaults")
                return@withContext null
            }
            return@withContext try {
                val cfg = json.decodeFromString(SpeechServiceConfig.serializer(), text)
                log.info("Loaded speech config from NSUserDefaults: {}", cfg)
                cfg
            } catch (t: Throwable) {
                log.warn("Failed to decode speech config from NSUserDefaults", t)
                null
            }
        }
    }

    override suspend fun saveSpeechConfig(config: SpeechServiceConfig) = withContext(Dispatchers.Default) {
        val log = LoggerFactory.getLogger("IosConfigRepository")
        autoreleasepool {
            val text = json.encodeToString(SpeechServiceConfig.serializer(), config)
            defaults.setObject(text, "speech_config")
            defaults.synchronize()
            log.info("Saved speech config to NSUserDefaults: {}", config)
        }
    }
}
