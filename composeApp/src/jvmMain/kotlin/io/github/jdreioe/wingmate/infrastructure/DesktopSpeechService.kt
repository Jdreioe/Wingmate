package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.domain.ConfigRepository
import org.koin.core.context.GlobalContext
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import java.io.File
import javazoom.jl.player.Player
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

class DesktopSpeechService : SpeechService {
    private val client = HttpClient(OkHttp) {}
    private val log = LoggerFactory.getLogger("DesktopSpeechService")

    override suspend fun speak(text: String, voice: Voice?, pitch: Double?, rate: Double?) {
        log.info("speak() called on thread={}", Thread.currentThread().name)
        // Run synthesis and playback on IO dispatcher to avoid blocking UI thread
        withContext(Dispatchers.IO) {
            log.info("speak() switched to IO on thread={}", Thread.currentThread().name)
            val cfg = getConfig() ?: throw IllegalStateException("No speech service config")
            val v = voice ?: Voice(name = "en-US-JennyNeural", primaryLanguage = "en-US")

            // Resolve effective language for SSML in this order:
            // 1. voice.selectedLanguage (per-voice selection)
            // 2. persisted UI primary language (SettingsRepository)
            // 3. voice.primaryLanguage
            // 4. fallback "en-US"
            val koin = GlobalContext.getOrNull()
            val settingsRepo = koin?.let { runCatching { it.get<io.github.jdreioe.wingmate.domain.SettingsRepository>() }.getOrNull() }
            val uiSettings = settingsRepo?.let { runCatching { it.get() }.getOrNull() }
            val effectiveLang = when {
                !v.selectedLanguage.isNullOrBlank() -> v.selectedLanguage
                !uiSettings?.primaryLanguage.isNullOrBlank() -> uiSettings!!.primaryLanguage
                !v.primaryLanguage.isNullOrBlank() -> v.primaryLanguage
                else -> "en-US"
            }

            val vForSsml = v.copy(primaryLanguage = effectiveLang)
            val ssml = AzureTtsClient.generateSsml(text, vForSsml)
            log.info("sending synth request to Azure endpoint (not printing key)")
            val bytes = AzureTtsClient.synthesize(client, ssml, cfg)
            log.info("received {} bytes from Azure TTS", bytes.size)

            // Save audio to disk under platform data dir (e.g., ~/.local/share/wingmate/audio)
            val audioDir = DesktopPaths.dataDir().resolve("audio")
            if (!Files.exists(audioDir)) Files.createDirectories(audioDir)
            val safeName = text.take(32).replace("[^A-Za-z0-9-_ ]".toRegex(), "_").trim().ifBlank { "utterance" }
            val outPath = audioDir.resolve("${'$'}{System.currentTimeMillis()}_${'$'}safeName.mp3")
            Files.write(outPath, bytes)

            // Record history with file path if repository is available
            runCatching {
                val koin = GlobalContext.getOrNull()
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

            // Play from saved file for consistency with stored path
            FileInputStream(outPath.toFile()).use { fis ->
                log.info("starting playback on thread={}", Thread.currentThread().name)
                val player = Player(fis)
                player.play()
                log.info("playback finished")
            }
        }
    }

    override suspend fun pause() {
        // JLayer Player doesn't support pause; implement later if needed
    }

    override suspend fun stop() {
        // Not implemented for now
    }

    private suspend fun getConfig(): SpeechServiceConfig? {
        // Prefer saved config from ConfigRepository (Koin). Fall back to env vars.
        GlobalContext.getOrNull()?.let { koin ->
            runCatching {
                val repo = koin.get<ConfigRepository>()
                return withContext(Dispatchers.IO) { repo.getSpeechConfig() }
            }
        }

        val endpoint = System.getenv("WINGMATE_AZURE_REGION") ?: ""
        val key = System.getenv("WINGMATE_AZURE_KEY") ?: ""
        return if (endpoint.isNotBlank() && key.isNotBlank()) {
            SpeechServiceConfig(endpoint = endpoint, subscriptionKey = key)
        } else null
    }
}
