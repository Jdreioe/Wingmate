package io.github.jdreioe.wingmate.infrastructure

import io.ktor.client.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * Client for OpenSymbols API - searches for pictograms/symbols.
 * Uses token-based auth with a shared secret.
 */
object OpenSymbolsClient {
    private const val BASE_URL = "https://www.opensymbols.org"
    private const val TOKEN_ENDPOINT = "$BASE_URL/api/v2/token"
    private const val SYMBOLS_ENDPOINT = "$BASE_URL/api/v2/symbols"
    
    // Runtime-provided shared secret. Never commit this value in source.
    private var sharedSecret: String? = null
    
    private var cachedToken: String? = null
    private var tokenExpiry: Long = 0L
    
    private val httpClient = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 15_000
        }
    }
    private val tokenMutex = Mutex()
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        isLenient = true
    }
    
    fun setSharedSecret(secret: String?) {
        sharedSecret = secret?.trim()?.takeIf { it.isNotEmpty() }
        cachedToken = null // Invalidate cached token
    }

    fun isConfigured(): Boolean = !sharedSecret.isNullOrBlank()
    
    /**
     * Get access token (cached if still valid)
     */
    private suspend fun getAccessToken(forceRefresh: Boolean = false): TokenResult = tokenMutex.withLock {
        val configuredSecret = sharedSecret ?: return@withLock TokenResult.NotConfigured
        val currentTime = Clock.System.now().toEpochMilliseconds()
        if (!forceRefresh && cachedToken != null && currentTime < tokenExpiry - 30_000) {
            return@withLock TokenResult.Success(cachedToken!!)
        }

        try {
            // OpenSymbols documents `secret` as a query parameter. Let Ktor
            // encode it so secrets containing reserved characters still work.
            val response: HttpResponse = httpClient.post(TOKEN_ENDPOINT) {
                parameter("secret", configuredSecret)
                accept(ContentType.Application.Json)
            }

            if (response.status == HttpStatusCode.OK) {
                val token = json.decodeFromString<TokenResponse>(response.bodyAsText()).access_token
                cachedToken = token
                tokenExpiry = Clock.System.now().toEpochMilliseconds() + 4 * 60 * 1000
                TokenResult.Success(token)
            } else {
                TokenResult.Failure(SearchError.Authentication)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            TokenResult.Failure(SearchError.Network)
        }
    }
    
    /**
     * Search for symbols matching query
     */
    suspend fun search(query: String, locale: String = "en"): SearchResponse = withContext(Dispatchers.Default) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return@withContext SearchResponse.Success(emptyList())

        val firstToken = getAccessToken()
        if (firstToken !is TokenResult.Success) return@withContext firstToken.asSearchResponse()

        val firstResponse = requestSymbols(normalizedQuery, normalizeLocale(locale), firstToken.token)
        if (firstResponse !is SearchResponse.Failure || firstResponse.error != SearchError.TokenExpired) {
            return@withContext firstResponse
        }

        cachedToken = null
        val refreshedToken = getAccessToken(forceRefresh = true)
        if (refreshedToken !is TokenResult.Success) return@withContext refreshedToken.asSearchResponse()
        requestSymbols(normalizedQuery, normalizeLocale(locale), refreshedToken.token)
    }

    private suspend fun requestSymbols(query: String, locale: String, token: String): SearchResponse {
        return try {
            val response: HttpResponse = httpClient.get(SYMBOLS_ENDPOINT) {
                parameter("q", query)
                parameter("locale", locale)
                parameter("access_token", token)
                accept(ContentType.Application.Json)
            }

            when (response.status) {
                HttpStatusCode.OK -> SearchResponse.Success(
                    json.decodeFromString<List<SymbolResult>>(response.bodyAsText()).map { symbol ->
                        symbol.copy(image_url = symbol.image_url?.toAbsoluteOpenSymbolsUrl())
                    }
                )
                HttpStatusCode.Unauthorized -> SearchResponse.Failure(SearchError.TokenExpired)
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

    private fun String.toAbsoluteOpenSymbolsUrl(): String {
        return if (startsWith('/')) "$BASE_URL$this" else this
    }

    enum class SearchError { NotConfigured, Authentication, TokenExpired, Throttled, Network, Server }

    sealed interface SearchResponse {
        data class Success(val symbols: List<SymbolResult>) : SearchResponse
        data class Failure(val error: SearchError) : SearchResponse
    }

    private sealed interface TokenResult {
        data class Success(val token: String) : TokenResult
        data class Failure(val error: SearchError) : TokenResult
        data object NotConfigured : TokenResult

        fun asSearchResponse(): SearchResponse = when (this) {
            is Success -> error("A successful token is not a search response")
            is Failure -> SearchResponse.Failure(error)
            NotConfigured -> SearchResponse.Failure(SearchError.NotConfigured)
        }
    }
    
    @Serializable
    data class TokenResponse(
        val access_token: String
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
        val extension: String? = null
    )
}
