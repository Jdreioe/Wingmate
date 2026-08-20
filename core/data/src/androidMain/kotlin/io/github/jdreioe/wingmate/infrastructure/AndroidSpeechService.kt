package io.github.jdreioe.wingmate.infrastructure

import android.Manifest
import android.content.Context
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.SpeechPlaybackState
import io.github.jdreioe.wingmate.domain.SpeechPlaybackStatus
import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.VoiceProvider
import io.github.jdreioe.wingmate.domain.resolvedProvider
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.domain.GoogleSpeechConfig
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.SpeechTextProcessor
import io.github.jdreioe.wingmate.domain.loggingClassName
import io.github.jdreioe.wingmate.infrastructure.AzureTtsClient
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*
import io.ktor.client.engine.okhttp.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.koin.core.context.GlobalContext
import java.io.File
import android.os.Environment
import androidx.annotation.RequiresPermission
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * AndroidSpeechService prefers using Azure TTS when a persisted SpeechServiceConfig exists.
 * It synthesizes to memory, writes an MP3 temp file and plays it with MediaPlayer.
 * Falls back to platform TextToSpeech when no Azure config is available.
 */
class AndroidSpeechService(
    private val context: Context,
    private val googleApiRequestHeaders: GoogleApiRequestHeaders,
) : SpeechService {
    private companion object {
        const val DEFAULT_AZURE_VOICE_NAME = "en-US-JennyNeural"
        const val DEFAULT_AZURE_LANGUAGE = "en-US"
    }

    private val client = HttpClient(OkHttp) {
        followRedirects = false
    }
    // Removed SLF4J logger for cross-platform compatibility

    // Platform TTS (fallback)
    private var tts: TextToSpeech? = null
    private var ttsInitialization: CompletableDeferred<Boolean>? = null
    private var activeTtsEnginePackage: String? = null

    // MediaPlayer based playback for Azure synthesized audio
    private var mediaPlayer: MediaPlayer? = null
    private val playerLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private data class PendingCache(val text: String, val voice: Voice?, val pitch: Double?, val rate: Double?)
    private val pendingSpeechCache = ConcurrentHashMap.newKeySet<PendingCache>()
    private val audioManager: AudioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    init {
        runCatching {
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch(Dispatchers.IO) { retryPendingSpeechCache() }
                }
            })
        }
    }
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        // For simplicity: on full loss, stop playback; on other changes we ignore/duck handled by system
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            scope.launch { runCatching { stop() } }
        }
    }
    private val ttsAudioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            // Android Auto routes generic app playback most reliably on media usage.
            .setUsage(AudioAttributes.USAGE_MEDIA)
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
    @Volatile private var isPlaying = false
    @Volatile private var isPaused = false
    private val requestGeneration = AtomicLong(0)
    @Volatile private var activeRequestJob: Job? = null
    @Volatile private var state = SpeechPlaybackState()
    private val pendingTtsUtterances = AtomicInteger(0)
    private val mediaSession: MediaSession by lazy {
        MediaSession(context, "WingmateSpeechSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPause() {
                    scope.launch { runCatching { pause() } }
                }

                override fun onPlay() {
                    scope.launch { runCatching { resume() } }
                }

                override fun onStop() {
                    scope.launch { runCatching { stop() } }
                }
            })
            isActive = true
            setPlaybackState(buildPlaybackState(PlaybackState.STATE_NONE))
        }
    }

    private fun buildPlaybackState(state: Int): PlaybackState {
        val actions =
            PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP
        return PlaybackState.Builder()
            .setActions(actions)
            .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f, System.currentTimeMillis())
            .build()
    }

    private fun updatePlaybackState(state: Int) {
        runCatching {
            mediaSession.isActive = state != PlaybackState.STATE_NONE
            mediaSession.setPlaybackState(buildPlaybackState(state))
        }
    }

    private fun updateNowPlaying(text: String?, voice: Voice?) {
        runCatching {
            val title = text?.trim()?.takeIf { it.isNotEmpty() }?.take(80) ?: "Wingmate Speech"
            val speaker = voice?.displayName?.takeIf { it.isNotBlank() }
                ?: voice?.name?.takeIf { it.isNotBlank() }
                ?: "Speech"
            val metadata = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, speaker)
                .build()
            mediaSession.setMetadata(metadata)
        }
    }

    private fun markPlaybackStarted(text: String?, voice: Voice?, requestId: Long = requestGeneration.get()) {
        if (requestGeneration.get() != requestId) return
        isPlaying = true
        isPaused = false
        state = SpeechPlaybackState(requestId, SpeechPlaybackStatus.PLAYING)
        updateNowPlaying(text, voice)
        updatePlaybackState(PlaybackState.STATE_PLAYING)
    }

    private fun finishPlayback(requestId: Long = requestGeneration.get()) {
        if (requestGeneration.get() != requestId) return
        isPlaying = false
        isPaused = false
        state = SpeechPlaybackState(requestId, SpeechPlaybackStatus.IDLE)
        pausedAtSegment = false
        updatePlaybackState(PlaybackState.STATE_STOPPED)
        abandonAudioFocus()
    }

    private fun onTtsUtteranceFinished(utteranceId: String?) {
        val requestId = utteranceId?.substringAfter("wingmate-", "")
            ?.substringBefore('-')?.toLongOrNull() ?: return
        if (requestGeneration.get() != requestId) return
        val remaining = pendingTtsUtterances.updateAndGet { count -> if (count > 0) count - 1 else 0 }
        if (remaining == 0) {
            finishPlayback(requestId)
        }
    }

    private suspend fun ensureTts(enginePackageName: String? = null): TextToSpeech = withContext(Dispatchers.Main) {
        val normalizedEngine = enginePackageName?.takeIf { it.isNotBlank() }
        val cur = tts
        if (cur != null && activeTtsEnginePackage == normalizedEngine) {
            val ready = ttsInitialization?.let { awaitSpeechInitialization(it, 5_000) } == true
            check(ready) { "Device text-to-speech did not become ready" }
            return@withContext cur
        }

        if (cur != null) {
            try {
                cur.stop()
            } catch (_: Throwable) {
                
            }
            try {
                cur.shutdown()
            } catch (_: Throwable) {
                
            }
            tts = null
        }

        val initialization = CompletableDeferred<Boolean>()
        ttsInitialization = initialization
        val created = if (normalizedEngine.isNullOrBlank()) {
            TextToSpeech(context) { status ->
                initialization.complete(status == TextToSpeech.SUCCESS)
            }
        } else {
            TextToSpeech(context, { status ->
                initialization.complete(status == TextToSpeech.SUCCESS)
            }, normalizedEngine)
        }

        created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val requestId = utteranceId?.substringAfter("wingmate-", "")
                    ?.substringBefore('-')?.toLongOrNull()
                if (requestId == requestGeneration.get()) {
                    updatePlaybackState(PlaybackState.STATE_PLAYING)
                }
            }

            override fun onDone(utteranceId: String?) {
                onTtsUtteranceFinished(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                onTtsUtteranceFinished(utteranceId)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onTtsUtteranceFinished(utteranceId)
            }
        })

        tts = created
        activeTtsEnginePackage = normalizedEngine
        val ready = awaitSpeechInitialization(initialization, 5_000)
        if (!ready) {
            if (tts === created) {
                tts = null
                activeTtsEnginePackage = null
                ttsInitialization = null
            }
            runCatching { created.shutdown() }
            error("Device text-to-speech failed to initialize")
        }
        created
    }

    private suspend fun beginRequest(): Long {
        val job = currentCoroutineContext()[Job]
        activeRequestJob?.takeIf { it !== job }?.cancel()
        activeRequestJob = job
        val requestId = requestGeneration.incrementAndGet()
        stopNativePlayback()
        state = SpeechPlaybackState(requestId, SpeechPlaybackStatus.PREPARING)
        return requestId
    }

    private suspend fun <T> executeRequest(requestId: Long, block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        if (requestGeneration.get() == requestId) {
            stopNativePlayback()
            state = SpeechPlaybackState(requestId, SpeechPlaybackStatus.IDLE)
        }
        throw cancelled
    } catch (error: Throwable) {
        if (requestGeneration.get() == requestId) {
            stopNativePlayback()
            state = SpeechPlaybackState(requestId, SpeechPlaybackStatus.FAILED, "Speech could not be played")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Speech could not be played. Please try again.", Toast.LENGTH_LONG).show()
            }
        }
        throw error
    }
    
    /**
     * Check if device has active internet connection
     */
    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
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
        speakWithCachePolicy(text, voice, pitch, rate, cacheAudio = true)
    }

    override suspend fun speakWithCachePolicy(text: String, voice: Voice?, pitch: Double?, rate: Double?, cacheAudio: Boolean) {
        val requestId = beginRequest()
        executeRequest(requestId) {
            val segments = SpeechTextProcessor.processText(text)
            speakSegmentsInternal(requestId, segments, voice, pitch, rate, cacheAudio)
        }
    }

    override suspend fun speakSegments(segments: List<SpeechSegment>, voice: Voice?, pitch: Double?, rate: Double?) {
        val requestId = beginRequest()
        executeRequest(requestId) {
            speakSegmentsInternal(requestId, segments, voice, pitch, rate, cacheAudio = true)
        }
    }

    override suspend fun speakSegmentsWithCachePolicy(segments: List<SpeechSegment>, voice: Voice?, pitch: Double?, rate: Double?, cacheAudio: Boolean) {
        val requestId = beginRequest()
        executeRequest(requestId) {
            speakSegmentsInternal(requestId, segments, voice, pitch, rate, cacheAudio)
        }
    }

    override suspend fun cacheSpeech(text: String, voice: Voice?, pitch: Double?, rate: Double?): Boolean {
        val normalizedText = SpeechTextProcessor.normalizeShorthandSsml(text).trim()
        if (normalizedText.isEmpty()) return true
        val request = PendingCache(normalizedText, voice, pitch, rate)
        val settingsRepo = GlobalContext.getOrNull()?.let {
            runCatching { it.get<io.github.jdreioe.wingmate.domain.SettingsRepository>() }.getOrNull()
        }
        val settings = settingsRepo?.let { runCatching { it.get() }.getOrNull() }
        val engine = settings?.ttsEngine ?: TtsEngine.SYSTEM
        if (engine == TtsEngine.SYSTEM) return false
        if (!isOnline()) {
            pendingSpeechCache += request
            return false
        }

        val cached = runCatching {
            val azureConfig = if (engine == TtsEngine.GOOGLE_CLOUD) null else getConfig() ?: return@runCatching false
            val googleConfig = if (engine == TtsEngine.GOOGLE_CLOUD) getGoogleConfig() ?: return@runCatching false else null
            val effectiveVoice = if (engine == TtsEngine.GOOGLE_CLOUD) {
                normalizeVoiceForGoogle(voice, settings?.primaryLanguage)
            } else {
                normalizeVoiceForAzure(voice)
            }
            val language = effectiveVoice.selectedLanguage.takeIf(String::isNotBlank)
                ?: settings?.primaryLanguage?.takeIf(String::isNotBlank)
                ?: effectiveVoice.primaryLanguage?.takeIf(String::isNotBlank)
                ?: DEFAULT_AZURE_LANGUAGE
            val voiceForSsml = effectiveVoice.copy(primaryLanguage = language, selectedLanguage = language)
            val dictionary = runCatching {
                GlobalContext.get().get<io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository>().getAll()
            }.getOrDefault(emptyList())
            val dictionaryIdentity = dictionary.joinToString("\u001f") { "${it.word}\u001e${it.phoneme}\u001e${it.alphabet}" }
            val cacheKey = SpeechCacheIdentity.digest(
                normalizedText,
                engine.name,
                voiceForSsml.name,
                voiceForSsml.primaryLanguage,
                voiceForSsml.selectedLanguage,
                pitch?.toString(),
                rate?.toString(),
                voiceForSsml.mathMode.toString(),
                dictionaryIdentity,
            )
            val root = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
            val directory = File(root, "wingmate/audio").apply { mkdirs() }
            val file = File(directory, "tts_v2_$cacheKey.mp3")
                if (!file.exists() || file.length() == 0L) {
                    val segments = SpeechTextProcessor.processText(normalizedText)
                    val bytes = if (engine == TtsEngine.GOOGLE_CLOUD) {
                        GoogleTtsClient.synthesizeSegments(
                            client,
                            segments,
                            voiceForSsml,
                            googleConfig!!,
                            pitch,
                            rate,
                            applicationHeaders = googleApiRequestHeaders,
                        )
                    } else {
                        val ssml = if (segments.any { !it.languageTag.isNullOrBlank() || it.pauseDurationMs > 0 }) {
                            AzureTtsClient.generateSsml(segments, voiceForSsml, dictionary)
                        } else {
                            AzureTtsClient.generateSsml(normalizedText, voiceForSsml, dictionary)
                        }
                        AzureTtsClient.synthesize(client, ssml, azureConfig!!)
                    }
                    file.outputStream().use { it.write(bytes) }
                }
            true
        }.getOrDefault(false)

        if (cached) pendingSpeechCache.remove(request) else pendingSpeechCache += request
        return cached
    }

    override suspend fun retryPendingSpeechCache() {
        if (!isOnline()) return
        pendingSpeechCache.toList().forEach { cacheSpeech(it.text, it.voice, it.pitch, it.rate) }
    }

    private suspend fun speakSegmentsInternal(requestId: Long, segments: List<SpeechSegment>, voice: Voice?, pitch: Double?, rate: Double?, cacheAudio: Boolean) {
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
        val engine = uiSettings?.ttsEngine ?: TtsEngine.SYSTEM
        if (engine == TtsEngine.SYSTEM) {
            playSegmentsWithPlatformTts(requestId, segments, voice, pitch, rate)
            recordHistory(combinedText, voice)
            return
        }

        // Try to reuse a cached audio file from history to save API calls (works even offline)
        if (cacheAudio && maybePlayFromHistoryCache(requestId, combinedText, voice, pitch, rate, uiSettings?.primaryLanguage)) {
            return
        }

        val azureConfig = if (engine == TtsEngine.GOOGLE_CLOUD) null else getConfig()
        val googleConfig = if (engine == TtsEngine.GOOGLE_CLOUD) getGoogleConfig() else null
        if (azureConfig == null && googleConfig == null) {
            // No credential for the selected provider - keep communication working via system TTS.
            showConfigWarning()
            speakWithPlatformTts(requestId, combinedText, voice, pitch, rate)
            recordHistory(combinedText, voice)
            return
        }
        
        // Check if we're online before attempting cloud TTS
        if (!isOnline()) {
            showOfflineWarning()
            // Fall back to system TTS when offline
            speakWithPlatformTts(requestId, combinedText, voice, pitch, rate)
            recordHistory(combinedText, voice)
            return
        }
        
        withContext(Dispatchers.IO) {
            try {
                val useDefaultAzureVoice = engine != TtsEngine.GOOGLE_CLOUD && shouldUseDefaultAzureVoice(voice)
                val v = if (engine == TtsEngine.GOOGLE_CLOUD) {
                    normalizeVoiceForGoogle(voice, uiSettings?.primaryLanguage)
                } else {
                    normalizeVoiceForAzure(voice)
                }
                
                // Show warning if no Azure voice was available and we had to fallback.
                if (useDefaultAzureVoice) {
                    showDefaultVoiceWarning()
                }

                // Determine effective language similar to desktop implementation
                val koin = GlobalContext.getOrNull()
                val settingsRepo = koin?.let { runCatching { it.get<io.github.jdreioe.wingmate.domain.SettingsRepository>() }.getOrNull() }
                val uiSettings = settingsRepo?.let { runCatching { it.get() }.getOrNull() }
                val effectiveLang =
                    v.selectedLanguage.takeIf(String::isNotBlank)
                        ?: uiSettings?.primaryLanguage?.takeIf(String::isNotBlank)
                        ?: v.primaryLanguage?.takeIf(String::isNotBlank)
                        ?: "en-US"

                val vForSsml = v.copy(primaryLanguage = effectiveLang, selectedLanguage = effectiveLang)
                
                // Fetch dictionary entries to apply
                val dict = runCatching { 
                    GlobalContext.get().get<io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository>().getAll() 
                }.getOrDefault(emptyList())

                val musicRoot = if (cacheAudio) {
                    context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
                } else {
                    context.cacheDir
                }
                val outDir = File(musicRoot, "wingmate/audio").apply { if (!exists()) mkdirs() }
                
                // Calculate hash early to check if file already exists
                val dictionaryIdentity = dict.joinToString("\u001f") { "${it.word}\u001e${it.phoneme}\u001e${it.alphabet}" }
                val cacheKey = SpeechCacheIdentity.digest(
                    combinedText,
                    engine.name,
                    vForSsml.name,
                    vForSsml.primaryLanguage,
                    vForSsml.selectedLanguage,
                    pitch?.toString(),
                    rate?.toString(),
                    vForSsml.mathMode.toString(),
                    dictionaryIdentity,
                )
                val fileName = if (cacheAudio) "tts_v2_$cacheKey.mp3" else "tts_session_${System.nanoTime()}.mp3"
                val outFile = File(outDir, fileName)

                if (cacheAudio && outFile.exists() && outFile.length() > 0) {
                    // Cache hit! reuse the file without calling Azure
                    startPlayback(requestId, outFile, vForSsml)
                    recordHistory(combinedText, vForSsml, outFile.absolutePath)
                    return@withContext
                }

                // Cache Miss - Proceed to Synthesis
                
                val bytes = if (engine == TtsEngine.GOOGLE_CLOUD) {
                    GoogleTtsClient.synthesizeSegments(
                        client,
                        segments,
                        vForSsml,
                        googleConfig!!,
                        pitch,
                        rate,
                        applicationHeaders = googleApiRequestHeaders,
                    )
                } else {
                    // Use segments-based SSML generation if ANY segment has language override OR pause
                    val ssml = if (segments.any { !it.languageTag.isNullOrBlank() || it.pauseDurationMs > 0 }) {
                        AzureTtsClient.generateSsml(segments, vForSsml, dict)
                    } else {
                        AzureTtsClient.generateSsml(combinedText, vForSsml, dict)
                    }
                    AzureTtsClient.synthesize(client, ssml, azureConfig!!)
                }

                // Persist to an app-private Music directory so history can reference it later
                
                // Use a stable hash for the filename to allow aggressive caching and reuse
                
                // If file already exists and is valid, skip writing (unless 0 bytes)
                if (!outFile.exists() || outFile.length() == 0L) {
                    outFile.outputStream().use { it.write(bytes) }
                }

                startPlayback(requestId, outFile, vForSsml, deleteAfterPlayback = !cacheAudio)
                recordHistory(combinedText, vForSsml, outFile.absolutePath.takeIf { cacheAudio })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (engine == TtsEngine.GOOGLE_CLOUD) {
                    OperationalLogger.warn(
                        operation = "speech.google_synthesis",
                        outcome = "platform_fallback",
                        exceptionClass = t.loggingClassName(),
                    )
                } else {
                    OperationalLogger.warn(
                        operation = "speech.azure_synthesis",
                        outcome = "platform_fallback",
                        exceptionClass = t.loggingClassName(),
                    )
                }
                // Fallback to platform TTS on error
                if (requestGeneration.get() != requestId) throw CancellationException("Speech request was replaced")
                speakWithPlatformTts(requestId, combinedText, voice, pitch, rate)
                recordHistory(combinedText, voice)
            }
        }
    }

    override suspend fun speakRecordedAudio(audioFilePath: String, textForHistory: String?, voice: Voice?): Boolean {
        val file = File(audioFilePath)
        if (!file.exists() || file.length() <= 0L) return false
        val requestId = beginRequest()

        val played = runCatching {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val player = MediaPlayer()
                    var finished = false

                    fun finalizePlayback(success: Boolean) {
                        if (finished) return
                        finished = true
                        try {
                            synchronized(playerLock) {
                                if (mediaPlayer === player) {
                                    try { player.reset() } catch (_: Throwable) {}
                                    try { player.release() } catch (_: Throwable) {}
                                    mediaPlayer = null
                                }
                            }
                        } finally {
                            finishPlayback(requestId)
                            if (cont.isActive) cont.resume(success)
                        }
                    }

                    requestAudioFocus()
                    player.setOnCompletionListener {
                        finalizePlayback(true)
                    }
                    player.setOnErrorListener { _, _, _ ->
                        finalizePlayback(false)
                        true
                    }

                    cont.invokeOnCancellation {
                        if (finished) return@invokeOnCancellation
                        finished = true
                        try {
                            synchronized(playerLock) {
                                if (mediaPlayer === player) {
                                    try { player.stop() } catch (_: Throwable) {}
                                    try { player.reset() } catch (_: Throwable) {}
                                    try { player.release() } catch (_: Throwable) {}
                                    mediaPlayer = null
                                }
                            }
                        } finally {
                            isPlaying = false
                            isPaused = false
                            abandonAudioFocus()
                        }
                    }

                    try {
                        updateNowPlaying(textForHistory ?: file.nameWithoutExtension, voice)
                        try { player.setAudioAttributes(ttsAudioAttributes) } catch (_: Throwable) {}
                        player.setDataSource(file.absolutePath)
                        player.prepare()
                        synchronized(playerLock) {
                            check(requestGeneration.get() == requestId) { "Speech request was replaced" }
                            mediaPlayer?.let { try { it.stop(); it.release() } catch (_: Throwable) {} }
                            mediaPlayer = player
                            markPlaybackStarted(textForHistory ?: file.nameWithoutExtension, voice, requestId)
                            player.start()
                        }
                    } catch (_: Throwable) {
                        finalizePlayback(false)
                    }
                }
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            finishPlayback(requestId)
            false
        }

        if (played) {
            textForHistory?.takeIf { it.isNotBlank() }?.let {
                recordHistory(it, voice, file.absolutePath)
            }
        }

        return played
    }
    
    private fun startPlayback(requestId: Long, file: File, voice: Voice, deleteAfterPlayback: Boolean = false) {
        check(requestGeneration.get() == requestId) { "Speech request was replaced" }
        val player = MediaPlayer()
                requestAudioFocus()

        player.setOnCompletionListener { mp ->
            try {
                synchronized(playerLock) {
                    if (mediaPlayer === mp) {
                        mp.reset()
                        mp.release()
                        mediaPlayer = null
                    }
                }
                finishPlayback(requestId)
                if (deleteAfterPlayback) runCatching { file.delete() }
            } catch (_: Throwable) {}
        }
        player.setOnErrorListener { mp, _, _ ->
            try { synchronized(playerLock) { if (mediaPlayer === mp) { mp.reset(); mp.release(); mediaPlayer = null } } } catch (_: Throwable) {}
            if (deleteAfterPlayback) runCatching { file.delete() }
            finishPlayback(requestId)
            true
        }
        try { player.setAudioAttributes(ttsAudioAttributes) } catch (_: Throwable) {}
        player.setDataSource(file.absolutePath)
        player.prepare()
        synchronized(playerLock) {
            check(requestGeneration.get() == requestId) { "Speech request was replaced" }
            mediaPlayer?.let { try { it.stop(); it.release() } catch (_: Throwable) {} }
            mediaPlayer = player
            markPlaybackStarted(file.nameWithoutExtension, voice, requestId)
            player.start()
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
        requestId: Long,
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
                // Stricter language matching
                .filter { 
                    val itemLang = it.primaryLanguage ?: ""
                    val voiceLang = v.selectedLanguage.ifBlank { v.primaryLanguage.orEmpty() }
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
            if (!file.name.matches(Regex("tts_v2_[0-9a-f]{64}\\.mp3"))) return false

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
                    finishPlayback(requestId)
                } catch (_: Throwable) {}
            }
            player.setOnErrorListener { mp, _, _ ->
                try { synchronized(playerLock) { if (mediaPlayer === mp) { mp.reset(); mp.release(); mediaPlayer = null } } } catch (_: Throwable) {}
                finishPlayback(requestId)
                true
            }
            // Ensure proper routing for car/AA through media usage speech attributes.
            try { player.setAudioAttributes(ttsAudioAttributes) } catch (_: Throwable) {}
            try {
                player.setDataSource(file.absolutePath)
                player.prepare()
                synchronized(playerLock) {
                    check(requestGeneration.get() == requestId) { "Speech request was replaced" }
                    mediaPlayer?.let { try { it.stop(); it.release() } catch (_: Throwable) {} }
                    mediaPlayer = player
                    markPlaybackStarted(text, v, requestId)
                    player.start()
                }
                recordHistory(text, v, file.absolutePath)
                true
            } catch (_: Throwable) {
                try { player.release() } catch (_: Throwable) {}
                finishPlayback(requestId)
                false
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            false
        }
    }


    private suspend fun speakWithPlatformTts(requestId: Long, text: String, voice: Voice?, pitch: Double?, rate: Double?) {
        val cleanText = text.replace(Regex("<[^>]+>"), "") // Strip tags for system TTS fallback
        withContext(Dispatchers.Main) {
            val parsedVoiceId = AndroidTtsVoiceId.parse(voice?.name)
            val t = ensureTts(parsedVoiceId.enginePackageName)
            val lang = voice?.primaryLanguage ?: Locale.getDefault().toLanguageTag()
            applyPlatformVoiceSettings(
                ttsEngine = t,
                languageTag = lang,
                requestedVoiceName = parsedVoiceId.voiceName,
            )
            t.setPitch((pitch ?: 1.0).toFloat())
            t.setSpeechRate((rate ?: 1.0).toFloat())
            // Route TTS through media channel to keep Android Auto routing consistent.
            try { t.setAudioAttributes(ttsAudioAttributes) } catch (_: Throwable) {}
            requestAudioFocus()
            val utteranceId = "wingmate-$requestId-${System.nanoTime()}"
            pendingTtsUtterances.set(1)
            val result = synchronized(playerLock) {
                check(requestGeneration.get() == requestId) { "Speech request was replaced" }
                t.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            }
            if (result != TextToSpeech.SUCCESS) {
                pendingTtsUtterances.set(0)
                finishPlayback(requestId)
                error("Device text-to-speech rejected playback")
            }
            markPlaybackStarted(cleanText, voice, requestId)
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
                val visibleInHistory = koin.getOrNull<io.github.jdreioe.wingmate.domain.SettingsRepository>()
                    ?.get()?.historyVisible ?: true
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
                        primaryLanguage = v.selectedLanguage.takeIf(String::isNotBlank) ?: v.primaryLanguage,
                        visibleInHistory = visibleInHistory
                    )
                )
            }.onFailure { t ->
                OperationalLogger.warn(
                    operation = "speech_history.record",
                    outcome = "failed",
                    exceptionClass = t.loggingClassName(),
                )
            }
        }
    }

    override suspend fun pause() {
        if (!isPlaying) return
        // Pause MediaPlayer if used; for platform TTS simulate pause with stop()
        synchronized(playerLock) {
            mediaPlayer?.let { if (it.isPlaying) it.pause() }
        }
        try { tts?.stop() } catch (_: Throwable) {}
        pendingTtsUtterances.set(0)
        
        // Mark as paused for segmented playback
        isPaused = true
        pausedAtSegment = true
        isPlaying = false
        state = SpeechPlaybackState(requestGeneration.get(), SpeechPlaybackStatus.PAUSED)
        updatePlaybackState(PlaybackState.STATE_PAUSED)
        abandonAudioFocus()
    }

    override suspend fun stop() {
        val requestId = requestGeneration.incrementAndGet()
        activeRequestJob?.cancel()
        activeRequestJob = null
        stopNativePlayback()
        state = SpeechPlaybackState(requestId, SpeechPlaybackStatus.IDLE)
    }

    private fun stopNativePlayback() {
        synchronized(playerLock) {
            try {
                mediaPlayer?.let { try { it.stop(); it.release() } catch (_: Throwable) {} }
            } finally {
                mediaPlayer = null
            }
            try { tts?.stop() } catch (_: Throwable) {}
        }
        pendingTtsUtterances.set(0)
        abandonAudioFocus()
        
        // Reset state
        isPlaying = false
        isPaused = false
        pausedAtSegment = false
        currentSegments = emptyList()
        currentSegmentIndex = 0
        updatePlaybackState(PlaybackState.STATE_STOPPED)
        
        // Reset offline warning for next session
        offlineWarningShown.set(false)
    }

    override suspend fun resume() {
        if (isPaused && pausedAtSegment && currentSegments.isNotEmpty()) {
            isPaused = false
            pausedAtSegment = false
            // Resume playing segments from where we left off
            state = SpeechPlaybackState(requestGeneration.get(), SpeechPlaybackStatus.PREPARING)
            playSegmentsFromIndex(requestGeneration.get(), currentSegmentIndex)
        }
    }

    override fun isPlaying(): Boolean = isPlaying

    override fun isPaused(): Boolean = isPaused

    override fun playbackState(): SpeechPlaybackState = state

    override suspend fun guessPronunciation(text: String, language: String): String? {
        val langCode = language.take(2).lowercase()
        return try {
            suspend fun lookup(edition: String, requireLanguageTag: Boolean): String? {
                val response = client.get("https://$edition.wiktionary.org/w/api.php") {
                    url { parameters.append("action", "query"); parameters.append("titles", text.trim()); parameters.append("prop", "revisions"); parameters.append("rvprop", "content"); parameters.append("format", "json") }
                }
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
                    val regex = if (requireLanguageTag) Regex("\\{\\{IPA\\|$langCode\\|/([^/]+)/") else Regex("\\{\\{IPA\\|(?:$langCode\\|)?/([^/]+)/")
                    val match = regex.find(content)
                    if (match != null) return match.groupValues[1]
                    
                    val regexBrackets = Regex("\\{\\{IPA\\|$langCode\\|\\[([^\\]]+)\\]")
                    val matchBrackets = regexBrackets.find(content)
                    if (matchBrackets != null) return matchBrackets.groupValues[1]
                }
                }
                return null
            }
            lookup(langCode, requireLanguageTag = false) ?: if (langCode != "en") lookup("en", requireLanguageTag = true) else null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun playSegmentsFromIndex(requestId: Long, startIndex: Int) {
        val koin = GlobalContext.getOrNull()
        val settingsRepo = koin?.let { runCatching { it.get<io.github.jdreioe.wingmate.domain.SettingsRepository>() }.getOrNull() }
        val uiSettings = settingsRepo?.let { runCatching { it.get() }.getOrNull() }
        
        // If user prefers system TTS, use platform TTS approach
        if (uiSettings?.ttsEngine == io.github.jdreioe.wingmate.domain.TtsEngine.SYSTEM) {
            playSegmentsWithPlatformTts(requestId, currentSegments.drop(startIndex), currentVoice, currentPitch, currentRate)
        } else {
            // For Azure TTS: concatenate remaining segments and use main speak logic
            val remainingSegments = currentSegments.drop(startIndex)
            if (remainingSegments.isNotEmpty()) {
                val remainingText = remainingSegments.joinToString(" ") { it.text }
                // Call the original speakSegments logic but bypass the text processing
                playTextWithAzure(requestId, remainingText, currentVoice, currentPitch, currentRate)
            }
        }
        
        // Mark as finished if we completed all segments
        if (startIndex >= currentSegments.size - 1) {
            isPlaying = false
            currentSegments = emptyList()
            currentSegmentIndex = 0
            updatePlaybackState(PlaybackState.STATE_STOPPED)
        }
    }

    private suspend fun playTextWithAzure(requestId: Long, text: String, voice: Voice?, pitch: Double?, rate: Double?) {
        // Use the existing Azure TTS logic from the original speakSegments method
        // This is a simplified version that just calls the platform TTS as fallback
        speakWithPlatformTts(requestId, text, voice, pitch, rate)
    }

    private suspend fun playSegmentsWithPlatformTts(requestId: Long, segments: List<SpeechSegment>, voice: Voice?, pitch: Double?, rate: Double?) {
        withContext(Dispatchers.Main) {
            val parsedVoiceId = AndroidTtsVoiceId.parse(voice?.name)
            val t = ensureTts(parsedVoiceId.enginePackageName)
            try { t.setAudioAttributes(ttsAudioAttributes) } catch (_: Throwable) {}
            requestAudioFocus()

            val combinedText = segments.joinToString(" ") { it.text }.trim()
            pendingTtsUtterances.set(0)
            check(requestGeneration.get() == requestId) { "Speech request was replaced" }
            
            // Iterate segments to handle breaks correctly for platform TTS
            for ((index, segment) in segments.withIndex()) {
                if (!isPlaying || pausedAtSegment) break
                
                currentSegmentIndex = index
                
                // 1. Speak the text (if any)
                if (segment.text.isNotEmpty()) {
                    val segmentVoice = voice.applyLanguageOverride(segment.languageTag)
                    
                    // Apply voice params for this segment
                    val lang = segmentVoice?.primaryLanguage ?: Locale.getDefault().toLanguageTag()
                    applyPlatformVoiceSettings(
                        ttsEngine = t,
                        languageTag = lang,
                        requestedVoiceName = parsedVoiceId.voiceName,
                    )
                    t.setPitch((pitch ?: 1.0).toFloat())
                    t.setSpeechRate((rate ?: 1.0).toFloat())
                    
                    // Queue the speech
                    val params = android.os.Bundle()
                    val utteranceId = "wingmate-$requestId-$index-${System.nanoTime()}"
                    params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                    
                    // Use QUEUE_ADD to chain segments seamlessly
                    val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    pendingTtsUtterances.incrementAndGet()
                    val speakResult = synchronized(playerLock) {
                        check(requestGeneration.get() == requestId) { "Speech request was replaced" }
                        t.speak(segment.text, queueMode, params, utteranceId)
                    }
                    if (speakResult != TextToSpeech.SUCCESS) {
                        onTtsUtteranceFinished(utteranceId)
                        error("Device text-to-speech rejected a speech segment")
                    }
                }
                
                // 2. Play silence for break (if any)
                if (segment.pauseDurationMs > 0) {
                    // playSilentUtterance is available API 21+ (Wingmate is min 24/26 usually)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        val silenceId = "wingmate-$requestId-silence-$index-${System.nanoTime()}"
                        pendingTtsUtterances.incrementAndGet()
                        val silenceResult = synchronized(playerLock) {
                            check(requestGeneration.get() == requestId) { "Speech request was replaced" }
                            t.playSilentUtterance(
                                segment.pauseDurationMs,
                                TextToSpeech.QUEUE_ADD,
                                silenceId
                            )
                        }
                        if (silenceResult != TextToSpeech.SUCCESS) {
                            onTtsUtteranceFinished(silenceId)
                            error("Device text-to-speech rejected a pause segment")
                        }
                    } else {
                        // Fallback using Thread.sleep is NOT safe on main thread, but pre-Lollipop is ancient.
                        // Just ignore or use playSilence (deprecated but works).
                        @Suppress("DEPRECATION")
                        run {
                            pendingTtsUtterances.incrementAndGet()
                            val silenceId = "wingmate-$requestId-silence-$index-${System.nanoTime()}"
                            val silenceResult = synchronized(playerLock) {
                                check(requestGeneration.get() == requestId) { "Speech request was replaced" }
                                t.playSilence(
                                    segment.pauseDurationMs,
                                    TextToSpeech.QUEUE_ADD,
                                    hashMapOf<String, String>().apply {
                                        put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, silenceId)
                                    }
                                )
                            }
                            if (silenceResult != TextToSpeech.SUCCESS) {
                                onTtsUtteranceFinished(silenceId)
                                error("Device text-to-speech rejected a pause segment")
                            }
                        }
                    }
                }
                
                // Note: We cannot easily delay() here because t.speak is asynchronous and non-blocking.
                // The queuing handles the timing. But for UI sync (highlighting), we're limited.
                // Since this method was previously using delay() which blocked the coroutine but not the TTS, 
                // we'll remove manual delay to let TTS handle timing properly.
            }

            if (pendingTtsUtterances.get() == 0) {
                finishPlayback(requestId)
            } else {
                markPlaybackStarted(combinedText, voice, requestId)
            }
        }
    }

    private fun applyPlatformVoiceSettings(
        ttsEngine: TextToSpeech,
        languageTag: String,
        requestedVoiceName: String?,
    ) {
        val locale = Locale.forLanguageTag(languageTag)
        runCatching { ttsEngine.setLanguage(locale) }

        if (requestedVoiceName.isNullOrBlank()) {
            return
        }

        val availableVoices = runCatching { ttsEngine.voices }.getOrNull().orEmpty()
        val selectedVoice = availableVoices.firstOrNull { it.name == requestedVoiceName } ?: return
        runCatching { ttsEngine.setVoice(selectedVoice) }
    }

    private fun shouldUseDefaultAzureVoice(voice: Voice?): Boolean {
        val voiceName = voice?.name
        if (voiceName.isNullOrBlank()) return true
        if (voiceName == "system-default") return true
        if (voice.resolvedProvider() == VoiceProvider.GOOGLE) return true

        val parsedVoiceId = AndroidTtsVoiceId.parse(voiceName)
        return parsedVoiceId.enginePackageName != null
    }

    private fun normalizeVoiceForAzure(voice: Voice?): Voice {
        if (!shouldUseDefaultAzureVoice(voice) && voice != null) {
            return voice
        }

        val base = voice ?: Voice()
        return base.copy(
            name = DEFAULT_AZURE_VOICE_NAME,
            primaryLanguage = base.primaryLanguage ?: DEFAULT_AZURE_LANGUAGE,
        )
    }


    private suspend fun getConfig(): SpeechServiceConfig? {
        val koin = GlobalContext.getOrNull()
        val repo = koin?.let { runCatching { it.get<io.github.jdreioe.wingmate.domain.ConfigRepository>() }.getOrNull() }

        val storedConfig = if (repo != null) {
            try {
                withContext(Dispatchers.IO) { repo.getSpeechConfig() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }

        if (storedConfig?.endpoint?.isNotBlank() == true && storedConfig.subscriptionKey.isNotBlank()) {
            return storedConfig
        }

        // Return any partially saved config so settings screens can still show persisted values.
        if (storedConfig != null) {
            return storedConfig
        }

        return null
    }

    private suspend fun getGoogleConfig(): GoogleSpeechConfig? {
        val repository = GlobalContext.getOrNull()?.let {
            runCatching { it.get<io.github.jdreioe.wingmate.domain.ConfigRepository>() }.getOrNull()
        } ?: return null
        return try {
            withContext(Dispatchers.IO) { repository.getGoogleSpeechConfig() }
                ?.takeIf { it.apiKey.isNotBlank() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }

    private fun normalizeVoiceForGoogle(voice: Voice?, primaryLanguage: String?): Voice {
        val base = voice?.takeIf { it.resolvedProvider() == VoiceProvider.GOOGLE } ?: Voice()
        val language = base.selectedLanguage.takeIf(String::isNotBlank)
            ?: primaryLanguage?.takeIf(String::isNotBlank)
            ?: base.primaryLanguage?.takeIf(String::isNotBlank)
            ?: "en-US"
        return base.copy(primaryLanguage = language, selectedLanguage = language, mathMode = false)
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
