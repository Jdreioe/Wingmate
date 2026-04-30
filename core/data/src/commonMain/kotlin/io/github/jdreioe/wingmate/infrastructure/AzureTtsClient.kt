package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.SpeechTextProcessor
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
    fun generateSsml(text: String, voice: Voice, dictionary: List<io.github.jdreioe.wingmate.domain.PronunciationEntry> = emptyList()): String {
        val params = resolveVoiceParams(voice)
        val normalized = SpeechTextProcessor.normalizeShorthandSsml(text)
        val processed = applyPronunciationDictionary(normalized, dictionary)
        val escaped = escapeForSsml(processed)
        val content = if (params.baseLang.isNotBlank() && params.baseLang != "en-US") {
            "<lang xml:lang=\"${params.baseLang}\">$escaped</lang>"
        } else {
            escaped
        }
        return buildSsmlDocument(params, content)
    }

    /**
     * Generate SSML by weaving <lang> tags through provided segments.
     */
    fun generateSsml(segments: List<SpeechSegment>, voice: Voice, dictionary: List<io.github.jdreioe.wingmate.domain.PronunciationEntry> = emptyList()): String {
        val params = resolveVoiceParams(voice)
        val content = buildString {
            segments.forEach { segment ->
                if (segment.text.isNotEmpty()) {
                    val normalized = SpeechTextProcessor.normalizeShorthandSsml(segment.text)
                    val processed = applyPronunciationDictionary(normalized, dictionary)
                    val escaped = escapeForSsml(processed)
                    val overrideLang = segment.languageTag?.takeIf { it.isNotBlank() }
                    if (!overrideLang.isNullOrBlank() && overrideLang != params.baseLang) {
                        append("<lang xml:lang=\"$overrideLang\">$escaped</lang>")
                    } else {
                        append(escaped)
                    }
                }
                if (segment.pauseDurationMs > 0) {
                    append("<break time=\"${segment.pauseDurationMs}ms\"/>")
                }
            }
        }
        return buildSsmlDocument(params, content)
    }

    /**
     * Replaces words with <phoneme> tags based on the provided dictionary.
     */
    private fun applyPronunciationDictionary(text: String, dictionary: List<io.github.jdreioe.wingmate.domain.PronunciationEntry>): String {
        if (dictionary.isEmpty()) return text
        var result = text
        // Sort by length descending to avoid partial matches (e.g. "car" matching "carpet")
        val sortedDict = dictionary.sortedByDescending { it.word.length }
        
        for (entry in sortedDict) {
            if (entry.word.isBlank() || entry.phoneme.isBlank()) continue
            
            // Regex to match whole word, case insensitive
            val regex = Regex("\\b${Regex.escape(entry.word)}\\b", RegexOption.IGNORE_CASE)
            result = result.replace(regex) { match ->
                "<phoneme alphabet=\"${entry.alphabet}\" ph=\"${entry.phoneme}\">${match.value}</phoneme>"
            }
        }
        return result
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
        // Find segments that are tags vs plain text
        val tagRegex = Regex("<[^>]+>")
        val matches = tagRegex.findAll(text)
        
        if (matches.none()) {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
        }
        
        val result = StringBuilder()
        var lastIndex = 0
        
        for (match in matches) {
            // 1. Escape the text BEFORE the tag
            val preText = text.substring(lastIndex, match.range.first)
            result.append(preText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"))
            
            // 2. Add the tag AS-IS (do not escape the code parts of it)
            result.append(match.value)
            
            lastIndex = match.range.last + 1
        }
        
        // 3. Escape the remaining text AFTER the last tag
        if (lastIndex < text.length) {
            val postText = text.substring(lastIndex)
            result.append(postText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"))
        }
        
        return result.toString()
    }

    private data class VoiceParams(
        val voiceName: String,
        val baseLang: String,
        val pitch: String,
        val rate: String
    )

    private fun resolveVoiceParams(voice: Voice): VoiceParams {
        val voiceName = voice.name ?: "en-US-JennyNeural"
        val baseLang = when {
            !voice.selectedLanguage.isNullOrBlank() -> voice.selectedLanguage!!
            !voice.primaryLanguage.isNullOrBlank() -> voice.primaryLanguage!!
            else -> "en-US"
        }
        val pitch = when {
            voice.pitchForSSML != null -> voice.pitchForSSML!!
            voice.pitch != null -> convertPitchToSSML(voice.pitch!!)
            else -> "medium"
        }
        val rate = when {
            voice.rateForSSML != null -> voice.rateForSSML!!
            voice.rate != null -> convertRateToSSML(voice.rate!!)
            else -> "medium"
        }
        logger.debug { "resolveVoiceParams: voiceName=${voice.name} baseLang=$baseLang pitch=$pitch rate=$rate" }
        return VoiceParams(voiceName, baseLang, pitch, rate)
    }

    private fun buildSsmlDocument(params: VoiceParams, content: String): String {
        val primaryWrapped = "<lang xml:lang=\"${params.baseLang}\">$content</lang>"
        val inner = buildString {
            append("<prosody pitch=\"${params.pitch}\">")
            append("<prosody rate=\"${params.rate}\">")
            append(primaryWrapped)
            append("</prosody>")
            append("</prosody>")
        }
                return """
                        <speak version="1.0" xml:lang="${params.baseLang}">
                            <voice xml:lang="${params.baseLang}" name="${params.voiceName}">
                                $inner
                            </voice>
                        </speak>
                """.trimIndent()
    }
    
    // ========================================================================
    // TOKEN-BASED AUTHENTICATION (Secure Backend)
    // ========================================================================
    
    /**
     * Synthesize speech using a bearer token instead of subscription key.
     * 
     * This is the secure method that should be used in production:
     * 1. Client calls TokenExchangeClient.getToken() to get a short-lived token
     * 2. This method uses that token to call Azure TTS directly
     * 3. No subscription key is ever stored on the client device
     * 
     * @param client Ktor HTTP client
     * @param ssml The SSML document to synthesize
     * @param token Bearer token from TokenExchangeClient
     * @param region Azure region (e.g., "eastus")
     * @param audioFormat Desired audio format
     */
    suspend fun synthesizeWithToken(
        client: HttpClient,
        ssml: String,
        token: String,
        region: String,
        audioFormat: AudioFormat = AudioFormat.MP3_24KHZ_160KBPS
    ): ByteArray {
        val url = "https://$region.tts.speech.microsoft.com/cognitiveservices/v1"
        
        logger.info { "Azure TTS (token auth) -> url=$url, format=${audioFormat.value}" }
        logger.debug { "SSML length=${ssml.length} chars" }
        
        try {
            val response: HttpResponse = client.post(url) {
                headers {
                    // Use Bearer token instead of subscription key
                    append(HttpHeaders.Authorization, "Bearer $token")
                    append(HttpHeaders.ContentType, "application/ssml+xml")
                    append("X-Microsoft-OutputFormat", audioFormat.value)
                    append(HttpHeaders.UserAgent, "WingmateKMP/2.0")
                    append(HttpHeaders.Accept, "audio/*")
                }
                setBody(ssml)
            }
            
            logger.info { "Azure TTS (token auth) response status=${response.status}" }
            
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
                    logger.error { "Azure TTS token expired or invalid" }
                    throw TokenExpiredException("Azure TTS token expired or invalid")
                }
                response.status.value == 429 -> {
                    logger.error { "Azure TTS rate limit exceeded" }
                    throw RuntimeException("Azure TTS rate limit exceeded. Please try again later.")
                }
                else -> {
                    val body = response.bodyAsText()
                    logger.error { "Azure TTS failed: ${response.status} - ${body.take(500)}" }
                    throw RuntimeException("Azure TTS failed: ${response.status}")
                }
            }
        } catch (e: TokenExpiredException) {
            throw e
        } catch (e: RuntimeException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Azure TTS network error" }
            throw RuntimeException("Azure TTS network error: ${e.message}", e)
        }
    }

    
    suspend fun getVoices(
        client: HttpClient, 
        config: SpeechServiceConfig
    ): List<Voice> {
        // The stored config.endpoint may be either a short region (e.g. "westus")
        // or a full host/URL. Support both forms:
        val baseUrl = when {
            config.endpoint.startsWith("http", ignoreCase = true) -> config.endpoint.trimEnd('/')
            config.endpoint.contains("tts.speech.microsoft.com", ignoreCase = true) || 
            config.endpoint.contains("cognitiveservices", ignoreCase = true) -> "https://${config.endpoint.trimEnd('/') }"
            else -> "https://${config.endpoint}.tts.speech.microsoft.com"
        }
        val url = "$baseUrl/cognitiveservices/voices/list"
        
        logger.info { "Fetching Azure voices from $url" }
        
        try {
            val response: HttpResponse = client.get(url) {
                headers {
                    append("Ocp-Apim-Subscription-Key", config.subscriptionKey)
                    append(HttpHeaders.UserAgent, "WingmateKMP/2.0")
                    append(HttpHeaders.Accept, "application/json")
                }
            }
            
            if (response.status.isSuccess()) {
                val azureVoices = response.body<List<AzureVoiceDto>>()
                logger.info { "Fetched ${azureVoices.size} voices from Azure" }
                return azureVoices.map { it.toDomain() }
            } else {
                val body = response.bodyAsText()
                logger.error { "Failed to fetch voices: ${response.status} - $body" }
                throw RuntimeException("Failed to fetch voices: ${response.status}")
            }
        } catch (e: Exception) {
            logger.error(e) { "Error fetching voices" }
            throw e
        }
    }
    
    @kotlinx.serialization.Serializable
    private data class AzureVoiceDto(
        val Name: String,
        val ShortName: String,
        val Gender: String,
        val Locale: String,
        val LocalName: String? = null,
        val DisplayName: String? = null
    ) {
        fun toDomain(): Voice {
            val display = if (LocalName != null && DisplayName != null) {
                "$LocalName ($DisplayName)" 
            } else {
                DisplayName ?: LocalName ?: ShortName
            }
            
            return Voice(
                name = ShortName,
                displayName = display,
                primaryLanguage = Locale,
                gender = Gender,
                // Assume 1.0 default pitch/rate
                pitch = 1.0, 
                rate = 1.0
            )
        }
    }
}

/**
 * Exception thrown when the Azure TTS token has expired.
 * The caller should invalidate the cached token and request a new one.
 */
class TokenExpiredException(message: String) : Exception(message)
