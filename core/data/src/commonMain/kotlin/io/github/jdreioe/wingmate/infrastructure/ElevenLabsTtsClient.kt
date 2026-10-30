package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Account voices and low-latency multilingual speech, always sent to a fixed authority.
 * Eleven v4 Turbo understands audio tags such as `[whispers]` or `[laughs]` in the text.
 */
object ElevenLabsTtsClient {
    private const val BASE_URL = "https://api.elevenlabs.io"
    const val MODEL_ID = "eleven_v4_turbo"
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    private fun key(client: HttpClient, config: ElevenLabsSpeechConfig): String {
        check(client.pluginOrNull(HttpRedirect) == null) { "ElevenLabs credential requests require redirects disabled" }
        return config.apiKey.trim().also { require(it.isNotEmpty()) { "ElevenLabs API key is required" } }
    }

    suspend fun getVoices(client: HttpClient, config: ElevenLabsSpeechConfig): List<Voice> {
        val apiKey = key(client, config)
        val voices = mutableListOf<Voice>()
        val seenTokens = mutableSetOf<String>()
        var token: String? = null
        do {
            val response = client.get("$BASE_URL/v2/voices") {
                header("xi-api-key", apiKey)
                parameter("page_size", 100)
                parameter("include_total_count", false)
                token?.let { parameter("next_page_token", it) }
            }
            if (!response.status.isSuccess()) throw ElevenLabsRequestException(response.status.value)
            val page = json.decodeFromString<ElevenLabsVoicesResponse>(response.bodyAsText())
            voices += page.voices.filter { it.voiceId.isNotBlank() }.map {
                Voice(
                    name = "elevenlabs|${it.voiceId}",
                    providerVoiceName = it.voiceId,
                    displayName = it.name,
                    provider = VoiceProvider.ELEVENLABS,
                    supportedLanguages = languageLocales,
                    primaryLanguage = "en-US",
                    gender = it.labels["gender"]?.uppercase(),
                    rate = 1.0,
                )
            }
            token = if (page.hasMore) page.nextPageToken else null
            check(!page.hasMore || (!token.isNullOrBlank() && seenTokens.add(token))) { "Invalid ElevenLabs catalog pagination" }
        } while (token != null)
        return voices.distinctBy { it.name }
    }

    suspend fun synthesizeSegments(
        client: HttpClient,
        segments: List<SpeechSegment>,
        voice: Voice,
        config: ElevenLabsSpeechConfig,
        rate: Double? = null,
    ): ByteArray =
        synthesize(client, segments.joinToString(" ") { segment -> segment.text + pauseTag(segment.pauseDurationMs) }, voice, config, rate)

    // v4 ignores SSML breaks, so explicit pauses become audio tags of approximate length.
    private fun pauseTag(durationMs: Long): String = when {
        durationMs <= 0 -> ""
        durationMs < 1500 -> " [pause]"
        else -> " [long pause]"
    }

    suspend fun synthesize(
        client: HttpClient,
        text: String,
        voice: Voice,
        config: ElevenLabsSpeechConfig,
        rate: Double? = null,
    ): ByteArray {
        val apiKey = key(client, config)
        require(voice.resolvedProvider() == VoiceProvider.ELEVENLABS) { "Select an ElevenLabs voice" }
        val id = voice.providerVoiceName.orEmpty()
        require(id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '_' || it == '-' }) { "Invalid ElevenLabs voice ID" }
        val language = (voice.selectedLanguage.ifBlank { voice.primaryLanguage.orEmpty() }).substringBefore('-').takeIf { it in languages }
        val response = client.post("$BASE_URL/v1/text-to-speech/$id") {
            parameter("output_format", "mp3_44100_128")
            header("xi-api-key", apiKey)
            header(HttpHeaders.Accept, "audio/mpeg")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ElevenLabsSynthesisRequest(
                text = text,
                languageCode = language,
                voiceSettings = ElevenLabsVoiceSettings((rate ?: voice.rate ?: 1.0).coerceIn(0.7, 1.2)),
            )))
        }
        if (!response.status.isSuccess()) throw ElevenLabsRequestException(response.status.value)
        return response.body<ByteArray>().also { require(it.isNotEmpty()) { "ElevenLabs returned empty audio" } }
    }

    private val languageLocales = listOf(
        "en-US", "en-GB", "ja-JP", "zh-CN", "de-DE", "hi-IN", "fr-FR", "ko-KR",
        "pt-BR", "pt-PT", "it-IT", "es-ES", "es-MX", "id-ID", "nl-NL", "tr-TR",
        "fil-PH", "pl-PL", "sv-SE", "bg-BG", "ro-RO", "ar-SA", "cs-CZ", "el-GR",
        "fi-FI", "hr-HR", "ms-MY", "sk-SK", "da-DK", "ta-IN", "uk-UA", "ru-RU",
        "hu-HU", "no-NO", "vi-VN",
    )

    // Languages that apply to every voice, including personal voice clones.
    private val languages = listOf("en", "ja", "zh", "de", "hi", "fr", "ko", "pt", "it", "es", "id", "nl", "tr", "fil", "pl", "sv", "bg", "ro", "ar", "cs", "el", "fi", "hr", "ms", "sk", "da", "ta", "uk", "ru", "hu", "no", "vi")
}

/** Deliberately excludes provider response bodies, which can echo private text or credentials. */
class ElevenLabsRequestException(val statusCode: Int) : IllegalStateException(
    when (statusCode) {
        401, 403 -> "ElevenLabs refused access. Check the API key and its Voices and Text to Speech permissions."
        429 -> "ElevenLabs quota or request limit reached. Try again later."
        else -> "ElevenLabs request failed (HTTP $statusCode)."
    }
) {
    val rejectedCredential: Boolean get() = statusCode == 401 || statusCode == 403
}

@Serializable
private data class ElevenLabsVoicesResponse(
    val voices: List<ElevenLabsVoiceDto> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_page_token") val nextPageToken: String? = null,
)
@Serializable
private data class ElevenLabsVoiceDto(
    @SerialName("voice_id") val voiceId: String,
    val name: String,
    val labels: Map<String, String> = emptyMap(),
)
@Serializable
private data class ElevenLabsSynthesisRequest(
    val text: String,
    @SerialName("model_id") val modelId: String = ElevenLabsTtsClient.MODEL_ID,
    @SerialName("language_code") val languageCode: String? = null,
    @SerialName("voice_settings") val voiceSettings: ElevenLabsVoiceSettings,
)
@Serializable
private data class ElevenLabsVoiceSettings(val speed: Double)
