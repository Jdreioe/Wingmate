package io.github.jdreioe.wingmate.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Searches OpenSymbols through Wingmate's server-side proxy.
 *
 * The OpenSymbols shared secret must never be provided to this client: mobile
 * and desktop binaries contain only the public proxy URL.
 */
object OpenSymbolsClient {
    private const val SEARCH_PATH = "/v1/opensymbols/search"
    private const val OPEN_SYMBOLS_BASE_URL = "https://www.opensymbols.org"

    private var proxyBaseUrl: String? = null

    private val httpClient = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 15_000
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun setProxyBaseUrl(url: String?) {
        proxyBaseUrl = normalizeProxyBaseUrl(url)
    }

    fun isConfigured(): Boolean = proxyBaseUrl != null

    suspend fun search(query: String, locale: String = "en"): SearchResponse = withContext(Dispatchers.Default) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return@withContext SearchResponse.Success(emptyList())
        val baseUrl = proxyBaseUrl ?: return@withContext SearchResponse.Failure(SearchError.NotConfigured)

        try {
            val response: HttpResponse = httpClient.post("$baseUrl$SEARCH_PATH") {
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                setBody(json.encodeToString(ProxySearchRequest(normalizedQuery, normalizeLocale(locale))))
            }

            when (response.status) {
                HttpStatusCode.OK -> SearchResponse.Success(
                    json.decodeFromString<List<SymbolResult>>(response.bodyAsText()).map { symbol ->
                        symbol.copy(image_url = symbol.image_url?.toAbsoluteOpenSymbolsUrl())
                    }
                )
                HttpStatusCode.TooManyRequests -> SearchResponse.Failure(SearchError.Throttled)
                else -> SearchResponse.Failure(SearchError.Server)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            SearchResponse.Failure(SearchError.Network)
        }
    }

    internal fun normalizeLocale(locale: String): String {
        return locale.trim().lowercase().substringBefore('-').substringBefore('_')
            .takeIf { it.length == 2 } ?: "en"
    }

    internal fun normalizeProxyBaseUrl(url: String?): String? {
        val value = url?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        val lower = value.lowercase()
        val isHttps = lower.startsWith("https://")
        val isLocalHttp = lower.startsWith("http://localhost") ||
            lower.startsWith("http://127.0.0.1") ||
            lower.startsWith("http://[::1]")
        if (!isHttps && !isLocalHttp) return null
        if (value.contains('#') || value.contains('?')) return null
        return value.removeSuffix(SEARCH_PATH)
    }

    private fun String.toAbsoluteOpenSymbolsUrl(): String {
        return if (startsWith('/')) "$OPEN_SYMBOLS_BASE_URL$this" else this
    }

    enum class SearchError { NotConfigured, Throttled, Network, Server }

    sealed interface SearchResponse {
        data class Success(val symbols: List<SymbolResult>) : SearchResponse
        data class Failure(val error: SearchError) : SearchResponse
    }

    @Serializable
    private data class ProxySearchRequest(
        val query: String,
        val locale: String,
    )

    @Serializable
    data class SymbolResult(
        val id: Long,
        val symbol_key: String? = null,
        val name: String,
        val locale: String? = null,
        val license: String? = null,
        val author: String? = null,
        val repo_key: String? = null,
        val image_url: String? = null,
        val details_url: String? = null,
        val extension: String? = null,
    )
}
