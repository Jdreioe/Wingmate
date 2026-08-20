package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.loggingClassName
import io.github.jdreioe.wingmate.domain.toGoogleVoiceCatalog
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class GoogleVoiceCatalog(
    private val configRepo: ConfigRepository,
    private val applicationHeaders: GoogleApiRequestHeaders = NoGoogleApiRequestHeaders,
) {
    private val client = HttpClient {
        followRedirects = false
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
    }

    suspend fun list(): List<Voice> = try {
        val config = configRepo.getGoogleSpeechConfig() ?: return emptyList()
        GoogleTtsClient.getVoices(client, config, applicationHeaders).toGoogleVoiceCatalog()
    } catch (error: Throwable) {
        OperationalLogger.warn(
            operation = "google_voice_catalog.load",
            outcome = "failed",
            exceptionClass = error.loggingClassName(),
        )
        emptyList()
    }
}
