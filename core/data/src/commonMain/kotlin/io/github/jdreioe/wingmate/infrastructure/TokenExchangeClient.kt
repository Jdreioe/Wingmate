package io.github.jdreioe.wingmate.infrastructure

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Token Exchange Client for secure Azure TTS authentication.
 * 
 * Instead of storing Azure API keys on the client, this client:
 * 1. Calls a serverless function (Azure Functions / Cloudflare Workers)
 * 2. The function fetches the real Azure key from secure storage (Key Vault)
 * 3. Returns a short-lived token (10 minutes) to the client
 * 
 * This follows the "Zero-Trust" security model - no secrets on client devices.
 */
class TokenExchangeClient(
    private val httpClient: HttpClient,
    private val tokenExchangeUrl: String,
    private val clientApiKey: String
) {
    private var cachedToken: String? = null
    private var cachedRegion: String? = null
    private var tokenExpiry: Long = 0
    
    /**
     * Get a valid Azure Speech token.
     * Returns cached token if still valid, otherwise fetches a new one.
     */
    suspend fun getToken(): TokenResult {
        val now = Clock.System.now().toEpochMilliseconds()
        
        // Return cached token if valid (with 1 minute buffer)
        cachedToken?.let { token ->
            cachedRegion?.let { region ->
                if (tokenExpiry > now + 60_000) {
                    val remainingSeconds = (tokenExpiry - now) / 1000
                    logger.debug { "Using cached token, expires in ${remainingSeconds}s" }
                    return TokenResult.Success(token, region, remainingSeconds)
                }
            }
        }
        
        logger.info { "Fetching new token from exchange service" }
        
        return try {
            val response = httpClient.post(tokenExchangeUrl) {
                header("X-API-Key", clientApiKey)
                header(HttpHeaders.Accept, ContentType.Application.Json)
            }
            
            when {
                response.status.isSuccess() -> {
                    val body = response.body<TokenResponse>()
                    
                    // Cache the token
                    cachedToken = body.token
                    cachedRegion = body.region
                    tokenExpiry = now + (body.expiresIn * 1000)
                    
                    logger.info { "Token exchange successful, expires in ${body.expiresIn}s" }
                    TokenResult.Success(body.token, body.region, body.expiresIn)
                }
                response.status.value == 401 -> {
                    logger.error { "Token exchange unauthorized - check CLIENT_API_KEY" }
                    TokenResult.Unauthorized
                }
                response.status.value == 429 -> {
                    logger.error { "Token exchange rate limited" }
                    TokenResult.RateLimited
                }
                else -> {
                    val errorBody = response.bodyAsText()
                    logger.error { "Token exchange failed: ${response.status} - $errorBody" }
                    TokenResult.Error("Token exchange failed: ${response.status}")
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Token exchange network error" }
            TokenResult.Error(e.message ?: "Network error during token exchange")
        }
    }
    
    /**
     * Invalidate the cached token.
     * Call this if you receive a 401 from Azure TTS.
     */
    fun invalidateToken() {
        logger.info { "Invalidating cached token" }
        cachedToken = null
        cachedRegion = null
        tokenExpiry = 0
    }
    
    /**
     * Check if we have a potentially valid cached token.
     */
    fun hasCachedToken(): Boolean {
        val now = Clock.System.now().toEpochMilliseconds()
        return cachedToken != null && tokenExpiry > now
    }
}

@Serializable
data class TokenResponse(
    val token: String,
    val region: String,
    val expiresIn: Long
)

sealed class TokenResult {
    data class Success(
        val token: String,
        val region: String,
        val expiresIn: Long
    ) : TokenResult()
    
    data class Error(val message: String) : TokenResult()
    data object Unauthorized : TokenResult()
    data object RateLimited : TokenResult()
}
