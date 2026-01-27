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
        
        // Combine all segments text for cache lookup and history
        val combinedText = segments.joinToString("") { it.text }

        // If user prefers system TTS, use it directly
        if (uiSettings?.useSystemTts == true) {
            recordHistory(combinedText, voice) // Record ONCE for the whole sentence
            playSegmentsWithPlatformTts(segments, voice, pitch, rate)
            return
        }

        // Try to reuse a cached audio file from history to save API calls (works even offline)
        if (maybePlayFromHistoryCache(combinedText, voice, pitch, rate, uiSettings?.primaryLanguage)) {
            return
        }

    // Try to obtain persisted Azure config; if present, prefer Azure TTS pipeline
        val cfg = getConfig()
        if (cfg == null) {
            // No Azure configuration - fall back to system TTS with warning
            showConfigWarning()
            recordHistory(combinedText, voice)
            speakWithPlatformTts(combinedText, voice, pitch, rate)
            return
        }
        
        // Check if we're online before attempting Azure TTS
        if (!isOnline()) {
            showOfflineWarning()
            recordHistory(combinedText, voice)
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
                
                // Fetch dictionary entries to apply
                val dict = runCatching { 
                    GlobalContext.get().get<io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository>().getAll() 
                }.getOrDefault(emptyList())

                val musicRoot = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
                val outDir = File(musicRoot, "wingmate/audio").apply { if (!exists()) mkdirs() }
                
                // Calculate hash early to check if file already exists
                val dictHash = dict.joinToString("|") { "${it.word}:${it.phoneme}:${it.alphabet}" }.hashCode()
                val cacheKey = "${combinedText}_${vForSsml.name}_${vForSsml.primaryLanguage}_${vForSsml.selectedLanguage}_${pitch}_${rate}_${dictHash}".hashCode()
                val fileName = "tts_$cacheKey.mp3"
                val outFile = File(outDir, fileName)

                if (outFile.exists() && outFile.length() > 0) {
                    // Cache hit! reuse the file without calling Azure
                    recordHistory(combinedText, vForSsml, outFile.absolutePath)
                    
                    // Simple playback logic (duplicated from below for now to keep flow linear)
                    startPlayback(outFile, vForSsml)
                    return@withContext
                }

                // Cache Miss - Proceed to Synthesis
                
                // Use segments-based SSML generation if ANY segment has language override OR pause
                val ssml = if (segments.any { !it.languageTag.isNullOrBlank() || it.pauseDurationMs > 0 }) {
                    AzureTtsClient.generateSsml(segments, vForSsml, dict)
                } else {
                    AzureTtsClient.generateSsml(combinedText, vForSsml, dict)
                }
                val bytes = AzureTtsClient.synthesize(client, ssml, cfg)

                // Persist to an app-private Music directory so history can reference it later
                
                // Use a stable hash for the filename to allow aggressive caching and reuse
                
                // If file already exists and is valid, skip writing (unless 0 bytes)
                if (!outFile.exists() || outFile.length() == 0L) {
                    outFile.outputStream().use { it.write(bytes) }
                }

                // Save history record
                recordHistory(combinedText, vForSsml, outFile.absolutePath)
                startPlayback(outFile, vForSsml)
            } catch (t: Throwable) {
                println("Azure TTS failed, falling back to platform TTS: $t")
                // Fallback to platform TTS on error
                recordHistory(combinedText, voice)
                speakWithPlatformTts(combinedText, voice, pitch, rate)
            }
        }
    }
    
    private fun startPlayback(file: File, voice: Voice) {
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
            } catch (_: Throwable) {}
        }
        player.setOnErrorListener { mp, _, _ ->
            try { synchronized(playerLock) { if (mediaPlayer === mp) { mp.reset(); mp.release(); mediaPlayer = null } } } catch (_: Throwable) {}
            false
        }
        try { player.setAudioAttributes(ttsAudioAttributes) } catch (_: Throwable) {}
        player.setDataSource(file.absolutePath)
        player.prepare()
        synchronized(playerLock) {
            mediaPlayer?.let { try { it.stop(); it.release() } catch (_: Throwable) {} }
            mediaPlayer = player
        }
        player.start()
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
            // Dictionary-aware caching:
            // To ensure we don't play stale audio when pronunciation rules change, we need to check if the
            // cached item was created with the *current* set of pronunciation rules.
            // Since we don't store rule hashes in the DB yet, we can use a heuristic:
            // 1. Only reuse history if it was created AFTER the last dictionary update (if we tracked that).
            // 2. OR, for now, simply re-enable cache but be aware it might persist old pronunciations until cleared.
            // A better approach for the future is to include a dictionary hash in the SaidText record.
            
            // For now, to satisfy the requirement of "fewest API calls", we re-enable it 
            // but we add a check: if the user JUST added a rule, they likely want to hear it.
            // We can't easily detect that here without more state. 
            // Let's re-enable the exact match logic.
            
            val candidate = list.asSequence()
                .filter { it.saidText == text }
                .filter { !it.audioFilePath.isNullOrBlank() }
                .filter { it.voiceName == v.name }
                // Stricter language matching
                .filter { 
                    val itemLang = it.primaryLanguage ?: ""
                    val voiceLang = v.selectedLanguage?.ifBlank { v.primaryLanguage } ?: v.primaryLanguage ?: ""
                    itemLang == voiceLang
                }
                // Stricter pitch/rate matching to avoid playing "normal" speed for "slow" request
                .filter { (it.pitch ?: 1.0) == (pitch ?: v.pitch ?: 1.0) }
                .filter { (it.speed ?: 1.0) == (rate ?: v.rate ?: 1.0) }
                .filter { item ->
                    val path = item.audioFilePath ?: return@filter false
                    val baseTime = item.createdAt ?: item.date ?: File(path).lastModified()
                    val age = System.currentTimeMillis() - baseTime
                    val fresh = age <= CACHE_TTL_MS
                    if (!fresh) runCatching { File(path).delete() }
                    fresh
                }
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


    private suspend fun speakWithPlatformTts(text: String, voice: Voice?, pitch: Double?, rate: Double?) {
        val cleanText = text.replace(Regex("<[^>]+>"), "") // Strip tags for system TTS fallback
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
            t.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "wingmate-${System.currentTimeMillis()}")
        }
    }

    /**
     * Internal helper to record speech history for all playback paths
     */
    private fun recordHistory(text: String, voice: Voice?, audioPath: String? = null) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val koin = GlobalContext.getOrNull()
                val saidRepo = koin?.get<io.github.jdreioe.wingmate.domain.SaidTextRepository>() ?: return@runCatching
                val now = System.currentTimeMillis()
                
                // Determine effective voice params for history
                val v = voice ?: Voice(name = "System", primaryLanguage = Locale.getDefault().toLanguageTag())
                
                saidRepo.add(
                    io.github.jdreioe.wingmate.domain.SaidText(
                        date = now,
                        saidText = text,
                        voiceName = v.name,
                        pitch = v.pitch,
                        speed = v.rate,
                        audioFilePath = audioPath,
                        createdAt = now,
                        position = 0,
                        primaryLanguage = v.selectedLanguage?.ifBlank { v.primaryLanguage } ?: v.primaryLanguage
                    )
                )
                println("DEBUG: Recorded history for: $text")
            }.onFailure { t ->
                println("DEBUG: Failed to record history: ${t.message}")
            }
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
        withContext(Dispatchers.Main) {
            val t = ensureTts()
            
            // Iterate segments to handle breaks correctly for platform TTS
            for ((index, segment) in segments.withIndex()) {
                if (!isPlaying || pausedAtSegment) break
                
                currentSegmentIndex = index
                
                // 1. Speak the text (if any)
                if (segment.text.isNotEmpty()) {
                    val segmentVoice = voice.applyLanguageOverride(segment.languageTag)
                    
                    // Apply voice params for this segment
                    val lang = segmentVoice?.primaryLanguage ?: Locale.getDefault().toLanguageTag()
                    val loc = Locale.forLanguageTag(lang)
                    if (t.language != loc) t.language = loc
                    t.setPitch((pitch ?: 1.0).toFloat())
                    t.setSpeechRate((rate ?: 1.0).toFloat())
                    
                    // Queue the speech
                    val params = android.os.Bundle()
                    params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "wingmate-${System.currentTimeMillis()}-$index")
                    
                    // Use QUEUE_ADD to chain segments seamlessly
                    val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    t.speak(segment.text, queueMode, params, "wingmate-${System.currentTimeMillis()}-$index")
                }
                
                // 2. Play silence for break (if any)
                if (segment.pauseDurationMs > 0) {
                    // playSilentUtterance is available API 21+ (Wingmate is min 24/26 usually)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        t.playSilentUtterance(
                            segment.pauseDurationMs, 
                            TextToSpeech.QUEUE_ADD, 
                            "silence-${System.currentTimeMillis()}-$index"
                        )
                    } else {
                        // Fallback using Thread.sleep is NOT safe on main thread, but pre-Lollipop is ancient.
                        // Just ignore or use playSilence (deprecated but works).
                        @Suppress("DEPRECATION")
                        t.playSilence(
                             segment.pauseDurationMs, 
                             TextToSpeech.QUEUE_ADD, 
                             null
                        )
                    }
                }
                
                // Note: We cannot easily delay() here because t.speak is asynchronous and non-blocking.
                // The queuing handles the timing. But for UI sync (highlighting), we're limited.
                // Since this method was previously using delay() which blocked the coroutine but not the TTS, 
                // we'll remove manual delay to let TTS handle timing properly.
            }
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
