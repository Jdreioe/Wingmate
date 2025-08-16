package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import javazoom.jl.player.Player
import java.io.ByteArrayInputStream
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
        val cfg = getConfig() ?: throw IllegalStateException("No speech service config")
        val v = voice ?: Voice(name = "en-US-JennyNeural", primaryLanguage = "en-US")
        if (log.isDebugEnabled) {
            var name = v.name
            var primaryLanguage = v.primaryLanguage
            log.debug("Using voice: $name with $primaryLanguage")
        }
            // Run synthesis & playback on IO so UI thread is not blocked and we can read persisted settings
            withContext(Dispatchers.IO) {
                slf4jLogger.info("speak() called on thread={}", Thread.currentThread().name)
                val cfg = getConfig() ?: throw IllegalStateException("No speech service config")
                val v = voice ?: Voice(name = "en-US-JennyNeural", primaryLanguage = "en-US")

                // Determine effective language for SSML:
                // 1. voice.selectedLanguage (per-voice override)
                // 2. persisted UI primary language (SettingsRepository)
                // 3. voice.primaryLanguage
                // 4. fallback en-US
                val koin = org.koin.core.context.GlobalContext.getOrNull()
                val settingsRepo = koin?.let { runCatching { it.get<io.github.jdreioe.wingmate.domain.SettingsRepository>() }.getOrNull() }
                val uiSettings = settingsRepo?.let { runCatching { it.get() }.getOrNull() }
                val effectiveLang = when {
                    !v.selectedLanguage.isNullOrBlank() -> v.selectedLanguage
                    !uiSettings?.primaryLanguage.isNullOrBlank() -> uiSettings!!.primaryLanguage
                    !v.primaryLanguage.isNullOrBlank() -> v.primaryLanguage
                    else -> "en-US"
                }

                slf4jLogger.debug("Using voice: {} with {}", v.name, effectiveLang)
                val vForSsml = v.copy(primaryLanguage = effectiveLang)
                val ssml = AzureTtsClient.generateSsml(text, vForSsml)
                val bytes = AzureTtsClient.synthesize(client, ssml, cfg)

                // Play from memory using JLayer. Ensure only one player runs at a time.
                ByteArrayInputStream(bytes).use { bis ->
                    val player = Player(bis)
                    // Close any existing player before starting a new one
                    synchronized(playerLock) {
                        currentPlayer?.close()
                        currentPlayer = player
                    }

                    try {
                        player.play()
                    } finally {
                        // Clear reference when finished
                        synchronized(playerLock) {
                            if (currentPlayer === player) currentPlayer = null
                        }
                    }
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
