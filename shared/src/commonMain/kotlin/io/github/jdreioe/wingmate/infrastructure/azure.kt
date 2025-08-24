package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

class AzureVoiceCatalog(private val configRepo: ConfigRepository) {
    private val client = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    @Serializable
    private data class AzureVoice(
        @SerialName("DisplayName") val displayName: String? = null,
        @SerialName("ShortName") val shortName: String? = null,
        @SerialName("Gender") val gender: String? = null,
        @SerialName("Locale") val locale: String? = null,
        @SerialName("SecondaryLocaleList") val secondary: List<String>? = null,
    )

    suspend fun list(): List<Voice> {
        return try {
            val cfg = configRepo.getSpeechConfig() ?: run {
                logger.debug { "AzureVoiceCatalog: no config; returning empty list" }
                return emptyList()
            }
            val endpoint = cfg.endpoint.trim()
            val key = cfg.subscriptionKey.trim()
            if (endpoint.isEmpty() || key.isEmpty()) {
                logger.warn { "AzureVoiceCatalog: empty endpoint or key; returning empty list" }
                return emptyList()
            }
            val host = endpoint.removePrefix("https://").removePrefix("http://").removeSuffix("/")
            val url = "https://$host.tts.speech.microsoft.com/cognitiveservices/voices/list"

            val res: List<AzureVoice> = client.get(url) {
                header("Ocp-Apim-Subscription-Key", key)
                header(HttpHeaders.UserAgent, "Wingmate 1.0")
            }.body()

            res.map {
                Voice(
                    name = it.shortName,
                    displayName = it.displayName,
                    gender = it.gender,
                    primaryLanguage = it.locale,
                    supportedLanguages = it.secondary ?: emptyList(),
                    selectedLanguage = it.locale ?: "",
                )
            }
        } catch (t: Throwable) {
            logger.warn(t) { "AzureVoiceCatalog: failed to fetch voice list; returning empty" }
            emptyList()
        }
    }
}
