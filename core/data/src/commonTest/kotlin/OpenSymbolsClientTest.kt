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
}
