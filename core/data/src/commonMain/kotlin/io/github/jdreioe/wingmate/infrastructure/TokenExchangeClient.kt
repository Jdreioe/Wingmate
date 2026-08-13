package io.github.jdreioe.wingmate.infrastructure

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.loggingClassName
import kotlin.time.Clock

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
                    OperationalLogger.debug("speech_token.acquire", "cache_hit")
                    return TokenResult.Success(token, region, remainingSeconds)
                }
            }
        }
        
        OperationalLogger.info("speech_token.exchange", "started")
        
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
                    
                    OperationalLogger.info("speech_token.exchange", "succeeded")
                    TokenResult.Success(body.token, body.region, body.expiresIn)
                }
                response.status.value == 401 -> {
                    OperationalLogger.warn("speech_token.exchange", "unauthorized", statusCode = 401)
                    TokenResult.Unauthorized
                }
                response.status.value == 429 -> {
                    OperationalLogger.warn("speech_token.exchange", "rate_limited", statusCode = 429)
                    TokenResult.RateLimited
                }
                else -> {
                    OperationalLogger.error(
                        operation = "speech_token.exchange",
                        outcome = "failed",
                        statusCode = response.status.value,
                    )
                    TokenResult.Error("Token exchange failed: ${response.status}")
                }
            }
        } catch (e: Exception) {
            OperationalLogger.error(
                operation = "speech_token.exchange",
                outcome = "network_failed",
                exceptionClass = e.loggingClassName(),
            )
            TokenResult.Error(e.message ?: "Network error during token exchange")
        }
    }
    
    /**
     * Invalidate the cached token.
     * Call this if you receive a 401 from Azure TTS.
     */
    fun invalidateToken() {
        OperationalLogger.info("speech_token.cache", "invalidated")
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
