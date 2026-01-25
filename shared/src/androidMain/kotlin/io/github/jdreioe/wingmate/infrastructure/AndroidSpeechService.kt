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
import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.SpeechTextProcessor
import io.github.jdreioe.wingmate.infrastructure.AzureTtsClient
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*
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
    
    // Enhanced state for segmented playback and resume
    private var currentSegments: List<SpeechSegment> = emptyList()
    private var currentSegmentIndex = 0
    private var currentVoice: Voice? = null
    private var currentPitch: Double? = null
    private var currentRate: Double? = null
    private var pausedAtSegment = false
    private var isPlaying = false
    private var isPaused = false

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
        // Process text to extract pause tags and create segments
        val segments = SpeechTextProcessor.processText(text)
        
        // Use the enhanced speakSegments function
        speakSegments(segments, voice, pitch, rate)
    }

    override suspend fun speakSegments(segments: List<SpeechSegment>, voice: Voice?, pitch: Double?, rate: Double?) {
        // Check user preference for TTS engine first
        val koin = GlobalContext.getOrNull()
        val settingsRepo = koin?.let { runCatching { it.get<io.github.jdreioe.wingmate.domain.SettingsRepository>() }.getOrNull() }
        val uiSettings = settingsRepo?.let { runCatching { it.get() }.getOrNull() }
        
        // Store current playback context for resume functionality
        currentSegments = segments
        currentSegmentIndex = 0
        currentVoice = voice
        currentPitch = pitch  
        currentRate = rate
        pausedAtSegment = false
        isPlaying = true
        isPaused = false
        
        // If user prefers system TTS, use it directly
        if (uiSettings?.useSystemTts == true) {
            playSegmentsWithPlatformTts(segments, voice, pitch, rate)
            return
        }

        // Combine all segments text for cache lookup
        val combinedText = segments.joinToString("") { it.text }

        // Try to reuse a cached audio file from history to save API calls (works even offline)
        if (maybePlayFromHistoryCache(combinedText, voice, pitch, rate, uiSettings?.primaryLanguage)) {
            return
        }
        
    // Before Azure: try history-first playback with TTL
    if (tryPlayFromHistoryIfFresh(combinedText, voice)) return

    // Try to obtain persisted Azure config; if present, prefer Azure TTS pipeline
        val cfg = getConfig()
        if (cfg == null) {
            // No Azure configuration - fall back to system TTS with warning
            showConfigWarning()
            speakWithPlatformTts(combinedText, voice, pitch, rate)
            return
        }
        
        // Check if we're online before attempting Azure TTS
        if (!isOnline()) {
            showOfflineWarning()
            // Fall back to system TTS when offline
            speakWithPlatformTts(combinedText, voice, pitch, rate)
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
                    v.selectedLanguage.isNotBlank() -> v.selectedLanguage
                    !uiSettings?.primaryLanguage.isNullOrBlank() -> uiSettings!!.primaryLanguage
                    !v.primaryLanguage.isNullOrBlank() -> v.primaryLanguage
                    else -> "en-US"
                } ?: "en-US"

                val vForSsml = v.copy(primaryLanguage = effectiveLang, selectedLanguage = effectiveLang)
                val ssml = if (segments.any { !it.languageTag.isNullOrBlank() }) {
                    AzureTtsClient.generateSsml(segments, vForSsml)
                } else {
                    AzureTtsClient.generateSsml(combinedText, vForSsml)
                }
                val bytes = AzureTtsClient.synthesize(client, ssml, cfg)

                // Persist to an app-private Music directory so history can reference it later
                val musicRoot = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
                val outDir = File(musicRoot, "wingmate/audio").apply { if (!exists()) mkdirs() }
                val safeName = combinedText.take(32).replace("[^A-Za-z0-9-_ ]".toRegex(), "_").trim().ifBlank { "utterance" }
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
                            saidText = combinedText,
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
                speakWithPlatformTts(combinedText, voice, pitch, rate)
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
        try { tts?.stop() } catch (_: Throwable) {}
        
        // Mark as paused for segmented playback
        isPaused = true
        pausedAtSegment = true
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
        
        // Reset state
        isPlaying = false
        isPaused = false
        pausedAtSegment = false
        currentSegments = emptyList()
        currentSegmentIndex = 0
        
        // Reset offline warning for next session
        offlineWarningShown.set(false)
    }

    override suspend fun resume() {
        if (isPaused && pausedAtSegment && currentSegments.isNotEmpty()) {
            isPaused = false
            pausedAtSegment = false
            // Resume playing segments from where we left off
            playSegmentsFromIndex(currentSegmentIndex)
        }
    }

    override fun isPlaying(): Boolean = isPlaying

    override fun isPaused(): Boolean = isPaused

    override suspend fun guessPronunciation(text: String, language: String): String? {
        val langCode = language.take(2).lowercase()
        return try {
            // Use Wiktionary API as a robust source for IPA
            val url = "https://en.wiktionary.org/w/api.php?action=query&titles=${text.trim()}&prop=revisions&rvprop=content&format=json"
            val response = client.get(url)
            
            if (response.status.value == 200) {
                val body = response.bodyAsText()
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                val root = json.parseToJsonElement(body).jsonObject
                
                val pages = root["query"]?.jsonObject?.get("pages")?.jsonObject
                if (pages == null || pages.isEmpty()) return null
                
                val pageKey = pages.keys.first()
                if (pageKey == "-1") return null
                
                val page = pages[pageKey]?.jsonObject
                val revisions = page?.get("revisions")?.jsonArray
                val content = revisions?.get(0)?.jsonObject?.get("*")?.jsonPrimitive?.content
                
                if (content != null) {
                    val regex = Regex("\\{\\{IPA\\|$langCode\\|/([^/]+)/")
                    val match = regex.find(content)
                    if (match != null) return match.groupValues[1]
                    
                    val regexBrackets = Regex("\\{\\{IPA\\|$langCode\\|\\[([^\\]]+)\\]")
                    val matchBrackets = regexBrackets.find(content)
                    if (matchBrackets != null) return matchBrackets.groupValues[1]
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun playSegmentsFromIndex(startIndex: Int) {
        val koin = GlobalContext.getOrNull()
        val settingsRepo = koin?.let { runCatching { it.get<io.github.jdreioe.wingmate.domain.SettingsRepository>() }.getOrNull() }
        val uiSettings = settingsRepo?.let { runCatching { it.get() }.getOrNull() }
        
        // If user prefers system TTS, use platform TTS approach
        if (uiSettings?.useSystemTts == true) {
            playSegmentsWithPlatformTts(currentSegments.drop(startIndex), currentVoice, currentPitch, currentRate)
        } else {
            // For Azure TTS: concatenate remaining segments and use main speak logic
            val remainingSegments = currentSegments.drop(startIndex)
            if (remainingSegments.isNotEmpty()) {
                val remainingText = remainingSegments.joinToString(" ") { it.text }
                // Call the original speakSegments logic but bypass the text processing
                playTextWithAzure(remainingText, currentVoice, currentPitch, currentRate)
            }
        }
        
        // Mark as finished if we completed all segments
        if (startIndex >= currentSegments.size - 1) {
            isPlaying = false
            currentSegments = emptyList()
            currentSegmentIndex = 0
        }
    }

    private suspend fun playTextWithAzure(text: String, voice: Voice?, pitch: Double?, rate: Double?) {
        // Use the existing Azure TTS logic from the original speakSegments method
        // This is a simplified version that just calls the platform TTS as fallback
        speakWithPlatformTts(text, voice, pitch, rate)
    }

    private suspend fun playSegmentsWithPlatformTts(segments: List<SpeechSegment>, voice: Voice?, pitch: Double?, rate: Double?) {
        for ((index, segment) in segments.withIndex()) {
            if (!isPlaying || pausedAtSegment) break
            
            currentSegmentIndex = index
            
            if (segment.text.isNotEmpty()) {
                val segmentVoice = voice.applyLanguageOverride(segment.languageTag)
                speakWithPlatformTts(segment.text, segmentVoice, pitch, rate)
                
                // Wait for TTS to finish (simplified approach)
                kotlinx.coroutines.delay(segment.text.length * 50L) // rough estimate
            }
            
            // Apply pause if specified
            if (segment.pauseDurationMs > 0 && !pausedAtSegment) {
                kotlinx.coroutines.delay(segment.pauseDurationMs)
            }
        }
        
        // Mark as finished
        if (currentSegmentIndex >= segments.size - 1) {
            isPlaying = false
            currentSegments = emptyList()
            currentSegmentIndex = 0
        }
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

    private fun Voice?.applyLanguageOverride(languageTag: String?): Voice? {
        if (languageTag.isNullOrBlank()) return this
        val base = this ?: Voice(name = "en-US-JennyNeural", primaryLanguage = languageTag, selectedLanguage = languageTag)
        return base.copy(
            selectedLanguage = languageTag,
            primaryLanguage = languageTag
        )
    }
}
