package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.infrastructure.AzureTtsClient
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import java.io.File
import android.os.Environment
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AndroidSpeechService prefers using Azure TTS when a persisted SpeechServiceConfig exists.
 * It synthesizes to memory, writes an MP3 temp file and plays it with MediaPlayer.
 * Falls back to platform TextToSpeech when no Azure config is available.
 */
class AndroidSpeechService(private val context: Context) : SpeechService {
    private val client = HttpClient(OkHttp) {}
    // Removed SLF4J logger for cross-platform compatibility

    // Platform TTS (fallback)
    private var tts: TextToSpeech? = null
    private val ttsReady = AtomicBoolean(false)

    // MediaPlayer based playback for Azure synthesized audio
    private var mediaPlayer: MediaPlayer? = null
    private val playerLock = Any()

    private fun ensureTts(): TextToSpeech {
        val cur = tts
        if (cur != null) return cur
        val created = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady.set(true)
            }
        }
        created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {}
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {}
        })
        tts = created
        return created
    }

    override suspend fun speak(text: String, voice: Voice?, pitch: Double?, rate: Double?) {
        // Try to obtain persisted Azure config; if present, prefer Azure TTS pipeline
        val cfg = getConfig()
        if (cfg != null) {
            withContext(Dispatchers.IO) {
                try {
                    val v = voice ?: Voice(name = "en-US-JennyNeural", primaryLanguage = "en-US")

                    // Determine effective language similar to desktop implementation
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
                    val bytes = AzureTtsClient.synthesize(client, ssml, cfg)

                    // Persist to an app-private Music directory so history can reference it later
                    val musicRoot = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
                    val outDir = File(musicRoot, "wingmate/audio").apply { if (!exists()) mkdirs() }
                    val safeName = text.take(32).replace("[^A-Za-z0-9-_ ]".toRegex(), "_").trim().ifBlank { "utterance" }
                    val fileName = "${'$'}{System.currentTimeMillis()}_${'$'}safeName.mp3"
                    val outFile = File(outDir, fileName)
                    outFile.outputStream().use { it.write(bytes) }

                    // Save history record if repository is available
                    runCatching {
                        val koin = GlobalContext.getOrNull()
                        val saidRepo = koin?.get<io.github.jdreioe.wingmate.domain.SaidTextRepository>()
                        val now = System.currentTimeMillis()
                        saidRepo?.add(
                            io.github.jdreioe.wingmate.domain.SaidText(
                                date = now,
                                saidText = text,
                                voiceName = vForSsml.name,
                                pitch = vForSsml.pitch,
                                speed = vForSsml.rate,
                                audioFilePath = outFile.absolutePath,
                                createdAt = now,
                                position = 0,
                                primaryLanguage = vForSsml.selectedLanguage?.ifBlank { vForSsml.primaryLanguage }
                            )
                        )
                    }

                    val player = MediaPlayer()
                    player.setOnCompletionListener { mp ->
                        try {
                            synchronized(playerLock) {
                                if (mediaPlayer === mp) {
                                    mp.reset()
                                    mp.release()
                                    mediaPlayer = null
                                }
                            }
                        } catch (_: Throwable) {
                        } finally { /* keep outFile for history */ }
                    }
                    player.setOnErrorListener { mp, what, extra ->
                        try { synchronized(playerLock) { if (mediaPlayer === mp) { mp.reset(); mp.release(); mediaPlayer = null } } } catch (_: Throwable) {}
                        false
                    }

                    player.setDataSource(outFile.absolutePath)
                    player.prepare()
                    synchronized(playerLock) {
                        // Stop any existing player
                        mediaPlayer?.let { try { it.stop(); it.release() } catch (_: Throwable) {} }
                        mediaPlayer = player
                    }
                    player.start()
                } catch (t: Throwable) {
                    println("Azure TTS failed, falling back to platform TTS: $t")
                    // Fallback to platform TTS on error
                    speakWithPlatformTts(text, voice, pitch, rate)
                }
            }
        } else {
            // No Azure config – use platform TextToSpeech
            speakWithPlatformTts(text, voice, pitch, rate)
        }
    }

    private suspend fun speakWithPlatformTts(text: String, voice: Voice?, pitch: Double?, rate: Double?) {
        withContext(Dispatchers.Main) {
            val t = ensureTts()
            val lang = voice?.primaryLanguage ?: Locale.getDefault().toLanguageTag()
            val loc = Locale.forLanguageTag(lang)
            t.language = loc
            t.setPitch((pitch ?: 1.0).toFloat())
            t.setSpeechRate((rate ?: 1.0).toFloat())
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wingmate-${System.currentTimeMillis()}")
        }
    }

    override suspend fun pause() {
        // Pause MediaPlayer if used; for platform TTS simulate pause with stop()
        synchronized(playerLock) {
            mediaPlayer?.let { if (it.isPlaying) it.pause() }
        }
        // platform TTS has no pause
    }

    override suspend fun stop() {
        synchronized(playerLock) {
            try {
                mediaPlayer?.let { try { it.stop(); it.release() } catch (_: Throwable) {} }
            } finally {
                mediaPlayer = null
            }
        }
        try { tts?.stop() } catch (_: Throwable) {}
    }

    private suspend fun getConfig(): SpeechServiceConfig? {
        val koin = GlobalContext.getOrNull()
        val repo = koin?.let { runCatching { it.get<io.github.jdreioe.wingmate.domain.ConfigRepository>() }.getOrNull() }
        if (repo != null) {
            return try { withContext(Dispatchers.IO) { repo.getSpeechConfig() } } catch (_: Throwable) { null }
        }

        // Fallback to environment variables if present (rare on Android)
        val endpoint = System.getenv("WINGMATE_AZURE_REGION") ?: ""
        val key = System.getenv("WINGMATE_AZURE_KEY") ?: ""
        return if (endpoint.isNotBlank() && key.isNotBlank()) SpeechServiceConfig(endpoint = endpoint, subscriptionKey = key) else null
    }
}
