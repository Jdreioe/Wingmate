package io.github.jdreioe.wingmate.kde

import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import org.koin.core.context.GlobalContext

import io.github.jdreioe.wingmate.domain.VoiceRepository
import io.github.jdreioe.wingmate.infrastructure.AzureTtsClient
import io.github.jdreioe.wingmate.infrastructure.validatedForStorage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class AzureConfigManager {
    private val configRepository: ConfigRepository by lazy {
        GlobalContext.get().get()
    }
    
    private val voiceRepository: VoiceRepository by lazy {
        GlobalContext.get().get()
    }
    
    suspend fun getConfig(): SpeechServiceConfig {
        return configRepository.getSpeechConfig() ?: SpeechServiceConfig()
    }
    
    suspend fun updateConfig(endpoint: String, key: String): SpeechServiceConfig {
        val newConfig = SpeechServiceConfig(
            endpoint = endpoint,
            subscriptionKey = key
        ).validatedForStorage()
        configRepository.saveSpeechConfig(newConfig)
        return newConfig
    }
    
    suspend fun fetchAndSaveVoices(config: SpeechServiceConfig) {
        val client = HttpClient {
            followRedirects = false
            install(ContentNegotiation) {
                json(Json { 
                    ignoreUnknownKeys = true 
                })
            }
        }
        try {
            val voices = AzureTtsClient.getVoices(client, config)
            if (voices.isNotEmpty()) {
                voiceRepository.saveVoices(voices)
            }
        } finally {
            client.close()
        }
    }
}
