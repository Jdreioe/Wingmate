package io.github.jdreioe.wingmate.infrastructure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

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
    private suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        // Return cached token if still valid (with 30s buffer)
        if (cachedToken != null && System.currentTimeMillis() < tokenExpiry - 30000) {
            return@withContext cachedToken
        }
        
        runCatching {
            val url = URL(TOKEN_ENDPOINT)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            
            val body = "secret=$sharedSecret"
            conn.outputStream.use { it.write(body.toByteArray()) }
            
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                val tokenResponse = json.decodeFromString<TokenResponse>(response)
                cachedToken = tokenResponse.access_token
                // Token is typically valid for a few minutes
                tokenExpiry = System.currentTimeMillis() + 4 * 60 * 1000
                tokenResponse.access_token
            } else {
                null
            }
        }.getOrNull()
    }
    
    /**
     * Search for symbols matching query
     */
    suspend fun search(query: String, locale: String = "en"): List<SymbolResult> = withContext(Dispatchers.IO) {
        val token = getAccessToken() ?: return@withContext emptyList()
        
        runCatching {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = URL("$SYMBOLS_ENDPOINT?q=$encodedQuery&locale=$locale&access_token=$token")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                json.decodeFromString<List<SymbolResult>>(response)
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
