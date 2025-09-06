package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.infrastructure.AzureTtsClient
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val audioManager: AudioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        // For simplicity: on full loss, stop playback; on other changes we ignore/duck handled by system
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            scope.launch { runCatching { stop() } }
        }
    }
    private val ttsAudioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
    private var audioFocusRequest: AudioFocusRequest? = null
    private fun requestAudioFocus(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .setAudioAttributes(ttsAudioAttributes)
                    .build()
                audioFocusRequest = req
                audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    focusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (_: Throwable) { false }
    }
    private fun abandonAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(focusChangeListener)
            }
        } catch (_: Throwable) { }
    }
    private val CACHE_TTL_MS: Long = 30L * 24 * 60 * 60 * 1000
    
    // Track if we've already shown the offline warning to avoid spam
    private val offlineWarningShown = AtomicBoolean(false)

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
    
    /**
     * Check if device has active internet connection
     */
    private fun isOnline(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    
    /**
     * Show user warning about offline mode fallback to system TTS (similar to iOS behavior)
     */
    private fun showOfflineWarning() {
        if (offlineWarningShown.compareAndSet(false, true)) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context,
                    "No internet connection. Using device text-to-speech instead of Azure voices.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    /**
     * Show user warning about missing Azure configuration
     */
    private fun showConfigWarning() {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                context,
                "Azure configuration not found. Using device text-to-speech.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    
    /**
     * Show user warning about using default voice
     */
    private fun showDefaultVoiceWarning() {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                context,
                "Using default voice (Jenny Neural). Configure voices in settings for more options.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override suspend fun speak(text: String, voice: Voice?, pitch: Double?, rate: Double?) {
        // Check user preference for TTS engine first
        val koin = GlobalContext.getOrNull()
        val settingsRepo = koin?.let { runCatching { it.get<io.github.jdreioe.wingmate.domain.SettingsRepository>() }.getOrNull() }
        val uiSettings = settingsRepo?.let { runCatching { it.get() }.getOrNull() }
        
        // If user prefers system TTS, use it directly
        if (uiSettings?.useSystemTts == true) {
            speakWithPlatformTts(text, voice, pitch, rate)
            return
        }

        // Try to reuse a cached audio file from history to save API calls (works even offline)
        if (maybePlayFromHistoryCache(text, voice, pitch, rate, uiSettings?.primaryLanguage)) {
            return
        }
        
    // Before Azure: try history-first playback with TTL
    if (tryPlayFromHistoryIfFresh(text, voice)) return

    // Try to obtain persisted Azure config; if present, prefer Azure TTS pipeline
        val cfg = getConfig()
        if (cfg == null) {
            // No Azure configuration - fall back to system TTS with warning
            showConfigWarning()
            speakWithPlatformTts(text, voice, pitch, rate)
            return
        }
        
        // Check if we're online before attempting Azure TTS
        if (!isOnline()) {
            showOfflineWarning()
            // Fall back to system TTS when offline
            speakWithPlatformTts(text, voice, pitch, rate)
            return
        }
        
        withContext(Dispatchers.IO) {
            try {
                val v = voice ?: Voice(name = "en-US-JennyNeural", primaryLanguage = "en-US")
                
                // Show warning if no voice was provided (using default)
                if (voice == null || voice.name.isNullOrBlank()) {
                    showDefaultVoiceWarning()
                }

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
    }

    private fun effectiveVoice(
        base: Voice?,
        uiPrimaryLanguage: String?
    ): Voice {
        val v = base ?: Voice(name = "en-US-JennyNeural", primaryLanguage = "en-US")
        val effectiveLang = when {
            !v.selectedLanguage.isNullOrBlank() -> v.selectedLanguage
            !uiPrimaryLanguage.isNullOrBlank() -> uiPrimaryLanguage
            !v.primaryLanguage.isNullOrBlank() -> v.primaryLanguage
            else -> "en-US"
        }
        return v.copy(primaryLanguage = effectiveLang)
    }

    private suspend fun maybePlayFromHistoryCache(
        text: String,
        voice: Voice?,
        pitch: Double?,
        rate: Double?,
        uiPrimaryLanguage: String?
    ): Boolean {
        return runCatching {
            val koin = GlobalContext.getOrNull()
            val saidRepo = koin?.getOrNull<io.github.jdreioe.wingmate.domain.SaidTextRepository>()
            if (saidRepo == null) return false

            val v = effectiveVoice(voice, uiPrimaryLanguage)
            val list = runCatching { withContext(Dispatchers.IO) { saidRepo.list() } }.getOrNull().orEmpty()
            val candidate = list.asSequence()
                .filter { it.saidText == text }
                .filter { !it.audioFilePath.isNullOrBlank() }
                .filter { it.voiceName == v.name }
                .filter { (it.primaryLanguage ?: "") == (v.selectedLanguage?.ifBlank { v.primaryLanguage } ?: v.primaryLanguage ?: "") }
                .filter { (it.pitch == null && pitch == null) || (it.pitch != null && pitch != null && it.pitch == pitch) }
                .filter { (it.speed == null && rate == null) || (it.speed != null && rate != null && it.speed == rate) }
                .sortedByDescending { it.date ?: it.createdAt ?: 0L }
                .firstOrNull()

            val path = candidate?.audioFilePath
            if (path.isNullOrBlank()) return false

            val file = File(path)
            if (!file.exists() || file.length() <= 0L) return false

            // Append a history record for this playback referencing the cached file
            runCatching {
                val now = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    saidRepo.add(
                        io.github.jdreioe.wingmate.domain.SaidText(
                            date = now,
                            saidText = text,
                            voiceName = v.name,
                            pitch = pitch ?: v.pitch,
                            speed = rate ?: v.rate,
                            audioFilePath = file.absolutePath,
                            createdAt = now,
                            position = 0,
                            primaryLanguage = v.selectedLanguage?.ifBlank { v.primaryLanguage }
                        )
                    )
                }
            }

            // Play the cached file with MediaPlayer (mirror Azure playback path)
            val player = MediaPlayer()
            if (!requestAudioFocus()) {
                // Even if focus fails, try to play; but Android Auto may ignore without focus
            }
            player.setOnCompletionListener { mp ->
                try {
                    synchronized(playerLock) {
                        if (mediaPlayer === mp) {
                            mp.reset()
                            mp.release()
                            mediaPlayer = null
                        }
                    }
                    abandonAudioFocus()
                } catch (_: Throwable) {}
            }
            player.setOnErrorListener { mp, _, _ ->
                try { synchronized(playerLock) { if (mediaPlayer === mp) { mp.reset(); mp.release(); mediaPlayer = null } } } catch (_: Throwable) {}
                abandonAudioFocus()
                false
            }
            // Ensure proper routing for car/AA: use navigation guidance speech attributes
            try { player.setAudioAttributes(ttsAudioAttributes) } catch (_: Throwable) {}
            player.setDataSource(file.absolutePath)
            player.prepare()
            synchronized(playerLock) {
                mediaPlayer?.let { try { it.stop(); it.release() } catch (_: Throwable) {} }
                mediaPlayer = player
            }
            player.start()
            true
        }.getOrElse { false }
    }

    private suspend fun tryPlayFromHistoryIfFresh(text: String, voice: Voice?): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val koin = GlobalContext.getOrNull()
                val saidRepo = koin?.get<io.github.jdreioe.wingmate.domain.SaidTextRepository>()
                val items = saidRepo?.list().orEmpty()
                val now = System.currentTimeMillis()
                val v = voice
                val match = items
                    .asSequence()
                    .filter { it.saidText?.trim().equals(text.trim(), ignoreCase = false) }
                    .sortedByDescending { it.createdAt ?: it.date ?: 0L }
                    .firstOrNull { item ->
                        val langOk = when {
                            item.primaryLanguage.isNullOrBlank() -> true
                            v?.selectedLanguage?.isNotBlank() == true -> item.primaryLanguage == v.selectedLanguage
                            v?.primaryLanguage?.isNotBlank() == true -> item.primaryLanguage == v.primaryLanguage
                            else -> true
                        }
                        if (!langOk) return@firstOrNull false
                        val path = item.audioFilePath ?: return@firstOrNull false
                        val baseTime = item.createdAt ?: item.date ?: File(path).lastModified()
                        val age = now - baseTime
                        val fresh = age <= CACHE_TTL_MS
                        if (!fresh) runCatching { File(path).delete() }
                        fresh
                    }
                if (match != null) {
                    // Play file directly
                    val p = match.audioFilePath!!
                    val player = MediaPlayer()
                    player.setOnCompletionListener { mp ->
                        try {
                            synchronized(playerLock) {
                                if (mediaPlayer === mp) {
                                    mp.reset(); mp.release(); mediaPlayer = null
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                    player.setOnErrorListener { mp, _, _ ->
                        try { synchronized(playerLock) { if (mediaPlayer === mp) { mp.reset(); mp.release(); mediaPlayer = null } } } catch (_: Throwable) {}
                        false
                    }
                    player.setDataSource(p)
                    player.prepare()
                    synchronized(playerLock) {
                        mediaPlayer?.let { try { it.stop(); it.release() } catch (_: Throwable) {} }
                        mediaPlayer = player
                    }
                    player.start()
                    true
                } else false
            } catch (_: Throwable) { false }
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
            // Route TTS through car audio properly by setting AudioAttributes and requesting focus
            try { t.setAudioAttributes(ttsAudioAttributes) } catch (_: Throwable) {}
            requestAudioFocus()
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
    abandonAudioFocus()
        
        // Reset offline warning for next session
        offlineWarningShown.set(false)
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
