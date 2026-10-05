package io.github.jdreioe.wingmate

import io.github.jdreioe.wingmate.domain.SpeechPlaybackState
import io.github.jdreioe.wingmate.domain.SpeechPlaybackStatus
import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.SpeechTextProcessor
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The device voice, implemented in Swift with AVSpeechSynthesizer and passed to
 * [IosDiBridge.startKoinWithOverridesBridge]. All calls arrive on the main thread.
 */
interface IosDeviceSpeech {
    /**
     * Speaks [segments] in order, honoring each segment's pause and language.
     * [rate] and [pitch] are multipliers where 1.0 is the voice's normal value.
     * Calls [onFinished] once: true when everything was spoken, false when it
     * failed or was cancelled.
     */
    fun speak(
        segments: List<SpeechSegment>,
        languageTag: String,
        rate: Double,
        pitch: Double,
        onFinished: (Boolean) -> Unit,
    )

    fun pause()
    fun resume()
    fun stop()
}

/**
 * Routes the Communication session's speech: [TtsEngine.SYSTEM] goes to the
 * device voice, everything else to the cloud service. The session owns engine
 * choice and fallback; this only reports the playback of whichever voice spoke
 * last, so the session can wait for it and see failures.
 */
internal class IosSessionSpeechService(
    private val cloud: SpeechService,
    private val device: IosDeviceSpeech,
) : SpeechService by cloud {
    private var deviceIsActive = false
    private var deviceRequestId = 0L
    private var deviceState = SpeechPlaybackState()

    override suspend fun speakWithoutHistory(
        text: String,
        voice: Voice?,
        pitch: Double?,
        rate: Double?,
        cacheAudio: Boolean,
        engine: TtsEngine,
    ) {
        if (engine != TtsEngine.SYSTEM) {
            deviceIsActive = false
            cloud.speakWithoutHistory(text, voice, pitch, rate, cacheAudio, engine)
        } else {
            speakOnDevice(SpeechTextProcessor.processText(text), voice, pitch, rate)
        }
    }

    override suspend fun speakSegmentsWithoutHistory(
        segments: List<SpeechSegment>,
        voice: Voice?,
        pitch: Double?,
        rate: Double?,
        cacheAudio: Boolean,
        engine: TtsEngine,
    ) {
        if (engine != TtsEngine.SYSTEM) {
            deviceIsActive = false
            cloud.speakSegmentsWithoutHistory(segments, voice, pitch, rate, cacheAudio, engine)
        } else {
            // Expand shorthand SSML pauses inside each segment, keeping its language.
            val expanded = segments.flatMap { segment ->
                SpeechTextProcessor.processText(segment.text).map {
                    it.copy(languageTag = it.languageTag ?: segment.languageTag)
                }
            }
            speakOnDevice(expanded, voice, pitch, rate)
        }
    }

    override suspend fun speakRecordedAudio(audioFilePath: String, textForHistory: String?, voice: Voice?): Boolean {
        deviceIsActive = false
        return cloud.speakRecordedAudio(audioFilePath, textForHistory, voice)
    }

    private suspend fun speakOnDevice(
        segments: List<SpeechSegment>,
        voice: Voice?,
        pitch: Double?,
        rate: Double?,
    ) = withContext(Dispatchers.Main) {
        val requestId = ++deviceRequestId
        deviceIsActive = true
        deviceState = SpeechPlaybackState(requestId, SpeechPlaybackStatus.PLAYING)
        val language = voice?.selectedLanguage?.takeIf(String::isNotBlank)
            ?: voice?.primaryLanguage.orEmpty()
        device.speak(segments, language, rate ?: 1.0, pitch ?: 1.0) { spoken ->
            if (requestId == deviceRequestId) {
                deviceState = if (spoken) {
                    SpeechPlaybackState(requestId, SpeechPlaybackStatus.IDLE)
                } else {
                    SpeechPlaybackState(requestId, SpeechPlaybackStatus.FAILED, "Device speech failed")
                }
            }
        }
    }

    override suspend fun pause() {
        if (!deviceIsActive) return cloud.pause()
        withContext(Dispatchers.Main) {
            if (deviceState.status == SpeechPlaybackStatus.PLAYING) {
                device.pause()
                deviceState = deviceState.copy(status = SpeechPlaybackStatus.PAUSED)
            }
        }
    }

    override suspend fun resume() {
        if (!deviceIsActive) return cloud.resume()
        withContext(Dispatchers.Main) {
            if (deviceState.status == SpeechPlaybackStatus.PAUSED) {
                device.resume()
                deviceState = deviceState.copy(status = SpeechPlaybackStatus.PLAYING)
            }
        }
    }

    override suspend fun stop() {
        withContext(Dispatchers.Main) {
            // A newer request id makes the cancelled utterance's callback a no-op.
            deviceRequestId++
            deviceState = SpeechPlaybackState(deviceRequestId, SpeechPlaybackStatus.IDLE)
            device.stop()
        }
        cloud.stop()
    }

    override fun isPlaying(): Boolean =
        if (deviceIsActive) deviceState.status == SpeechPlaybackStatus.PLAYING else cloud.isPlaying()

    override fun isPaused(): Boolean =
        if (deviceIsActive) deviceState.status == SpeechPlaybackStatus.PAUSED else cloud.isPaused()

    override fun playbackState(): SpeechPlaybackState =
        if (deviceIsActive) deviceState else cloud.playbackState()
}
