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
 * Enhanced shared Azure TTS client with improved audio quality and error handling.
 * Accepts SSML and returns audio bytes (mp3) from Azure.
 * Uses Ktor client from the calling platform (ensure ktor client engine configured on each platform).
 */
object AzureTtsClient {
    
    /**
     * Audio format options for Azure TTS
     */
    enum class AudioFormat(val value: String) {
        MP3_16KHZ_128KBPS("audio-16khz-128kbitrate-mono-mp3"),
        MP3_24KHZ_160KBPS("audio-24khz-160kbitrate-mono-mp3"),
        MP3_48KHZ_192KBPS("audio-48khz-192kbitrate-mono-mp3"),
        WAV_16KHZ_16BIT("riff-16khz-16bit-mono-pcm"),
        WAV_24KHZ_16BIT("riff-24khz-16bit-mono-pcm")
    }
    
    suspend fun synthesize(
        client: HttpClient, 
        ssml: String, 
        config: SpeechServiceConfig,
        audioFormat: AudioFormat = AudioFormat.MP3_24KHZ_160KBPS
    ): ByteArray {
        // The stored config.endpoint may be either a short region (e.g. "westus")
        // or a full host/URL. Support both forms:
        val baseUrl = when {
            config.endpoint.startsWith("http", ignoreCase = true) -> config.endpoint.trimEnd('/')
            config.endpoint.contains("tts.speech.microsoft.com", ignoreCase = true) || 
            config.endpoint.contains("cognitiveservices", ignoreCase = true) -> "https://${config.endpoint.trimEnd('/') }"
            else -> "https://${config.endpoint}.tts.speech.microsoft.com"
        }
        val url = "$baseUrl/cognitiveservices/v1"

        // Enhanced logging with request details
        logger.info { "Azure TTS request -> url=$url (endpoint=${config.endpoint}, format=${audioFormat.value})" }
        logger.debug { "SSML length=${ssml.length} chars, preview=${ssml.take(200).replace(Regex("[\r\n]+"), " ")}" }

        try {
            val response: HttpResponse = client.post(url) {
                headers {
                    // Important: do not log the subscription key. We must send it but not print it.
                    append("Ocp-Apim-Subscription-Key", config.subscriptionKey)
                    append(HttpHeaders.ContentType, "application/ssml+xml")
                    append("X-Microsoft-OutputFormat", audioFormat.value)
                    append(HttpHeaders.UserAgent, "WingmateKMP/2.0")
                    append(HttpHeaders.Accept, "audio/*")
                }
                setBody(ssml)
            }

            logger.info { "Azure TTS response status=${response.status}" }
            
            when {
                response.status.isSuccess() -> {
                    val bytes = response.body<ByteArray>()
                    logger.info { "Azure TTS returned ${bytes.size} bytes (${bytes.size / 1024}KB)" }
                    
                    if (bytes.isEmpty()) {
                        throw RuntimeException("Azure TTS returned empty audio data")
                    }
                    
                    return bytes
                }
                response.status.value == 401 -> {
                    val body = response.bodyAsText()
                    logger.error { "Azure TTS authentication failed: ${response.status}" }
                    throw RuntimeException("Azure TTS authentication failed. Please check your subscription key.")
                }
                response.status.value == 429 -> {
                    val body = response.bodyAsText()
                    logger.error { "Azure TTS rate limit exceeded: ${response.status}" }
                    throw RuntimeException("Azure TTS rate limit exceeded. Please try again later.")
                }
                response.status.value in 400..499 -> {
                    val body = response.bodyAsText()
                    logger.error { "Azure TTS client error: ${response.status} - ${body.take(500)}" }
                    throw RuntimeException("Azure TTS request error: ${response.status.description}")
                }
                response.status.value in 500..599 -> {
                    val body = response.bodyAsText()
                    logger.error { "Azure TTS server error: ${response.status} - ${body.take(500)}" }
                    throw RuntimeException("Azure TTS server error: ${response.status.description}")
                }
                else -> {
                    val body = response.bodyAsText()
                    logger.error { "Azure TTS failed: ${response.status} - ${body.take(500)}" }
                    throw RuntimeException("Azure TTS failed: ${response.status} - ${response.status.description}")
                }
            }
        } catch (e: Exception) {
            when (e) {
                is RuntimeException -> throw e
                else -> {
                    logger.error(e) { "Azure TTS network error" }
                    throw RuntimeException("Azure TTS network error: ${e.message}", e)
                }
            }
        }
    }
    
    /**
     * Backward compatibility method with default audio format
     */
    suspend fun synthesize(client: HttpClient, ssml: String, config: SpeechServiceConfig): ByteArray {
        return synthesize(client, ssml, config, AudioFormat.MP3_24KHZ_160KBPS)
    }

    /**
     * Enhanced SSML generation with better voice parameter support
     */
    /**
     * Enhanced SSML generation with better voice parameter support
     */
    fun generateSsml(text: String, voice: Voice): String {
        val voiceName = voice.name ?: "en-US-JennyNeural" // Azure expects the short name
        val lang = when {
            !voice.selectedLanguage.isNullOrBlank() -> voice.selectedLanguage!!
            !voice.primaryLanguage.isNullOrBlank() -> voice.primaryLanguage!!
            else -> "en-US"
        }
        
        // Enhanced parameter handling with better defaults and validation
        val pitchForSSML = when {
            voice.pitchForSSML != null -> voice.pitchForSSML!!
            voice.pitch != null -> convertPitchToSSML(voice.pitch!!)
            else -> "medium"
        }
        
        val rateForSSML = when {
            voice.rateForSSML != null -> voice.rateForSSML!!
            voice.rate != null -> convertRateToSSML(voice.rate!!)
            else -> "medium"
        }

        logger.debug { "generateSsml: voiceName=${voice.name} selectedLanguage=${voice.selectedLanguage} primaryLanguage=${voice.primaryLanguage} pitch=$pitchForSSML rate=$rateForSSML" }

        val escaped = escapeForSsml(text)
        
        // Enhanced SSML with better structure and error handling
        val inner = buildString {
            append("<prosody pitch=\"$pitchForSSML\">")
            append("<prosody rate=\"$rateForSSML\">")
            
            // Add volume control if needed (future enhancement)
            // append("<prosody volume=\"medium\">")
            
            if (lang.isNotBlank() && lang != "en-US") {
                append("<lang xml:lang=\"$lang\">")
                append(escaped)
                append("</lang>")
            } else {
                append(escaped)
            }
            
            // Close prosody tags in reverse order
            append("</prosody>") // rate
            append("</prosody>") // pitch
        }

        return """
            <speak version="1.0" xml:lang="$lang">
              <voice name="$voiceName">
                $inner
              </voice>
            </speak>
        """.trimIndent()
    }
    
    /**
     * Convert numeric pitch (0.0-2.0) to SSML pitch string
     */
    private fun convertPitchToSSML(pitch: Double): String {
        return when {
            pitch < 0.7 -> "x-low"
            pitch < 0.8 -> "low" 
            pitch < 1.2 -> "medium"
            pitch < 1.5 -> "high"
            else -> "x-high"
        }
    }
    
    /**
     * Convert numeric rate (0.0-2.0) to SSML rate string
     */
    private fun convertRateToSSML(rate: Double): String {
        return when {
            rate < 0.7 -> "x-slow"
            rate < 0.8 -> "slow"
            rate < 1.2 -> "medium" 
            rate < 1.5 -> "fast"
            else -> "x-fast"
        }
    }

    /**
     * Enhanced SSML text escaping with comprehensive character support
     */
    private fun escapeForSsml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
