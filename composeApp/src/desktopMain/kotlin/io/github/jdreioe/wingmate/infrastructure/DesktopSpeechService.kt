package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import javazoom.jl.player.Player
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import io.github.jdreioe.wingmate.domain.ConfigRepository
import org.koin.core.context.GlobalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

class DesktopSpeechService : SpeechService {
    private val client = HttpClient(OkHttp) {}
    private val slf4jLogger = LoggerFactory.getLogger(DesktopSpeechService::class.java)
    // Only one Player playback at a time
    private var currentPlayer: Player? = null
    private val playerLock = Any()
    private val log = LoggerFactory.getLogger("DesktopSpeechService")

    override suspend fun speak(text: String, voice: Voice?, pitch: Double?, rate: Double?) {
        // Check user preference for TTS engine first
        val koin = GlobalContext.getOrNull()
        val settingsRepo = koin?.let { runCatching { it.get<io.github.jdreioe.wingmate.domain.SettingsRepository>() }.getOrNull() }
        val uiSettings = settingsRepo?.let { runCatching { it.get() }.getOrNull() }
        
        // If user prefers system TTS, just throw an exception since desktop system TTS isn't implemented
        if (uiSettings?.useSystemTts == true) {
            throw IllegalStateException("System TTS not available on desktop. Please use Azure TTS or run on mobile.")
        }
        
        val cfg = getConfig() ?: throw IllegalStateException("No speech service config")
        
        val v = voice ?: Voice(name = "en-US-JennyNeural", primaryLanguage = "en-US")
        if (log.isDebugEnabled) {
            val name = v.name
            val primaryLanguage = v.primaryLanguage
            log.debug("Using voice: $name with $primaryLanguage")
        }
        
        // Run synthesis & playback on IO so UI thread is not blocked and we can read persisted settings
        withContext(Dispatchers.IO) {
            slf4jLogger.info("speak() called on thread={}", Thread.currentThread().name)
            
            try {
                slf4jLogger.debug("Starting audio synthesis for '{}'", text)
                val config = AudioConfig.fromDefaultSpeakerOutput()
                
                val synthesizer = SpeechSynthesizer(cfg, config)
                val ssml = synthesizer.ssmlFromText(text, v, pitch, rate)
                
                // Create cancellation token that can be cancelled
                slf4jLogger.debug("Calling speakSsmlAsync with cancellation token: {}", ssml)
                
                val result = synthesizer.speakSsmlAsync(ssml).get()
                
                when (result.reason) {
                    ResultReason.SynthesizingAudioCompleted -> {
                        slf4jLogger.debug("Synthesis completed successfully for text: {}", text)
                        return@withContext
                    }
                    
                    ResultReason.Canceled -> {
                        val cancellationDetails = CancellationDetails.fromResult(result)
                        if (cancellationDetails.reason == CancellationReason.Error) {
                            slf4jLogger.error(
                                "Synthesis canceled due to error. Code: {}, Details: {}",
                                cancellationDetails.errorCode,
                                cancellationDetails.errorDetails
                            )
                        } else {
                            slf4jLogger.info("Synthesis canceled")
                        }
                        throw RuntimeException("Speech synthesis failed: ${cancellationDetails.errorDetails}")
                    }
                    
                    else -> {
                        slf4jLogger.error("Unexpected synthesis result: {}", result.reason)
                        throw RuntimeException("Unexpected synthesis result: ${result.reason}")
                    }
                }
            } catch (e: Exception) {
                slf4jLogger.error("Error during speech synthesis", e)
                throw e
            }
        }
    }

    override suspend fun pause() {
        // JLayer Player doesn't support pause; implement later if needed
    }

    override suspend fun stop() {
        // Stop any current playback by closing the Player
        synchronized(playerLock) {
            try {
                currentPlayer?.close()
            } catch (t: Throwable) {
                slf4jLogger.warn("Error while stopping player", t)
            } finally {
                currentPlayer = null
            }
        }
    }

    private suspend fun getConfig(): SpeechServiceConfig? {
        // Prefer persisted config from the app's ConfigRepository (Koin)
        val koin = GlobalContext.getOrNull()
        val repo = koin?.let { runCatching { it.get<ConfigRepository>() }.getOrNull() }
        if (repo != null) {
            return try {
                // call repository on IO dispatcher
                withContext(Dispatchers.IO) { repo.getSpeechConfig() }
            } catch (t: Throwable) {
                null
            }
        }

        // Fallback to environment variables for headless / CI usage
        val endpoint = System.getenv("WINGMATE_AZURE_REGION") ?: ""
        val key = System.getenv("WINGMATE_AZURE_KEY") ?: ""
        return if (endpoint.isNotBlank() && key.isNotBlank()) {
            SpeechServiceConfig(endpoint = endpoint, subscriptionKey = key)
        } else null
    }
}
