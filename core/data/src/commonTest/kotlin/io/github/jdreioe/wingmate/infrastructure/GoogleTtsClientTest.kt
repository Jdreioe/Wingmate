package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.GoogleSpeechConfig
import io.github.jdreioe.wingmate.domain.GoogleVoiceModel
import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.VoiceProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class GoogleTtsClientTest {
    @Test
    fun synthesisUsesFixedAuthorityHeaderAuthenticationAndBoundedSettings() = runBlocking {
        val client = testClient { request ->
            assertEquals(GoogleTtsClient.SYNTHESIS_URL, request.url.toString())
            assertEquals("google-secret", request.headers[GoogleTtsClient.API_KEY_HEADER])
            assertEquals("io.github.wingmate", request.headers["X-Ios-Bundle-Identifier"])
            assertFalse(request.url.toString().contains("google-secret"))
            val body = request.body.toByteArray().decodeToString()
            assertContains(body, "\"name\":\"da-DK-Neural2-D\"")
            assertContains(body, "\"languageCode\":\"da-DK\"")
            assertContains(body, "\"speakingRate\":2.0")
            assertContains(body, "\"pitch\":-12.0")
            respondJson("""{"audioContent":"AQID"}""")
        }

        val audio = GoogleTtsClient.synthesize(
            client = client,
            text = "private phrase",
            voice = Voice(name = "da-DK-Neural2-D", selectedLanguage = "da-DK"),
            config = GoogleSpeechConfig(" google-secret "),
            pitch = 0.0,
            rate = 4.0,
            applicationHeaders = GoogleApiRequestHeaders {
                mapOf("X-Ios-Bundle-Identifier" to "io.github.wingmate")
            },
        )

        assertContentEquals(byteArrayOf(1, 2, 3), audio)
        client.close()
    }

    @Test
    fun groupedLegacyVoiceReconstructsTheNameForItsSelectedLocale() = runBlocking {
        val client = testClient { request ->
            val body = request.body.toByteArray().decodeToString()
            assertContains(body, "\"name\":\"da-DK-Wavenet-F\"")
            respondJson("""{"audioContent":"AQ=="}""")
        }

        GoogleTtsClient.synthesize(
            client,
            "Hej",
            Voice(
                name = "google|WAVENET|F",
                selectedLanguage = "da-DK",
                provider = VoiceProvider.GOOGLE,
                googleModel = GoogleVoiceModel.WAVENET,
                providerVoiceName = "F",
            ),
            GoogleSpeechConfig("key"),
        )
        client.close()
    }

    @Test
    fun segmentSynthesisEscapesTextAndPreservesLanguageAndPauses() = runBlocking {
        val client = testClient { request ->
            val body = request.body.toByteArray().decodeToString()
            assertContains(body, "&lt;private&gt; &amp; safe")
            assertContains(body, "<lang xml:lang=\\\"da-DK\\\">")
            assertContains(body, "<break time=\\\"250ms\\\"/>")
            respondJson("""{"audioContent":"AQ=="}""")
        }

        GoogleTtsClient.synthesizeSegments(
            client,
            listOf(SpeechSegment("<private> & safe", 250, "da-DK")),
            Voice(name = "da-DK-Standard-A", primaryLanguage = "da-DK"),
            GoogleSpeechConfig("key"),
        )
        client.close()
    }

    @Test
    fun geminiSynthesisSendsModelAndSpeakerWithoutLegacySsmlControls() = runBlocking {
        val client = testClient { request ->
            val body = request.body.toByteArray().decodeToString()
            assertContains(body, "\"text\":\"Hello world\"")
            assertContains(body, "\"name\":\"Kore\"")
            assertContains(body, "\"modelName\":\"gemini-3.1-flash-tts-preview\"")
            assertFalse(body.contains("\"ssml\""))
            assertFalse(body.contains("\"speakingRate\""))
            assertFalse(body.contains("\"pitch\""))
            respondJson("""{"audioContent":"AQ=="}""")
        }

        GoogleTtsClient.synthesizeSegments(
            client,
            listOf(SpeechSegment("Hello", 250), SpeechSegment("world", 0)),
            Voice(
                name = "gemini-3.1-flash-tts-preview|en-US|Kore",
                primaryLanguage = "en-US",
                provider = VoiceProvider.GOOGLE,
                googleModel = GoogleVoiceModel.GEMINI_3_1_FLASH,
                providerVoiceName = "Kore",
            ),
            GoogleSpeechConfig("key"),
        )
        client.close()
    }

    @Test
    fun voiceCatalogMapsLanguagesAndGender() = runBlocking {
        val client = testClient { request ->
            assertEquals(GoogleTtsClient.VOICES_URL, request.url.toString())
            respondJson(
                """{"voices":[{"languageCodes":["en-US","en-CA"],"name":"en-US-Neural2-A","ssmlGender":"FEMALE","naturalSampleRateHertz":24000}]}""",
            )
        }

        val voice = GoogleTtsClient.getVoices(client, GoogleSpeechConfig("key")).single()
        assertEquals("en-US-Neural2-A", voice.name)
        assertEquals(listOf("en-US", "en-CA"), voice.supportedLanguages)
        assertEquals("FEMALE", voice.gender)
        assertEquals(VoiceProvider.GOOGLE, voice.provider)
        assertEquals(GoogleVoiceModel.NEURAL2, voice.googleModel)
        client.close()
    }

    @Test
    fun redirectEnabledClientIsRejectedBeforeCredentialRequest() = runBlocking {
        var requests = 0
        val client = HttpClient(MockEngine {
            requests++
            respondJson("""{"audioContent":"AQ=="}""")
        }) {
            install(ContentNegotiation) { json() }
        }

        assertFailsWith<IllegalStateException> {
            GoogleTtsClient.synthesize(
                client,
                "hello",
                Voice(primaryLanguage = "en-US"),
                GoogleSpeechConfig("key"),
            )
        }
        assertEquals(0, requests)
        client.close()
    }

    private fun testClient(
        handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ) =
        HttpClient(MockEngine(handler)) {
            followRedirects = false
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; explicitNulls = false })
            }
        }

    private fun MockRequestHandleScope.respondJson(value: String) = respond(
        content = value,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )
}
