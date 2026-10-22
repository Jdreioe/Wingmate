import io.github.jdreioe.wingmate.application.NativeSymbol
import io.github.jdreioe.wingmate.application.SymbolSearchFacade
import io.github.jdreioe.wingmate.application.SymbolSearchStatus
import io.github.jdreioe.wingmate.infrastructure.OpenSymbolsClient
import io.github.jdreioe.wingmate.infrastructure.SymbolSearchClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SymbolSearchFacadeTest {
    @Test
    fun successMapsSymbolsAndParsesThePackage() = runBlocking {
        var requestedPackage: SymbolSearchClient.Package? = null
        val facade = SymbolSearchFacade { _, _, packageFilter, _ ->
            requestedPackage = packageFilter
            SymbolSearchClient.SearchResponse.Success(
                listOf(SymbolSearchClient.SymbolResult("arasaac:1", "dog", "https://img", SymbolSearchClient.Source.Arasaac)),
            )
        }

        val result = facade.search("dog", "en", "arasaac", prioritizeArasaac = false)

        assertEquals(SymbolSearchClient.Package.Arasaac, requestedPackage)
        assertEquals(SymbolSearchStatus.Success, result.status)
        assertEquals(listOf(NativeSymbol("arasaac:1", "dog", "https://img", "arasaac")), result.symbols)
    }

    @Test
    fun failuresKeepTheirKindAndRetryability() = runBlocking {
        suspend fun searchFailing(error: OpenSymbolsClient.SearchError) =
            SymbolSearchFacade { _, _, _, _ -> SymbolSearchClient.SearchResponse.Failure(error) }
                .search("dog", "en", "all", prioritizeArasaac = false)

        val notConfigured = searchFailing(OpenSymbolsClient.SearchError.NotConfigured)
        assertEquals(SymbolSearchStatus.NotConfigured, notConfigured.status)
        assertFalse(notConfigured.isRetryable)

        val offline = searchFailing(OpenSymbolsClient.SearchError.Network)
        assertEquals(SymbolSearchStatus.NetworkFailure, offline.status)
        assertTrue(offline.isRetryable)

        assertEquals(SymbolSearchStatus.Throttled, searchFailing(OpenSymbolsClient.SearchError.Throttled).status)
        assertEquals(SymbolSearchStatus.ServerFailure, searchFailing(OpenSymbolsClient.SearchError.Server).status)
    }
}
