package io.github.jdreioe.wingmate.kde

import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

import io.github.jdreioe.wingmate.domain.VoiceRepository
import io.github.jdreioe.wingmate.infrastructure.AzureTtsClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class AzureConfigManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val configRepository: ConfigRepository by lazy {
        GlobalContext.get().get()
    }
    
    private val voiceRepository: VoiceRepository by lazy {
        GlobalContext.get().get()
    }
    
    suspend fun getConfig(): SpeechServiceConfig {
        return configRepository.getSpeechConfig() ?: SpeechServiceConfig()
    }
    
    fun updateConfig(endpoint: String, key: String) {
        scope.launch {
            val newConfig = SpeechServiceConfig(
                endpoint = endpoint,
                subscriptionKey = key
            )
            configRepository.saveSpeechConfig(newConfig)
        }
    }
    
    suspend fun fetchAndSaveVoices(config: SpeechServiceConfig) {
        val client = HttpClient {
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
                // Filter logic could go here (e.g. only selected language)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        } finally {
            client.close()
        }
    }
}
