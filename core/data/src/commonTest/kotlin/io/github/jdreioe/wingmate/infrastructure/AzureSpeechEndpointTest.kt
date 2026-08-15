package io.github.jdreioe.wingmate.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AzureSpeechEndpointTest {
    @Test
    fun publicRegionNormalizesToTheOfficialRegionalAuthority() {
        val endpoint = valid("NorthEurope")

        assertEquals("northeurope", endpoint.persistedValue)
        assertEquals("https://northeurope.tts.speech.microsoft.com", endpoint.baseUrl)
        assertEquals("https://northeurope.tts.speech.microsoft.com/cognitiveservices/v1", endpoint.synthesisUrl)
        assertEquals(
            "https://northeurope.tts.speech.microsoft.com/cognitiveservices/voices/list",
            endpoint.voicesUrl,
        )
    }

    @Test
    fun sovereignRegionsUseTheirDocumentedCloudAuthorities() {
        assertEquals(
            "https://usgovvirginia.tts.speech.azure.us",
            valid("USGovVirginia").baseUrl,
        )
        assertEquals(
            "https://chinanorth3.tts.speech.azure.cn",
            valid("ChinaNorth3").baseUrl,
        )
    }

    @Test
    fun officialRegionalHostsAreAcceptedWithOrWithoutScheme() {
        assertEquals(
            "https://westus2.tts.speech.microsoft.com",
            valid("https://WestUS2.tts.speech.microsoft.com/").persistedValue,
        )
        assertEquals(
            "https://usgovarizona.tts.speech.azure.us",
            valid("usgovarizona.tts.speech.azure.us").persistedValue,
        )
    }

    @Test
    fun portalRegionalEndpointsNormalizeToTextToSpeechAuthorities() {
        assertEquals(
            "https://northeurope.tts.speech.microsoft.com",
            valid("https://northeurope.api.cognitive.microsoft.com/").baseUrl,
        )
        assertEquals(
            "https://usgovvirginia.tts.speech.azure.us",
            valid("https://usgovvirginia.api.cognitive.microsoft.us").baseUrl,
        )
        assertEquals(
            "https://chinanorth3.tts.speech.azure.cn",
            valid("https://chinanorth3.api.cognitive.azure.cn").baseUrl,
        )
    }

    @Test
    fun resourceEndpointUsesTheResourceSpecificVoiceListPath() {
        val endpoint = valid("https://my-speech-resource.cognitiveservices.azure.com")

        assertEquals(
            "https://my-speech-resource.cognitiveservices.azure.com/cognitiveservices/v1",
            endpoint.synthesisUrl,
        )
        assertEquals(
            "https://my-speech-resource.cognitiveservices.azure.com/tts/cognitiveservices/voices/list",
            endpoint.voicesUrl,
        )
    }

    @Test
    fun officialResourceHostsForSovereignCloudsAreAccepted() {
        assertEquals(
            "https://resource-name.cognitiveservices.azure.us",
            valid("https://resource-name.cognitiveservices.azure.us").baseUrl,
        )
        assertEquals(
            "https://resource-name.cognitiveservices.azure.cn",
            valid("https://resource-name.cognitiveservices.azure.cn").baseUrl,
        )
    }

    @Test
    fun arbitraryOrDeceptiveAuthoritiesAreRejected() {
        listOf(
            "https://attacker.example",
            "https://northeurope.tts.speech.microsoft.com.attacker.example",
            "https://tts.speech.microsoft.com",
            "https://cognitiveservices.azure.com",
            "https://resource.cognitiveservices.azure.com@attacker.example",
            "https://attacker.example/tts.speech.microsoft.com",
        ).forEach { value ->
            assertIs<AzureSpeechEndpointResult.Invalid>(AzureSpeechEndpoint.parse(value), value)
        }
    }

    @Test
    fun insecureOrUnexpectedUrlComponentsAreRejected() {
        listOf(
            "http://northeurope.tts.speech.microsoft.com",
            "https://northeurope.tts.speech.microsoft.com:443",
            "https://northeurope.tts.speech.microsoft.com:444",
            "https://northeurope.tts.speech.microsoft.com/cognitiveservices/v1",
            "https://northeurope.tts.speech.microsoft.com?target=attacker",
            "https://northeurope.tts.speech.microsoft.com#fragment",
            "https://northeurope.tts.speech.microsoft.com/%2e%2e",
            "https://north europe.tts.speech.microsoft.com",
            "javascript:alert(1)",
            "",
        ).forEach { value ->
            assertIs<AzureSpeechEndpointResult.Invalid>(AzureSpeechEndpoint.parse(value), value)
        }
    }

    private fun valid(value: String): AzureSpeechEndpoint =
        assertIs<AzureSpeechEndpointResult.Valid>(AzureSpeechEndpoint.parse(value)).endpoint
}
