package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.SpeechTextProcessor
import io.github.jdreioe.wingmate.domain.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class ChatterboxSpeechService(
    private val context: Context,
) : SpeechService {

    private var engine: ChatterboxTtsEngine? = null
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var isPaused = false
    private val playLock = Any()
    private val isEngineLoaded = AtomicBoolean(false)
    private var conditionals: ChatterboxTtsEngine.Conditionals? = null

    companion object {
        private const val TAG = "ChatterboxService"
        private const val SAMPLE_RATE = 24000
    }

    suspend fun ensureLoaded(): Result<Unit> = withContext(Dispatchers.IO) {
        if (isEngineLoaded.get()) return@withContext Result.success(Unit)
        runCatching {
            val modelDir = ChatterboxModelDownloader.getDefaultModelDir(context)
            val eng = ChatterboxTtsEngine(modelDir)
            eng.load()
            engine = eng
            isEngineLoaded.set(true)
        }
    }

    fun unload() {
        engine?.unload()
        engine = null
        isEngineLoaded.set(false)
    }

    fun setConditionals(conds: ChatterboxTtsEngine.Conditionals) {
        conditionals = conds
    }

    override suspend fun speak(text: String, voice: Voice?, pitch: Double?, rate: Double?) {
        val segments = SpeechTextProcessor.processText(text)
        speakSegments(segments, voice, pitch, rate)
    }

    override suspend fun speakSegments(
        segments: List<SpeechSegment>,
        voice: Voice?,
        pitch: Double?,
        rate: Double?,
    ) {
        val combinedText = segments.joinToString(" ") { it.text }.trim()
        if (combinedText.isBlank()) return

        stop()

        val eng = engine ?: run {
            ensureLoaded().getOrElse {
                return
            }
            engine ?: return
        }

        val conds = conditionals ?: return

        withContext(Dispatchers.IO) {
            runCatching {
                val audioSamples = eng.synthesize(
                    text = combinedText,
                    languageId = voice?.primaryLanguage?.take(2) ?: "en",
                    conditionals = conds,
                )

                synchronized(playLock) {
                    playAudio(audioSamples)
                }
            }.onFailure { e ->
                android.util.Log.e(TAG, "Chatterbox synthesis failed", e)
            }
        }
    }

    override suspend fun speakRecordedAudio(
        audioFilePath: String,
        textForHistory: String?,
        voice: Voice?,
    ): Boolean = false

    override suspend fun pause() {
        synchronized(playLock) {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.pause()
                    isPaused = true
                }
            }
        }
    }

    override suspend fun stop() {
        stopAudioInternal()
    }

    private fun stopAudioInternal() {
        synchronized(playLock) {
            audioTrack?.let {
                try {
                    it.stop()
                    it.release()
                } catch (_: Throwable) {}
                audioTrack = null
            }
            isPlaying = false
            isPaused = false
        }
    }

    override suspend fun resume() {
        synchronized(playLock) {
            audioTrack?.let {
                if (isPaused && it.playState == AudioTrack.PLAYSTATE_PAUSED) {
                    it.play()
                    isPaused = false
                }
            }
        }
    }

    override fun isPlaying(): Boolean = isPlaying
    override fun isPaused(): Boolean = isPaused

    override suspend fun guessPronunciation(text: String, language: String): String? = null

    private fun playAudio(samples: ShortArray) {
        stopAudioInternal()

        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(samples.size * 2)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(samples, 0, samples.size)
        track.play()
        audioTrack = track
        isPlaying = true
    }
}
