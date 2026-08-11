import io.github.jdreioe.wingmate.infrastructure.SymbolSearchClient
import kotlin.test.Test
import kotlin.test.assertEquals

class SymbolSearchClientTest {
    private fun symbol(id: String, source: SymbolSearchClient.Source) =
        SymbolSearchClient.SymbolResult(id, id, "https://example.test/$id", source)

    @Test
    fun combinedSearchInterleavesSources() {
        val ordered = SymbolSearchClient.orderCombinedResults(
            listOf(
                symbol("open-1", SymbolSearchClient.Source.OpenSymbols),
                symbol("open-2", SymbolSearchClient.Source.OpenSymbols),
                symbol("mulberry-1", SymbolSearchClient.Source.Mulberry),
                symbol("arasaac-1", SymbolSearchClient.Source.Arasaac),
            ),
            prioritizeArasaac = false,
        )

        assertEquals(listOf("open-1", "arasaac-1", "mulberry-1", "open-2"), ordered.map { it.id })
    }

    @Test
    fun downloadedArasaacSymbolsAreRankedFirst() {
        val ordered = SymbolSearchClient.orderCombinedResults(
            listOf(
                symbol("open-1", SymbolSearchClient.Source.OpenSymbols),
                symbol("mulberry-1", SymbolSearchClient.Source.Mulberry),
                symbol("arasaac-1", SymbolSearchClient.Source.Arasaac),
                symbol("arasaac-2", SymbolSearchClient.Source.Arasaac),
            ),
            prioritizeArasaac = true,
        )

        assertEquals(listOf("arasaac-1", "arasaac-2", "open-1", "mulberry-1"), ordered.map { it.id })
    }
}
