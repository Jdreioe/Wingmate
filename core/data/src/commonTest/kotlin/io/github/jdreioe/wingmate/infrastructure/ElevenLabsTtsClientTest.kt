package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ElevenLabsTtsClientTest {
    private val config = ElevenLabsSpeechConfig(" private-key ")
    private val voice = Voice(name = "elevenlabs|clone123", providerVoiceName = "clone123", provider = VoiceProvider.ELEVENLABS, selectedLanguage = "da-DK")

    @Test
    fun accountCatalogFollowsPaginationAndPreservesPersonalVoiceIds() = runBlocking {
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            assertEquals("api.elevenlabs.io", request.url.host)
            assertEquals("/v2/voices", request.url.encodedPath)
            assertEquals("private-key", request.headers["xi-api-key"])
            calls++
            val result = if (calls == 1) {
                assertNull(request.url.parameters["next_page_token"])
                """{"voices":[{"voice_id":"clone123","name":"My voice","labels":{"gender":"male"}}],"has_more":true,"next_page_token":"next"}"""
            } else {
                assertEquals("next", request.url.parameters["next_page_token"])
                """{"voices":[{"voice_id":"second","name":"Second"}],"has_more":false}"""
            }
            respond(result, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { followRedirects = false }
        client.use {
            val voices = ElevenLabsTtsClient.getVoices(it, config)
            assertEquals(2, voices.size)
            assertEquals("clone123", voices.first().providerVoiceName)
            assertEquals("My voice", voices.first().displayName)
            assertContains(voices.first().supportedLanguages.orEmpty(), "da-DK")
            assertEquals(voices, voices.forTtsEngine(TtsEngine.ELEVENLABS))
            assertTrue(voices.forTtsEngine(TtsEngine.GOOGLE_CLOUD).isEmpty())
        }
    }

    @Test
    fun synthesisUsesSelectedVoiceLanguageAndBoundedSpeedAndReturnsMp3() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            assertEquals("/v1/text-to-speech/clone123", request.url.encodedPath)
            assertEquals("mp3_44100_128", request.url.parameters["output_format"])
            assertEquals("private-key", request.headers["xi-api-key"])
            assertFalse(request.url.toString().contains("private-key"))
            val body = request.body.toByteArray().decodeToString()
            assertContains(body, "\"model_id\":\"eleven_v4_turbo\"")
            assertContains(body, "\"language_code\":\"da\"")
            assertContains(body, "\"speed\":1.2")
            assertContains(body, "Hello")
            respond(byteArrayOf(1, 2, 3), headers = headersOf(HttpHeaders.ContentType, "audio/mpeg"))
        }) { followRedirects = false }
        client.use { assertContentEquals(byteArrayOf(1, 2, 3), ElevenLabsTtsClient.synthesize(it, "Hello", voice, config, 2.0)) }
    }

    @Test
    fun audioTagsReachElevenLabsVerbatimAndPausesBecomeTags() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            val body = request.body.toByteArray().decodeToString()
            assertContains(body, "[whispers] Hello [pause] [laughs] there [long pause]")
            assertFalse(body.contains("<break"))
            respond(byteArrayOf(1), headers = headersOf(HttpHeaders.ContentType, "audio/mpeg"))
        }) { followRedirects = false }
        val segments = SpeechTextProcessor.processText("[whispers] Hello <1s> [laughs] there <2s>")
        client.use { ElevenLabsTtsClient.synthesizeSegments(it, segments, voice, config) }
        Unit
    }

    @Test
    fun refusedRequestsNeverExposeProviderBody() = runBlocking {
        val client = HttpClient(MockEngine { respond("private-key private phrase", HttpStatusCode.Unauthorized) }) { followRedirects = false }
        client.use {
            val failure = assertFailsWith<ElevenLabsRequestException> { ElevenLabsTtsClient.synthesize(it, "private phrase", voice, config) }
            assertTrue(failure.rejectedCredential)
            assertFalse(failure.message.orEmpty().contains("private"))
            assertFalse(config.toString().contains("private-key"))
        }
    }

    @Test
    fun redirectsAndForeignProviderVoicesAreRejectedBeforeSendingCredentials() = runBlocking {
        val client = HttpClient(MockEngine { error("Must not send credentials") })
        client.use { assertFailsWith<IllegalStateException> { ElevenLabsTtsClient.getVoices(it, config) } }
        val safeClient = HttpClient(MockEngine { error("Must not send a foreign voice") }) { followRedirects = false }
        safeClient.use { assertFailsWith<IllegalArgumentException> { ElevenLabsTtsClient.synthesize(it, "Hello", Voice(provider = VoiceProvider.AZURE), config) } }
        Unit
    }
}
