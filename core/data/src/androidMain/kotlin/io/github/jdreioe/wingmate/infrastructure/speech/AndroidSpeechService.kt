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
import io.github.jdreioe.wingmate.domain.AudioRouteDecision
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.SpeechPlaybackState
import io.github.jdreioe.wingmate.domain.SpeechPlaybackStatus
import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.VoiceProvider
import io.github.jdreioe.wingmate.domain.resolvedProvider
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.domain.ElevenLabsSpeechConfig
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
import java.io.FileInputStream
import java.io.FileOutputStream
import android.os.ParcelFileDescriptor
import android.os.Environment
import androidx.annotation.RequiresPermission
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * Plays cloud speech (Azure or Google) through MediaPlayer and device speech through platform
 * TextToSpeech. Communication-session requests speak exactly the requested engine and let the
 * session own fallback and History; the remaining legacy entry points fall back themselves.
 */
class AndroidSpeechService(
    internal val context: Context,
    internal val googleApiRequestHeaders: GoogleApiRequestHeaders,
    internal val audioRouter: AndroidAudioPreviewRouter = AndroidAudioPreviewRouter(context),
) : SpeechService {
    internal companion object {
        const val DEFAULT_AZURE_VOICE_NAME = "en-US-JennyNeural"
        const val DEFAULT_AZURE_LANGUAGE = "en-US"
    }

    internal val client = HttpClient(OkHttp) {
        followRedirects = false
    }

    // Platform TTS (fallback)
    internal var tts: TextToSpeech? = null
    internal var ttsInitialization: CompletableDeferred<Boolean>? = null
    internal var activeTtsEnginePackage: String? = null

    // MediaPlayer based playback for Azure synthesized audio
    internal var mediaPlayer: MediaPlayer? = null
    internal val playerLock = Any()
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    internal val audioManager: AudioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    init {
        runCatching {
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch(Dispatchers.IO) {
                        GlobalContext.getOrNull()?.getOrNull<io.github.jdreioe.wingmate.domain.BoardSpeechCache>()?.cacheAll()
                    }
                }
            })
        }
    }
    internal val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        // For simplicity: on full loss, stop playback; on other changes we ignore/duck handled by system
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            scope.launch { runCatching { stop() } }
        }
    }
    internal val ttsAudioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            // Android Auto routes generic app playback most reliably on media usage.
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
    internal var audioFocusRequest: AudioFocusRequest? = null
    internal fun requestAudioFocus(attributes: AudioAttributes = ttsAudioAttributes): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .setAudioAttributes(attributes)
                    .build()
                audioFocusRequest = req
                audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                val streamType = if (attributes.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION) {
                    AudioManager.STREAM_VOICE_CALL
                } else {
                    AudioManager.STREAM_MUSIC
                }
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    focusChangeListener,
                    streamType,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (_: Throwable) { false }
    }
    internal fun abandonAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(focusChangeListener)
            }
        } catch (_: Throwable) { }
    }
    internal val CACHE_TTL_MS: Long = 30L * 24 * 60 * 60 * 1000
    
    // Track if we've already shown the offline warning to avoid spam
    internal val offlineWarningShown = AtomicBoolean(false)
    
    // Enhanced state for segmented playback and resume
    internal var currentSegments: List<SpeechSegment> = emptyList()
    internal var currentSegmentIndex = 0
    internal var currentVoice: Voice? = null
    internal var currentPitch: Double? = null
    internal var currentRate: Double? = null
    internal var pausedAtSegment = false
    @Volatile internal var isPlaying = false
    @Volatile internal var isPaused = false
    internal val requestGeneration = AtomicLong(0)
    @Volatile internal var activeRequestJob: Job? = null
    @Volatile internal var state = SpeechPlaybackState()
    internal val pendingTtsUtterances = AtomicInteger(0)
    /** Set when the TTS engine reports an error for the current request's utterances. */
    internal val ttsUtteranceFailed = java.util.concurrent.atomic.AtomicBoolean(false)
    internal val mediaSession: MediaSession by lazy {
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

    internal fun buildPlaybackState(state: Int): PlaybackState {
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

    internal fun updatePlaybackState(state: Int) {
        runCatching {
            mediaSession.isActive = state != PlaybackState.STATE_NONE
            mediaSession.setPlaybackState(buildPlaybackState(state))
        }
    }

    internal fun updateNowPlaying(text: String?, voice: Voice?) {
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

    internal fun markPlaybackStarted(text: String?, voice: Voice?, requestId: Long = requestGeneration.get()) {
        if (requestGeneration.get() != requestId) return
        isPlaying = true
        isPaused = false
        state = SpeechPlaybackState(requestId, SpeechPlaybackStatus.PLAYING)
        updateNowPlaying(text, voice)
        updatePlaybackState(PlaybackState.STATE_PLAYING)
    }

    internal fun finishPlayback(requestId: Long = requestGeneration.get(), failed: Boolean = false) {
        if (requestGeneration.get() != requestId) return
        isPlaying = false
        isPaused = false
        state = if (failed) {
            SpeechPlaybackState(requestId, SpeechPlaybackStatus.FAILED, "Speech could not be played")
        } else {
            SpeechPlaybackState(requestId, SpeechPlaybackStatus.IDLE)
        }
        pausedAtSegment = false
        updatePlaybackState(PlaybackState.STATE_STOPPED)
        abandonAudioFocus()
    }

    internal suspend fun beginRequest(): Long {
        val job = currentCoroutineContext()[Job]
        activeRequestJob?.takeIf { it !== job }?.cancel()
        activeRequestJob = job
        val requestId = requestGeneration.incrementAndGet()
        stopNativePlayback()
        state = SpeechPlaybackState(requestId, SpeechPlaybackStatus.PREPARING)
        return requestId
    }

    internal suspend fun <T> executeRequest(
        requestId: Long,
        announceFailure: Boolean = true,
        block: suspend () -> T,
    ): T = try {
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
            if (announceFailure) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Speech could not be played. Please try again.", Toast.LENGTH_LONG).show()
                }
            }
        }
        throw error
    }
    
    /**
     * Check if device has active internet connection
     */
    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    internal fun isOnline(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    
    /**
     * Show user warning about offline mode fallback to system TTS (similar to iOS behavior)
     */
    internal fun showOfflineWarning() {
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
    internal fun showConfigWarning() {
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
    internal fun showDefaultVoiceWarning() {
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

    override suspend fun speakWithoutHistory(
        text: String,
        voice: Voice?,
        pitch: Double?,
        rate: Double?,
        cacheAudio: Boolean,
        engine: TtsEngine,
    ) = speakWithoutHistory(text, voice, pitch, rate, cacheAudio, engine, AudioRouteDecision.SystemDefault)

    override suspend fun speakWithoutHistory(
        text: String,
        voice: Voice?,
        pitch: Double?,
        rate: Double?,
        cacheAudio: Boolean,
        engine: TtsEngine,
        audioRoute: AudioRouteDecision,
    ) {
        val requestId = beginRequest()
        // The session explains failures and falls back itself, so no toast here.
        executeRequest(requestId, announceFailure = false) {
            val segments = SpeechTextProcessor.processText(text)
            speakSessionSegments(requestId, segments, voice, pitch, rate, cacheAudio, engine, audioRoute)
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

    override suspend fun speakSegmentsWithoutHistory(
        segments: List<SpeechSegment>,
        voice: Voice?,
        pitch: Double?,
        rate: Double?,
        cacheAudio: Boolean,
        engine: TtsEngine,
    ) = speakSegmentsWithoutHistory(segments, voice, pitch, rate, cacheAudio, engine, AudioRouteDecision.SystemDefault)

    override suspend fun speakSegmentsWithoutHistory(
        segments: List<SpeechSegment>,
        voice: Voice?,
        pitch: Double?,
        rate: Double?,
        cacheAudio: Boolean,
        engine: TtsEngine,
        audioRoute: AudioRouteDecision,
    ) {
        val requestId = beginRequest()
        executeRequest(requestId, announceFailure = false) {
            speakSessionSegments(requestId, segments, voice, pitch, rate, cacheAudio, engine, audioRoute)
        }
    }

    override suspend fun cacheSpeech(text: String, voice: Voice?, pitch: Double?, rate: Double?): Boolean =
        cacheSpeechImpl(text, voice, pitch, rate)

    internal suspend fun speakSegmentsInternal(
        requestId: Long,
        segments: List<SpeechSegment>,
        voice: Voice?,
        pitch: Double?,
        rate: Double?,
        cacheAudio: Boolean,
    ) {
        val uiSettings = currentSettings()
        val engine = uiSettings?.ttsEngine ?: TtsEngine.SYSTEM
        rememberPlaybackContext(segments, voice, pitch, rate)
        val combinedText = segments.joinToString("") { it.text }

        if (engine == TtsEngine.SYSTEM) {
            playSegmentsWithPlatformTts(requestId, segments, voice, pitch, rate)
            recordHistory(combinedText, voice)
            return
        }
        val played = try {
            playCloud(requestId, engine, segments, voice, pitch, rate, cacheAudio, uiSettings)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            when ((failure as? CloudSpeechUnavailable)?.reason) {
                CloudSpeechUnavailable.Reason.NotConfigured -> showConfigWarning()
                CloudSpeechUnavailable.Reason.Offline -> showOfflineWarning()
                null -> Unit
            }
            if (requestGeneration.get() != requestId) throw CancellationException("Speech request was replaced")
            speakWithPlatformTts(requestId, combinedText, voice, pitch, rate)
            recordHistory(combinedText, voice)
            return
        }
        recordHistory(combinedText, played.voice, played.cachedAudioPath)
    }

    /** Communication-session playback: speaks with exactly [engine] and never falls back or records History. */
    internal suspend fun speakSessionSegments(
        requestId: Long,
        segments: List<SpeechSegment>,
        voice: Voice?,
        pitch: Double?,
        rate: Double?,
        cacheAudio: Boolean,
        engine: TtsEngine,
        audioRoute: AudioRouteDecision = AudioRouteDecision.SystemDefault,
    ) {
        rememberPlaybackContext(segments, voice, pitch, rate)
        if (engine == TtsEngine.SYSTEM) {
            playSegmentsWithPlatformTts(requestId, segments, voice, pitch, rate, audioRoute)
        } else {
            playCloud(requestId, engine, segments, voice, pitch, rate, cacheAudio, currentSettings(), audioRoute)
        }
    }

    internal suspend fun currentSettings(): io.github.jdreioe.wingmate.domain.Settings? {
        val settingsRepo = GlobalContext.getOrNull()?.let {
            runCatching { it.get<io.github.jdreioe.wingmate.domain.SettingsRepository>() }.getOrNull()
        }
        return settingsRepo?.let { runCatching { it.get() }.getOrNull() }
    }

    /** Store current playback context for resume functionality. */
    internal fun rememberPlaybackContext(segments: List<SpeechSegment>, voice: Voice?, pitch: Double?, rate: Double?) {
        currentSegments = segments
        currentSegmentIndex = 0
        currentVoice = voice
        currentPitch = pitch
        currentRate = rate
        pausedAtSegment = false
        isPlaying = true
        isPaused = false
    }

    override suspend fun speakRecordedAudio(audioFilePath: String, textForHistory: String?, voice: Voice?): Boolean =
        speakRecordedAudioImpl(audioFilePath, textForHistory, voice, AudioRouteDecision.SystemDefault)

    override suspend fun speakRecordedAudio(
        audioFilePath: String,
        textForHistory: String?,
        voice: Voice?,
        audioRoute: AudioRouteDecision,
    ): Boolean = speakRecordedAudioImpl(audioFilePath, textForHistory, voice, audioRoute)

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
        audioRouter.restoreRoute()
        state = SpeechPlaybackState(requestId, SpeechPlaybackStatus.IDLE)
    }

    internal fun stopNativePlayback() {
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

    internal suspend fun playSegmentsFromIndex(requestId: Long, startIndex: Int) {
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
}
