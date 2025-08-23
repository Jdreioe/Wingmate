package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}
/**
 * Thin shared Azure TTS client. Accepts SSML and returns audio bytes (mp3) from Azure.
 * Uses Ktor client from the calling platform (ensure ktor client engine configured on each platform).
 */
object AzureTtsClient {
    suspend fun synthesize(client: HttpClient, ssml: String, config: SpeechServiceConfig): ByteArray {
        // The stored config.endpoint may be either a short region (e.g. "westus")
        // or a full host/URL. Support both forms:
        val baseUrl = when {
            config.endpoint.startsWith("http", ignoreCase = true) -> config.endpoint.trimEnd('/')
            config.endpoint.contains("tts.speech.microsoft.com", ignoreCase = true) || config.endpoint.contains("cognitiveservices", ignoreCase = true) -> "https://${config.endpoint.trimEnd('/') }"
            else -> "https://${config.endpoint}.tts.speech.microsoft.com"
        }
        val url = "$baseUrl/cognitiveservices/v1"

        // Debug logging (do not print subscription key)
        logger.info { "Azure TTS request -> url=$url (endpoint=${config.endpoint})" }
        logger.debug { "SSML length=${ssml.length} preview=${ssml.take(200).replace(Regex("\n"), " ")}" }

        val response: HttpResponse = client.post(url) {
            headers {
                // Important: do not log the subscription key. We must send it but not print it.
                append("Ocp-Apim-Subscription-Key", config.subscriptionKey)
                append(HttpHeaders.ContentType, "application/ssml+xml")
                append("X-Microsoft-OutputFormat", "audio-16khz-128kbitrate-mono-mp3")
                append(HttpHeaders.UserAgent, "WingmateKMP")
            }
            setBody(ssml)
        }

        logger.info { "Azure TTS response status=${response.status}" }
        if (response.status.isSuccess()) {
            val bytes = response.body<ByteArray>()
            logger.info { "Azure TTS returned ${bytes.size} bytes" }
            return bytes
        } else {
            val body = response.bodyAsText()
            logger.error { "Azure TTS failed: ${response.status} - ${body.take(2000)}" }
            throw RuntimeException("Azure TTS failed: ${response.status} - $body")
        }
    }

    fun generateSsml(text: String, voice: Voice): String {
    val selectedVoice = voice.name ?: "en-US-JennyNeural"
    // Debug the voice selection and language used
    val primaryLanguage = voice.primaryLanguage ?: "en-US"
    val pitchForSSML = voice.pitchForSSML ?: "medium"
    val rateForSSML = voice.rateForSSML ?: "medium"
    logger.debug { "generateSsml: voiceName=${voice.name} primaryLanguage=$primaryLanguage selectedLanguage=${voice.primaryLanguage} pitchForSSML=$pitchForSSML rateForSSML=$rateForSSML" }
    val langTag = "<lang xml:lang=\"$primaryLanguage\">"
    val rateTag = "<prosody rate=\"$rateForSSML\">"
    val endRate = "</prosody>"
    val pitchTag = "<prosody pitch=\"$pitchForSSML\">"
    val endPitch = "</prosody>"

        val endLang = "</lang>"
    logger.debug { "primaryLanguage.isEmpty()=${primaryLanguage.isEmpty()}" }
        return if (primaryLanguage.isEmpty()) {
            """
            <speak version="1.0" xml:lang="en-US">
              <voice name="$selectedVoice">
                $pitchTag
                $rateTag
                ${escapeForSsml(text)}
                $endPitch
                $endRate
              </voice>
            </speak>
            """.trimIndent()
        } else {
            """
            <speak version="1.0" xml:lang="$primaryLanguage">
              <voice name="$selectedVoice">
                $langTag
                ${escapeForSsml(text)}
                $endLang
              </voice>
            </speak>
            """.trimIndent()
        }
    }

    private fun escapeForSsml(text: String): String {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
