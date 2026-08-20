package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.Base64Decoder
import io.github.jdreioe.wingmate.domain.GoogleSpeechConfig
import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.Voice
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Fixed-authority Google Cloud Text-to-Speech v1 client used by every Wingmate client. */
object GoogleTtsClient {
    const val SYNTHESIS_URL = "https://texttospeech.googleapis.com/v1/text:synthesize"
    const val VOICES_URL = "https://texttospeech.googleapis.com/v1/voices"
    const val API_KEY_HEADER = "x-goog-api-key"
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    enum class AudioEncoding { MP3, LINEAR16 }

    suspend fun synthesize(
        client: HttpClient,
        text: String,
        voice: Voice,
        config: GoogleSpeechConfig,
        pitch: Double? = null,
        rate: Double? = null,
        audioEncoding: AudioEncoding = AudioEncoding.MP3,
        applicationHeaders: GoogleApiRequestHeaders = NoGoogleApiRequestHeaders,
    ): ByteArray = synthesizeInput(
        client = client,
        input = GoogleSynthesisInput(text = text),
        voice = voice,
        config = config,
        applicationHeaders = applicationHeaders,
        pitch = pitch,
        rate = rate,
        audioEncoding = audioEncoding,
    )

    suspend fun synthesizeSegments(
        client: HttpClient,
        segments: List<SpeechSegment>,
        voice: Voice,
        config: GoogleSpeechConfig,
        pitch: Double? = null,
        rate: Double? = null,
        audioEncoding: AudioEncoding = AudioEncoding.MP3,
        applicationHeaders: GoogleApiRequestHeaders = NoGoogleApiRequestHeaders,
    ): ByteArray = synthesizeInput(
        client = client,
        input = GoogleSynthesisInput(ssml = segments.toGoogleSsml()),
        voice = voice,
        config = config,
        applicationHeaders = applicationHeaders,
        pitch = pitch,
        rate = rate,
        audioEncoding = audioEncoding,
    )

    suspend fun getVoices(
        client: HttpClient,
        config: GoogleSpeechConfig,
        applicationHeaders: GoogleApiRequestHeaders = NoGoogleApiRequestHeaders,
    ): List<Voice> {
        val key = requireApiKey(config)
        requireCredentialSafeClient(client)
        val response = client.get(VOICES_URL) {
            header(API_KEY_HEADER, key)
            applicationHeaders.values().forEach { (name, value) -> header(name, value) }
            header(HttpHeaders.Accept, ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) {
            OperationalLogger.warn(
                "google_voice_catalog.fetch",
                "failed",
                statusCode = response.status.value,
            )
            throw googleFailure("load voices", response.status.value)
        }
        return json.decodeFromString<GoogleVoicesResponse>(response.bodyAsText()).voices.map { it.toDomain() }
    }

    private suspend fun synthesizeInput(
        client: HttpClient,
        input: GoogleSynthesisInput,
        voice: Voice,
        config: GoogleSpeechConfig,
        applicationHeaders: GoogleApiRequestHeaders,
        pitch: Double?,
        rate: Double?,
        audioEncoding: AudioEncoding,
    ): ByteArray {
        val key = requireApiKey(config)
        requireCredentialSafeClient(client)
        val language = voice.selectedLanguage.takeIf(String::isNotBlank)
            ?: voice.primaryLanguage?.takeIf(String::isNotBlank)
            ?: "en-US"
        val name = voice.name?.takeIf {
            it.isNotBlank() && it.startsWith("$language-") && it.count { character -> character == '-' } >= 3
        }
        val request = GoogleSynthesisRequest(
            input = input,
            voice = GoogleVoiceSelection(languageCode = language, name = name),
            audioConfig = GoogleAudioConfig(
                audioEncoding = audioEncoding.name,
                speakingRate = (rate ?: voice.rate ?: 1.0).coerceIn(0.25, 2.0),
                // Wingmate stores pitch as a 0..2 multiplier centered at 1. Convert to semitones.
                pitch = (((pitch ?: voice.pitch ?: 1.0) - 1.0) * 12.0).coerceIn(-20.0, 20.0),
            ),
        )
        val response: HttpResponse = client.post(SYNTHESIS_URL) {
            header(API_KEY_HEADER, key)
            applicationHeaders.values().forEach { (name, value) -> header(name, value) }
            header(HttpHeaders.Accept, ContentType.Application.Json)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(json.encodeToString(request))
        }
        if (!response.status.isSuccess()) {
            OperationalLogger.warn(
                "google_tts.synthesize",
                "failed",
                statusCode = response.status.value,
            )
            throw googleFailure("synthesize speech", response.status.value)
        }
        val audio = Base64Decoder.decode(
            json.decodeFromString<GoogleSynthesisResponse>(response.bodyAsText()).audioContent,
        )
        require(audio.isNotEmpty()) { "Google Cloud Text-to-Speech returned empty audio" }
        return audio
    }

    private fun requireApiKey(config: GoogleSpeechConfig): String =
        config.apiKey.trim().also { require(it.isNotEmpty()) { "Google Cloud API key is required" } }

    private fun requireCredentialSafeClient(client: HttpClient) {
        check(client.pluginOrNull(HttpRedirect) == null) {
            "Google credential requests require an HTTP client with redirects disabled"
        }
    }

    private fun googleFailure(operation: String, status: Int): IllegalStateException =
        IllegalStateException(
            when (status) {
                400, 401, 403 -> "Google Cloud could not $operation. Check the API key, project, and Text-to-Speech API access."
                429 -> "Google Cloud Text-to-Speech quota was exceeded. Try again later."
                else -> "Google Cloud could not $operation (HTTP $status)."
            },
        )
}

/** Platform identity headers required by Google application-restricted API keys. */
fun interface GoogleApiRequestHeaders {
    fun values(): Map<String, String>
}

object NoGoogleApiRequestHeaders : GoogleApiRequestHeaders {
    override fun values(): Map<String, String> = emptyMap()
}

@Serializable
private data class GoogleSynthesisRequest(
    val input: GoogleSynthesisInput,
    val voice: GoogleVoiceSelection,
    val audioConfig: GoogleAudioConfig,
)

@Serializable
private data class GoogleSynthesisInput(val text: String? = null, val ssml: String? = null)

@Serializable
private data class GoogleVoiceSelection(val languageCode: String, val name: String? = null)

@Serializable
private data class GoogleAudioConfig(
    val audioEncoding: String,
    val speakingRate: Double,
    val pitch: Double,
)

@Serializable
private data class GoogleSynthesisResponse(val audioContent: String)

@Serializable
private data class GoogleVoicesResponse(val voices: List<GoogleVoiceDto> = emptyList())

@Serializable
private data class GoogleVoiceDto(
    val languageCodes: List<String> = emptyList(),
    val name: String,
    @SerialName("ssmlGender") val gender: String = "SSML_VOICE_GENDER_UNSPECIFIED",
) {
    fun toDomain(): Voice = Voice(
        name = name,
        displayName = name,
        supportedLanguages = languageCodes,
        primaryLanguage = languageCodes.firstOrNull(),
        selectedLanguage = languageCodes.firstOrNull().orEmpty(),
        gender = gender.removePrefix("SSML_VOICE_GENDER_"),
        pitch = 1.0,
        rate = 1.0,
    )
}

private fun List<SpeechSegment>.toGoogleSsml(): String = buildString {
    append("<speak>")
    this@toGoogleSsml.forEach { segment ->
        if (segment.text.isNotEmpty()) {
            val escaped = segment.text.escapeGoogleSsml()
            val language = segment.languageTag?.trim().orEmpty()
            if (language.isNotEmpty()) append("<lang xml:lang=\"").append(language.escapeGoogleSsml()).append("\">")
            append(escaped)
            if (language.isNotEmpty()) append("</lang>")
        }
        if (segment.pauseDurationMs > 0) {
            append("<break time=\"").append(segment.pauseDurationMs).append("ms\"/>")
        }
    }
    append("</speak>")
}

private fun String.escapeGoogleSsml(): String = buildString(length) {
    for (character in this@escapeGoogleSsml) {
        append(
            when (character) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&apos;"
                else -> character
            },
        )
    }
}
