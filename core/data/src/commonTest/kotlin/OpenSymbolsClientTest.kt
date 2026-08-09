import io.github.jdreioe.wingmate.infrastructure.OpenSymbolsClient
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenSymbolsClientTest {
    @Test
    fun normalizesRegionalLocalesForTheOpenSymbolsApi() {
        assertEquals("da", OpenSymbolsClient.normalizeLocale("da-DK"))
        assertEquals("en", OpenSymbolsClient.normalizeLocale("en_US"))
    }

    @Test
    fun fallsBackToEnglishForUnsupportedLocaleValues() {
        assertEquals("en", OpenSymbolsClient.normalizeLocale(""))
        assertEquals("en", OpenSymbolsClient.normalizeLocale("english"))
    }

    @Test
    fun acceptsOnlyHttpsOrLocalDevelopmentProxyUrls() {
        assertEquals("https://symbols.example", OpenSymbolsClient.normalizeProxyBaseUrl("https://symbols.example/"))
        assertEquals("http://localhost:8787", OpenSymbolsClient.normalizeProxyBaseUrl("http://localhost:8787"))
        assertEquals(null, OpenSymbolsClient.normalizeProxyBaseUrl("http://symbols.example"))
        assertEquals(null, OpenSymbolsClient.normalizeProxyBaseUrl("https://symbols.example?secret=nope"))
    }
}
