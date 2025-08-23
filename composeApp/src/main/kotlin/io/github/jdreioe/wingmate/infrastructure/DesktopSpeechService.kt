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

                // Resolve data directory for audio files
                fun dataDir(): Path {
                    val os = System.getProperty("os.name").lowercase()
                    return when {
                        os.contains("mac") -> Paths.get(System.getProperty("user.home"), "Library", "Application Support", "Wingmate")
                        os.contains("win") -> {
                            val local = System.getenv("LOCALAPPDATA") ?: Paths.get(System.getProperty("user.home"), "AppData", "Local").toString()
                            Paths.get(local, "Wingmate")
                        }
                        else -> Paths.get(System.getProperty("user.home"), ".local", "share", "wingmate")
                    }
                }

                val audioDir = dataDir().resolve("audio")
                if (!Files.exists(audioDir)) Files.createDirectories(audioDir)
                val safeName = text.take(32).replace("[^A-Za-z0-9-_ ]".toRegex(), "_").trim().ifBlank { "utterance" }
                val outPath = audioDir.resolve("${'$'}{System.currentTimeMillis()}_${'$'}safeName.mp3")
                Files.write(outPath, bytes)

                // Save history with audioFilePath if repo available
                runCatching {
                    val koin = org.koin.core.context.GlobalContext.getOrNull()
                    val repo = koin?.get<io.github.jdreioe.wingmate.domain.SaidTextRepository>()
                    val now = System.currentTimeMillis()
                    repo?.add(
                        io.github.jdreioe.wingmate.domain.SaidText(
                            date = now,
                            saidText = text,
                            voiceName = vForSsml.name,
                            pitch = vForSsml.pitch,
                            speed = vForSsml.rate,
                            audioFilePath = outPath.toAbsolutePath().toString(),
                            createdAt = now,
                            position = 0,
                            primaryLanguage = vForSsml.selectedLanguage?.takeIf { it.isNotBlank() } ?: vForSsml.primaryLanguage
                        )
                    )
                }

                // Play from saved file; ensure only one player at a time
                FileInputStream(outPath.toFile()).use { fis ->
                    val player = Player(fis)
                    synchronized(playerLock) {
                        currentPlayer?.close()
                        currentPlayer = player
                    }
                    try {
                        player.play()
                    } finally {
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
