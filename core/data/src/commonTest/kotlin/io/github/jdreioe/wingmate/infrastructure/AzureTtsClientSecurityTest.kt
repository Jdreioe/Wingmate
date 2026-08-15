package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AzureTtsClientSecurityTest {
    @Test
    fun invalidEndpointIsRejectedBeforeAnyRequest() = runBlocking {
        var requests = 0
        val client = HttpClient(MockEngine {
            requests++
            respond(ByteArray(1) { 1 })
        }) {
            followRedirects = false
        }

        assertFailsWith<IllegalArgumentException> {
            AzureTtsClient.synthesize(
                client = client,
                ssml = "<speak>test</speak>",
                config = SpeechServiceConfig("https://attacker.example", "azure-secret"),
            )
        }

        assertEquals(0, requests)
        client.close()
    }

    @Test
    fun redirectEnabledClientIsRejectedBeforeAnyRequest() = runBlocking {
        var requests = 0
        val client = HttpClient(MockEngine {
            requests++
            respond(ByteArray(1) { 1 })
        })

        assertFailsWith<IllegalStateException> {
            AzureTtsClient.synthesize(
                client = client,
                ssml = "<speak>test</speak>",
                config = SpeechServiceConfig("northeurope", "azure-secret"),
            )
        }

        assertEquals(0, requests)
        client.close()
    }

    @Test
    fun synthesisSendsCredentialOnlyToValidatedRegionalUrl() = runBlocking {
        var requests = 0
        val client = HttpClient(MockEngine { request ->
            requests++
            assertEquals(
                "https://northeurope.tts.speech.microsoft.com/cognitiveservices/v1",
                request.url.toString(),
            )
            assertEquals("azure-secret", request.headers[AZURE_SUBSCRIPTION_KEY_HEADER])
            respond(
                content = ByteArray(1) { 1 },
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "audio/mpeg"),
            )
        }) {
            followRedirects = false
        }

        AzureTtsClient.synthesize(
            client = client,
            ssml = "<speak>test</speak>",
            config = SpeechServiceConfig("NorthEurope", "azure-secret"),
        )

        assertEquals(1, requests)
        client.close()
    }

    @Test
    fun resourceEndpointUsesItsDocumentedVoiceListPath() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            assertEquals(
                "https://my-resource.cognitiveservices.azure.com/tts/cognitiveservices/voices/list",
                request.url.toString(),
            )
            assertEquals("azure-secret", request.headers[AZURE_SUBSCRIPTION_KEY_HEADER])
            respond(
                content = "[]",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) {
            followRedirects = false
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        assertEquals(
            emptyList(),
            AzureTtsClient.getVoices(
                client,
                SpeechServiceConfig(
                    "https://my-resource.cognitiveservices.azure.com",
                    "azure-secret",
                ),
            ),
        )
        client.close()
    }

    @Test
    fun redirectResponseIsNotFollowed() = runBlocking {
        var requests = 0
        val client = HttpClient(MockEngine {
            requests++
            respond(
                content = "redirect",
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "https://attacker.example/collect"),
            )
        }) {
            followRedirects = false
            install(ContentNegotiation) { json() }
        }

        assertFailsWith<RuntimeException> {
            AzureTtsClient.getVoices(
                client,
                SpeechServiceConfig("northeurope", "azure-secret"),
            )
        }

        assertEquals(1, requests)
        client.close()
    }

    private companion object {
        const val AZURE_SUBSCRIPTION_KEY_HEADER = "Ocp-Apim-Subscription-Key"
    }
}
