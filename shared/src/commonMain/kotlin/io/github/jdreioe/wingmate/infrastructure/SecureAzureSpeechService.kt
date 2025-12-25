package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.*
import io.ktor.client.*
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Secure Azure Speech Service using Token Exchange.
 * 
 * This implementation:
 * 1. Never stores Azure subscription keys on the client
 * 2. Uses a backend token exchange service to get short-lived tokens
 * 3. Caches tokens locally (they're safe - only valid for 10 minutes)
 * 4. Falls back to system TTS if token exchange fails
 * 
 * Architecture:
 * ```
 * Client App → Token Exchange Backend → Azure Key Vault
 *                      ↓
 *                 Short-lived Token (10 min)
 *                      ↓
 * Client App → Azure TTS API (with Bearer token)
 * ```
 */
class SecureAzureSpeechService(
    private val httpClient: HttpClient,
    private val tokenExchangeClient: TokenExchangeClient,
    private val voiceRepository: VoiceRepository,
    private val saidTextRepository: SaidTextRepository,
    private val systemTtsFallback: SpeechService? = null
) : SpeechService {
    
    private var isPlaying = false
    private var isPaused = false
    
    override suspend fun speak(text: String, voice: Voice?, pitch: Double?, rate: Double?) {
        if (text.isBlank()) return
        
        val selectedVoice = voice ?: voiceRepository.getSelected() ?: getDefaultVoice()
        val effectiveVoice = selectedVoice.copy(
            pitch = pitch ?: selectedVoice.pitch,
            rate = rate ?: selectedVoice.rate
        )
        
        try {
            speakWithToken(text, effectiveVoice)
            
            // Log successful speech
            saidTextRepository.add(SaidText(
                saidText = text,
                date = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
                voiceName = effectiveVoice.name
            ))
        } catch (e: Exception) {
            logger.error(e) { "Secure TTS failed, attempting fallback" }
            
            // Fall back to system TTS if available
            systemTtsFallback?.speak(text, effectiveVoice, pitch, rate)
                ?: throw e
        }
    }
    
    override suspend fun speakSegments(
        segments: List<SpeechSegment>,
        voice: Voice?,
        pitch: Double?,
        rate: Double?
    ) {
        if (segments.isEmpty()) return
        
        val selectedVoice = voice ?: voiceRepository.getSelected() ?: getDefaultVoice()
        val effectiveVoice = selectedVoice.copy(
            pitch = pitch ?: selectedVoice.pitch,
            rate = rate ?: selectedVoice.rate
        )
        
        try {
            speakSegmentsWithToken(segments, effectiveVoice)
        } catch (e: Exception) {
            logger.error(e) { "Secure TTS segments failed, attempting fallback" }
            
            // Fall back to speaking combined text
            val combinedText = segments.joinToString(" ") { it.text }
            systemTtsFallback?.speak(combinedText, effectiveVoice, pitch, rate)
                ?: throw e
        }
    }
    
    private suspend fun speakWithToken(text: String, voice: Voice) {
        val tokenResult = tokenExchangeClient.getToken()
        
        when (tokenResult) {
            is TokenResult.Success -> {
                val ssml = AzureTtsClient.generateSsml(text, voice)
                
                try {
                    isPlaying = true
                    val audioBytes = AzureTtsClient.synthesizeWithToken(
                        client = httpClient,
                        ssml = ssml,
                        token = tokenResult.token,
                        region = tokenResult.region
                    )
                    
                    // Play the audio (platform-specific implementation needed)
                    playAudio(audioBytes)
                    
                } catch (e: TokenExpiredException) {
                    // Token expired mid-request, invalidate and retry once
                    logger.warn { "Token expired, refreshing and retrying" }
                    tokenExchangeClient.invalidateToken()
                    
                    val newTokenResult = tokenExchangeClient.getToken()
                    if (newTokenResult is TokenResult.Success) {
                        val audioBytes = AzureTtsClient.synthesizeWithToken(
                            client = httpClient,
                            ssml = ssml,
                            token = newTokenResult.token,
                            region = newTokenResult.region
                        )
                        playAudio(audioBytes)
                    } else {
                        throw RuntimeException("Failed to refresh token")
                    }
                } finally {
                    isPlaying = false
                }
            }
            
            is TokenResult.Unauthorized -> {
                throw RuntimeException("Token exchange unauthorized - check API key configuration")
            }
            
            is TokenResult.RateLimited -> {
                throw RuntimeException("Token exchange rate limited - try again later")
            }
            
            is TokenResult.Error -> {
                throw RuntimeException("Token exchange failed: ${tokenResult.message}")
            }
        }
    }
    
    private suspend fun speakSegmentsWithToken(segments: List<SpeechSegment>, voice: Voice) {
        val tokenResult = tokenExchangeClient.getToken()
        
        when (tokenResult) {
            is TokenResult.Success -> {
                val ssml = AzureTtsClient.generateSsml(segments, voice)
                
                isPlaying = true
                try {
                    val audioBytes = AzureTtsClient.synthesizeWithToken(
                        client = httpClient,
                        ssml = ssml,
                        token = tokenResult.token,
                        region = tokenResult.region
                    )
                    playAudio(audioBytes)
                } finally {
                    isPlaying = false
                }
            }
            
            else -> {
                // Convert to simple text and try fallback
                val combinedText = segments.joinToString(" ") { it.text }
                speakWithToken(combinedText, voice)
            }
        }
    }
    
    /**
     * Platform-specific audio playback.
     * Override this in platform implementations.
     */
    protected open suspend fun playAudio(audioBytes: ByteArray) {
        // Default implementation logs a warning
        // Platform implementations should override this
        logger.warn { "playAudio not implemented - ${audioBytes.size} bytes received" }
    }
    
    override suspend fun pause() {
        isPaused = true
        // Platform-specific pause implementation
    }
    
    override suspend fun stop() {
        isPlaying = false
        isPaused = false
        // Platform-specific stop implementation
    }
    
    override suspend fun resume() {
        isPaused = false
        // Platform-specific resume implementation
    }
    
    override fun isPlaying(): Boolean = isPlaying
    
    override fun isPaused(): Boolean = isPaused
    
    private fun getDefaultVoice(): Voice = Voice(
        name = "en-US-JennyNeural",
        displayName = "Jenny",
        gender = "Female",
        primaryLanguage = "en-US"
    )
}
