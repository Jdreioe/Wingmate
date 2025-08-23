package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.cinterop.autoreleasepool
import platform.Foundation.NSUserDefaults
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

class IosConfigRepository : ConfigRepository {
    private val defaults by lazy { NSUserDefaults.standardUserDefaults() }
    private val json = Json { prettyPrint = true }

    override suspend fun getSpeechConfig(): SpeechServiceConfig? = withContext(Dispatchers.Default) {
        autoreleasepool {
            val text = defaults.stringForKey("speech_config")
            if (text == null) {
                logger.info { "No speech config found in NSUserDefaults" }
                return@withContext null
            }
            return@withContext try {
                val cfg = json.decodeFromString(SpeechServiceConfig.serializer(), text)
                logger.info { "Loaded speech config from NSUserDefaults: $cfg" }
                cfg
            } catch (t: Throwable) {
                logger.warn(t) { "Failed to decode speech config from NSUserDefaults" }
                null
            }
        }
    }

    override suspend fun saveSpeechConfig(config: SpeechServiceConfig) = withContext(Dispatchers.Default) {
        autoreleasepool {
            val text = json.encodeToString(SpeechServiceConfig.serializer(), config)
            defaults.setObject(text, "speech_config")
            defaults.synchronize()
            logger.info { "Saved speech config to NSUserDefaults: $config" }
        }
    }
}
