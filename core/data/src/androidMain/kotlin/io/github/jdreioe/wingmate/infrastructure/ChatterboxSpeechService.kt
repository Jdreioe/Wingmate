package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.SpeechTextProcessor
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.chatterbox.ChatterboxError
import io.github.jdreioe.wingmate.domain.chatterbox.ChatterboxRuntimeStatus
import io.github.jdreioe.wingmate.domain.chatterbox.ChatterboxStatusProvider
import io.github.jdreioe.wingmate.domain.chatterbox.ModelDownloader
import io.github.jdreioe.wingmate.domain.chatterbox.ModelInstallationStatus
import io.github.jdreioe.wingmate.domain.chatterbox.VoiceCloningService
import io.github.jdreioe.wingmate.domain.chatterbox.VoiceProfileRepository
import io.github.jdreioe.wingmate.infrastructure.chatterbox.OfficialModelRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class ChatterboxSpeechService(
    private val context: Context,
    private val voiceProfileRepository: VoiceProfileRepository,
    private val modelDownloader: ModelDownloader,
) : SpeechService, VoiceCloningService, ChatterboxStatusProvider {
    private val modelId = OfficialModelRegistry.Q4_MODEL_ID
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loadMutex = Mutex()
    private val requestMutex = Mutex()
    private val playLock = Any()
    private val _status = MutableStateFlow<ChatterboxRuntimeStatus>(initialStatus())
    override val status: StateFlow<ChatterboxRuntimeStatus> = _status.asStateFlow()

    private var engine: ChatterboxTtsEngine? = null
    private var conditionals: ChatterboxTtsEngine.Conditionals? = null
    private var conditionalsProfileId: String? = null
    private var synthesis: Deferred<ShortArray>? = null
    private var requestSerial = 0L
    private var audioTrack: AudioTrack? = null
    private var paused = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    private fun initialStatus(): ChatterboxRuntimeStatus =
        when (modelDownloader.installationStatus(modelId)) {
            is ModelInstallationStatus.Installed -> ChatterboxRuntimeStatus.Ready(modelId, null)
            else -> ChatterboxRuntimeStatus.NotInstalled
        }

    suspend fun ensureLoaded(): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            loadMutex.withLock {
                if (engine?.isLoaded() == true) return@withLock
                _status.value = ChatterboxRuntimeStatus.Loading
                val created = engine ?: createEngineForInstalledModel().also { engine = it }
                created.load().getOrThrow()
                engine = created
                _status.value = ChatterboxRuntimeStatus.Ready(modelId, conditionalsProfileId)
            }
        }.onFailure { error ->
            _status.value = ChatterboxRuntimeStatus.Error(error.message ?: "Unable to load Chatterbox", false)
        }
    }

    fun unload() {
        synchronized(playLock) { stopAudioLocked() }
        synthesis?.cancel()
        synthesis = null
        engine?.unload()
        engine = null
        conditionals = null
        conditionalsProfileId = null
        _status.value = initialStatus()
    }

    override fun release() = unload()

    fun recordFallback(error: Throwable) {
        _status.value = ChatterboxRuntimeStatus.Error(
            error.message ?: "Chatterbox failed; Android speech was used",
            fallbackUsed = true,
        )
    }

    override suspend fun extractConditionals(audioFilePath: String): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            val file = File(audioFilePath)
            if (!file.isFile) throw ChatterboxError.CloneFailed("Audio file not found")
            loadMutex.withLock {
                _status.value = ChatterboxRuntimeStatus.Loading
                val currentEngine = engine ?: createEngineForInstalledModel().also { engine = it }
                conditionals = currentEngine.createConditionalsFromAudio(file).getOrThrow()
                conditionalsProfileId = voiceProfileRepository.getActive()
                    ?.takeIf { it.sourceRecordingPath == audioFilePath }
                    ?.id
                _status.value = ChatterboxRuntimeStatus.Ready(modelId, conditionalsProfileId)
            }
        }
    }

    private fun createEngineForInstalledModel(): ChatterboxTtsEngine {
        val installation = modelDownloader.installationStatus(modelId)
        if (installation !is ModelInstallationStatus.Installed) {
            _status.value = ChatterboxRuntimeStatus.NotInstalled
            throw ChatterboxError.ModelNotFound(modelId)
        }
        val modelDir = File(installation.storagePath)
        val tokenizer = ChatterboxTokenizer(File(modelDir, "tokenizer.json").absolutePath)
        return ChatterboxTtsEngine(modelDir, tokenizer)
    }

    override suspend fun speak(text: String, voice: Voice?, pitch: Double?, rate: Double?) {
        speakSegmentsResult(SpeechTextProcessor.processText(text), voice, pitch, rate).getOrThrow()
    }

    override suspend fun speakSegments(
        segments: List<SpeechSegment>,
        voice: Voice?,
        pitch: Double?,
        rate: Double?,
    ) {
        speakSegmentsResult(segments, voice, pitch, rate).getOrThrow()
    }

    suspend fun speakSegmentsResult(
        segments: List<SpeechSegment>,
        voice: Voice?,
        pitch: Double?,
        rate: Double?,
    ): Result<Unit> {
        val text = segments.joinToString(" ") { it.text }.trim()
        if (text.isBlank()) return Result.success(Unit)
        val requestId = beginRequest()
        return try {
            _status.value = ChatterboxRuntimeStatus.Speaking
            val task = serviceScope.async {
                ensureLoaded().getOrThrow()
                ensureActiveVoiceConditionals()
                val currentEngine = engine ?: throw ChatterboxError.InferenceError("Engine is not loaded")
                _status.value = ChatterboxRuntimeStatus.Speaking
                currentEngine.synthesize(
                    text = text,
                    languageId = voice?.primaryLanguage?.take(2) ?: "en",
                    conditionals = conditionals,
                )
            }
            val accepted = requestMutex.withLock {
                if (requestId != requestSerial) false
                else {
                    synthesis = task
                    true
                }
            }
            if (!accepted) {
                task.cancel()
                return Result.failure(ChatterboxError.Cancelled())
            }
            val samples = task.await()
            val stillOwner = requestMutex.withLock {
                if (requestId == requestSerial && synthesis === task) {
                    synthesis = null
                    true
                } else false
            }
            if (!stillOwner) throw ChatterboxError.Cancelled()
            withContext(Dispatchers.IO) { playAudio(samples) }
            Result.success(Unit)
        } catch (_: CancellationException) {
            requestMutex.withLock {
                if (requestId == requestSerial) synthesis = null
            }
            Result.failure(ChatterboxError.Cancelled())
        } catch (error: ChatterboxError.Cancelled) {
            Result.failure(error)
        } catch (error: Throwable) {
            requestMutex.withLock {
                if (requestId == requestSerial) synthesis = null
            }
            Log.e(TAG, "Chatterbox synthesis failed", error)
            _status.value = ChatterboxRuntimeStatus.Error(error.message ?: "Chatterbox failed", false)
            Result.failure(error)
        }
    }

    private suspend fun beginRequest(): Long {
        val (requestId, previous) = requestMutex.withLock {
            requestSerial++
            val old = synthesis
            synthesis = null
            requestSerial to old
        }
        previous?.cancelAndJoin()
        synchronized(playLock) { stopAudioLocked() }
        return requestId
    }

    private suspend fun ensureActiveVoiceConditionals() {
        val active = voiceProfileRepository.getActive()
        if (active == null) {
            conditionals = null
            conditionalsProfileId = null
            return
        }
        if (conditionals != null && conditionalsProfileId == active.id) return
        val path = active.sourceRecordingPath
            ?: throw ChatterboxError.CorruptedVoiceProfile("Missing source recording")
        val file = File(path)
        if (!file.isFile) throw ChatterboxError.CorruptedVoiceProfile("Source recording not found")
        conditionals = engine?.createConditionalsFromAudio(file)?.getOrThrow()
            ?: throw ChatterboxError.InferenceError("Engine is not loaded")
        conditionalsProfileId = active.id
        _status.value = ChatterboxRuntimeStatus.Ready(modelId, active.id)
    }

    override suspend fun speakRecordedAudio(audioFilePath: String, textForHistory: String?, voice: Voice?): Boolean = false

    override suspend fun pause() {
        synchronized(playLock) {
            audioTrack?.takeIf { it.playState == AudioTrack.PLAYSTATE_PLAYING }?.pause()
            paused = audioTrack?.playState == AudioTrack.PLAYSTATE_PAUSED
        }
    }

    override suspend fun stop() {
        val task = requestMutex.withLock {
            requestSerial++
            synthesis.also { synthesis = null }
        }
        task?.cancelAndJoin()
        synchronized(playLock) { stopAudioLocked() }
        if (_status.value is ChatterboxRuntimeStatus.Speaking) {
            _status.value = ChatterboxRuntimeStatus.Ready(modelId, conditionalsProfileId)
        }
    }

    override suspend fun resume() {
        synchronized(playLock) {
            if (paused) {
                audioTrack?.play()
                paused = false
                _status.value = ChatterboxRuntimeStatus.Speaking
            }
        }
    }

    override fun isPlaying(): Boolean = synchronized(playLock) {
        audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING
    }

    override fun isPaused(): Boolean = paused

    override suspend fun guessPronunciation(text: String, language: String): String? = null

    private fun playAudio(samples: ShortArray) {
        if (samples.isEmpty()) throw ChatterboxError.InvalidWaveform("empty PCM")
        synchronized(playLock) {
            stopAudioLocked()
            requestAudioFocus()
            val track = AudioTrack.Builder()
                .setAudioAttributes(AUDIO_ATTRIBUTES)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(ChatterboxTtsEngine.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            val written = track.write(samples, 0, samples.size)
            if (written != samples.size) {
                track.release()
                throw ChatterboxError.InvalidWaveform("AudioTrack accepted $written/${samples.size} samples")
            }
            track.notificationMarkerPosition = samples.size
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(completed: AudioTrack) {
                    synchronized(playLock) {
                        if (audioTrack === completed) {
                            stopAudioLocked()
                            _status.value = ChatterboxRuntimeStatus.Ready(modelId, conditionalsProfileId)
                        }
                    }
                }

                override fun onPeriodicNotification(track: AudioTrack) = Unit
            })
            audioTrack = track
            paused = false
            track.play()
        }
    }

    private fun stopAudioLocked() {
        audioTrack?.let { track ->
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        audioTrack = null
        paused = false
        abandonAudioFocus()
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AUDIO_ATTRIBUTES)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let(audioManager::abandonAudioFocusRequest)
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    companion object {
        private const val TAG = "ChatterboxService"
        private val AUDIO_ATTRIBUTES = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
