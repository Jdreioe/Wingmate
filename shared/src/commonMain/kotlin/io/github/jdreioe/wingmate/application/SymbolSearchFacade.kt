package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.infrastructure.OpenSymbolsClient
import io.github.jdreioe.wingmate.infrastructure.SymbolSearchClient

enum class SymbolSearchStatus {
    Success,

    /** This build has no OpenSymbols proxy; retrying will not help. */
    NotConfigured,

    /** The symbol service asked to slow down; retry later. */
    Throttled,

    /** The device could not reach the symbol service. */
    NetworkFailure,

    /** The symbol service answered with an error. */
    ServerFailure,
}

data class NativeSymbol(
    val id: String,
    val name: String,
    val imageUrl: String?,
    /** `opensymbols`, `mulberry`, or `arasaac`. */
    val source: String,
)

data class SymbolSearchResult(
    val status: SymbolSearchStatus,
    val symbols: List<NativeSymbol> = emptyList(),
) {
    val isSuccess: Boolean get() = status == SymbolSearchStatus.Success
    val isRetryable: Boolean get() = status != SymbolSearchStatus.Success && status != SymbolSearchStatus.NotConfigured
}

/**
 * Native boundary to the shared symbol search, so ranking and package rules stay
 * in [SymbolSearchClient]. The OpenSymbols proxy is configured once at startup.
 */
class SymbolSearchFacade(
    private val searchSymbols: suspend (
        query: String,
        locale: String,
        packageFilter: SymbolSearchClient.Package,
        prioritizeArasaac: Boolean,
    ) -> SymbolSearchClient.SearchResponse,
) {
    /** [symbolPackage] is a package wire value (`all`, `opensymbols`, `mulberry`, `arasaac`). */
    suspend fun search(
        query: String,
        locale: String,
        symbolPackage: String,
        prioritizeArasaac: Boolean,
    ): SymbolSearchResult =
        when (
            val response = searchSymbols(
                query,
                locale,
                SymbolSearchClient.Package.fromWireValue(symbolPackage),
                prioritizeArasaac,
            )
        ) {
            is SymbolSearchClient.SearchResponse.Success -> SymbolSearchResult(
                status = SymbolSearchStatus.Success,
                symbols = response.symbols.map {
                    NativeSymbol(id = it.id, name = it.name, imageUrl = it.imageUrl, source = it.source.name.lowercase())
                },
            )
            is SymbolSearchClient.SearchResponse.Failure -> SymbolSearchResult(response.error.toStatus())
        }
}

private fun OpenSymbolsClient.SearchError.toStatus(): SymbolSearchStatus = when (this) {
    OpenSymbolsClient.SearchError.NotConfigured -> SymbolSearchStatus.NotConfigured
    OpenSymbolsClient.SearchError.Throttled -> SymbolSearchStatus.Throttled
    OpenSymbolsClient.SearchError.Network -> SymbolSearchStatus.NetworkFailure
    OpenSymbolsClient.SearchError.Server -> SymbolSearchStatus.ServerFailure
}
