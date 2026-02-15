package io.github.jdreioe.wingmate.infrastructure

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Client for OpenSymbols API - searches for pictograms/symbols.
 * Uses token-based auth with a shared secret.
 */
object OpenSymbolsClient {
    private const val BASE_URL = "https://www.opensymbols.org"
    private const val TOKEN_ENDPOINT = "$BASE_URL/api/v2/token"
    private const val SYMBOLS_ENDPOINT = "$BASE_URL/api/v2/symbols"
    
    // User's shared secret - TODO: move to secure config
    private var sharedSecret: String = "6a1ee5b773c69533b82d5166"
    
    private var cachedToken: String? = null
    private var tokenExpiry: Long = 0L
    
    private val httpClient = HttpClient()
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        isLenient = true
    }
    
    fun setSharedSecret(secret: String) {
        sharedSecret = secret
        cachedToken = null // Invalidate cached token
    }
    
    /**
     * Get access token (cached if still valid)
     */
    private suspend fun getAccessToken(): String? = withContext(Dispatchers.Default) {
        // Return cached token if still valid (with 30s buffer)
        val currentTime = Clock.System.now().toEpochMilliseconds()
        if (cachedToken != null && currentTime < tokenExpiry - 30000) {
            return@withContext cachedToken
        }
        
        runCatching {
            val response: HttpResponse = httpClient.post(TOKEN_ENDPOINT) {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("secret=$sharedSecret")
            }
            
            if (response.status == HttpStatusCode.OK) {
                val tokenResponse = json.decodeFromString<TokenResponse>(response.bodyAsText())
                cachedToken = tokenResponse.access_token
                // Token is typically valid for a few minutes
                tokenExpiry = Clock.System.now().toEpochMilliseconds() + 4 * 60 * 1000
                tokenResponse.access_token
            } else {
                null
            }
        }.getOrNull()
    }
    
    /**
     * Search for symbols matching query
     */
    suspend fun search(query: String, locale: String = "en"): List<SymbolResult> = withContext(Dispatchers.Default) {
        val token = getAccessToken() ?: return@withContext emptyList()
        
        runCatching {
            val response: HttpResponse = httpClient.get(SYMBOLS_ENDPOINT) {
                parameter("q", query)
                parameter("locale", locale)
                parameter("access_token", token)
                accept(ContentType.Application.Json)
            }
            
            if (response.status == HttpStatusCode.OK) {
                json.decodeFromString<List<SymbolResult>>(response.bodyAsText())
            } else {
                emptyList()
            }
        }.getOrElse { emptyList() }
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
