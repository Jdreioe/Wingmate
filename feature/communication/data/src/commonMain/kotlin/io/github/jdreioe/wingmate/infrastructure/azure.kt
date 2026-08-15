package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.loggingClassName
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

class AzureVoiceCatalog(private val configRepo: ConfigRepository) {
    private val client = HttpClient {
        followRedirects = false
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    suspend fun list(): List<Voice> {
        return try {
            val cfg = configRepo.getSpeechConfig() ?: run {
                OperationalLogger.debug("azure_voice_catalog.load", "config_missing")
                return emptyList()
            }
            if (cfg.endpoint.isBlank() || cfg.subscriptionKey.isBlank()) {
                OperationalLogger.warn("azure_voice_catalog.load", "config_incomplete")
                return emptyList()
            }
            AzureTtsClient.getVoices(client, cfg)
        } catch (t: Throwable) {
            OperationalLogger.warn(
                operation = "azure_voice_catalog.load",
                outcome = "failed",
                exceptionClass = t.loggingClassName(),
            )
            emptyList()
        }
    }
}
