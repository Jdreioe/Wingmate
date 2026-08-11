package io.github.jdreioe.wingmate.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Shared search and ranking rules for every native symbol picker. */
object SymbolSearchClient {
    private const val ARASAAC_SEARCH_URL = "https://api.arasaac.org/api/pictograms"
    private const val ARASAAC_IMAGE_URL = "https://api.arasaac.org/api/pictograms"

    private val httpClient = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 15_000
        }
    }
    private val json = Json { ignoreUnknownKeys = true }

    enum class Package(val wireValue: String) {
        All("all"),
        OpenSymbols("opensymbols"),
        Mulberry("mulberry"),
        Arasaac("arasaac");

        companion object {
            fun fromWireValue(value: String): Package = entries.firstOrNull { it.wireValue == value } ?: All
        }
    }

    enum class Source { OpenSymbols, Mulberry, Arasaac }

    data class SymbolResult(
        val id: String,
        val name: String,
        val imageUrl: String?,
        val source: Source,
    )

    sealed interface SearchResponse {
        data class Success(val symbols: List<SymbolResult>) : SearchResponse
        data class Failure(val error: OpenSymbolsClient.SearchError) : SearchResponse
    }

    suspend fun search(
        query: String,
        locale: String = "en",
        packageFilter: Package = Package.All,
        prioritizeArasaac: Boolean = false,
    ): SearchResponse {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return SearchResponse.Success(emptyList())

        return when (packageFilter) {
            Package.OpenSymbols -> mapOpenSymbols(OpenSymbolsClient.search(normalizedQuery, locale))
            Package.Mulberry -> mapOpenSymbols(
                OpenSymbolsClient.search(normalizedQuery, locale, OpenSymbolsClient.Repository.Mulberry),
                Source.Mulberry,
            )
            Package.Arasaac -> searchArasaac(normalizedQuery, locale)
            Package.All -> searchAll(normalizedQuery, locale, prioritizeArasaac)
        }
    }

    private suspend fun searchAll(
        query: String,
        locale: String,
        prioritizeArasaac: Boolean,
    ): SearchResponse = coroutineScope {
        val openSymbols = async { mapOpenSymbols(OpenSymbolsClient.search(query, locale)) }
        val mulberry = async {
            mapOpenSymbols(
                OpenSymbolsClient.search(query, locale, OpenSymbolsClient.Repository.Mulberry),
                Source.Mulberry,
            )
        }
        val arasaac = async { searchArasaac(query, locale) }
        val responses = listOf(openSymbols.await(), mulberry.await(), arasaac.await())
        val successful = responses.filterIsInstance<SearchResponse.Success>()
        if (successful.isEmpty()) {
            return@coroutineScope responses.filterIsInstance<SearchResponse.Failure>().first()
        }

        SearchResponse.Success(
            orderCombinedResults(
                successful.flatMap { it.symbols },
                prioritizeArasaac,
            )
        )
    }

    private fun mapOpenSymbols(
        response: OpenSymbolsClient.SearchResponse,
        forcedSource: Source? = null,
    ): SearchResponse = when (response) {
        is OpenSymbolsClient.SearchResponse.Failure -> SearchResponse.Failure(response.error)
        is OpenSymbolsClient.SearchResponse.Success -> SearchResponse.Success(
            response.symbols.map { symbol ->
                SymbolResult(
                    id = "opensymbols:${symbol.id}",
                    name = symbol.name,
                    imageUrl = symbol.image_url,
                    source = forcedSource ?: Source.OpenSymbols,
                )
            }
        )
    }

    private suspend fun searchArasaac(query: String, locale: String): SearchResponse {
        val language = OpenSymbolsClient.normalizeLocale(locale)
        val url = "$ARASAAC_SEARCH_URL/$language/bestsearch/${query.encodeURLPathPart()}"
        return try {
            val response = httpClient.get(url)
            if (!response.status.isSuccess()) return SearchResponse.Failure(OpenSymbolsClient.SearchError.Server)
            val pictograms = json.decodeFromString<List<ArasaacPictogram>>(response.bodyAsText())
            SearchResponse.Success(
                pictograms.mapNotNull { pictogram ->
                    val name = pictogram.keywords.firstOrNull()?.keyword?.trim().orEmpty()
                    if (name.isEmpty()) return@mapNotNull null
                    SymbolResult(
                        id = "arasaac:${pictogram.id}",
                        name = name,
                        imageUrl = "$ARASAAC_IMAGE_URL/${pictogram.id}?download=false&resolution=500",
                        source = Source.Arasaac,
                    )
                }
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            SearchResponse.Failure(OpenSymbolsClient.SearchError.Network)
        }
    }

    internal fun orderCombinedResults(
        symbols: List<SymbolResult>,
        prioritizeArasaac: Boolean,
    ): List<SymbolResult> {
        val unique = symbols.distinctBy { it.id }
        val arasaac = unique.filter { it.source == Source.Arasaac }
        val openSymbols = unique.filter { it.source == Source.OpenSymbols }
        val mulberry = unique.filter { it.source == Source.Mulberry }
        val mixedRemote = interleave(openSymbols, mulberry)
        return if (prioritizeArasaac) arasaac + mixedRemote else interleave(mixedRemote, arasaac)
    }

    private fun <T> interleave(first: List<T>, second: List<T>): List<T> = buildList(first.size + second.size) {
        repeat(maxOf(first.size, second.size)) { index ->
            first.getOrNull(index)?.let(::add)
            second.getOrNull(index)?.let(::add)
        }
    }

    @Serializable
    private data class ArasaacPictogram(
        @SerialName("_id") val id: Long,
        val keywords: List<ArasaacKeyword> = emptyList(),
    )

    @Serializable
    private data class ArasaacKeyword(val keyword: String)
}
