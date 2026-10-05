package io.github.jdreioe.wingmate

import io.github.jdreioe.wingmate.domain.SpeechPlaybackState
import io.github.jdreioe.wingmate.domain.SpeechPlaybackStatus
import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.SpeechTextProcessor
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
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
    /**
     * Which voice spoke last and how its device request stands. The session polls
     * from a worker thread while the device reports on the main thread, so the
     * snapshot is only ever replaced as a whole.
     */
    private data class Route(
        val deviceIsActive: Boolean = false,
        val device: SpeechPlaybackState = SpeechPlaybackState(),
    )

    private val route = MutableStateFlow(Route())

    override suspend fun speakWithoutHistory(
        text: String,
        voice: Voice?,
        pitch: Double?,
        rate: Double?,
        cacheAudio: Boolean,
        engine: TtsEngine,
    ) {
        if (engine != TtsEngine.SYSTEM) {
            route.update { it.copy(deviceIsActive = false) }
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
            route.update { it.copy(deviceIsActive = false) }
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
        route.update { it.copy(deviceIsActive = false) }
        return cloud.speakRecordedAudio(audioFilePath, textForHistory, voice)
    }

    private suspend fun speakOnDevice(
        segments: List<SpeechSegment>,
        voice: Voice?,
        pitch: Double?,
        rate: Double?,
    ) = withContext(Dispatchers.Main) {
        val requestId = route.updateAndGet {
            Route(deviceIsActive = true, device = SpeechPlaybackState(it.device.requestId + 1, SpeechPlaybackStatus.PLAYING))
        }.device.requestId
        val language = voice?.selectedLanguage?.takeIf(String::isNotBlank)
            ?: voice?.primaryLanguage.orEmpty()
        device.speak(segments, language, rate ?: 1.0, pitch ?: 1.0) { spoken ->
            val status = if (spoken) SpeechPlaybackStatus.IDLE else SpeechPlaybackStatus.FAILED
            route.update { current ->
                // A newer request or a Stop makes this request's end irrelevant.
                if (current.device.requestId != requestId) current
                else current.copy(device = SpeechPlaybackState(requestId, status, "Device speech failed".takeUnless { spoken }))
            }
        }
    }

    override suspend fun pause() {
        if (!route.value.deviceIsActive) return cloud.pause()
        withContext(Dispatchers.Main) {
            val current = route.value.device
            if (current.status == SpeechPlaybackStatus.PLAYING) {
                device.pause()
                route.update { it.withDeviceStatus(current.requestId, SpeechPlaybackStatus.PAUSED) }
            }
        }
    }

    override suspend fun resume() {
        if (!route.value.deviceIsActive) return cloud.resume()
        withContext(Dispatchers.Main) {
            val current = route.value.device
            if (current.status == SpeechPlaybackStatus.PAUSED) {
                device.resume()
                route.update { it.withDeviceStatus(current.requestId, SpeechPlaybackStatus.PLAYING) }
            }
        }
    }

    override suspend fun stop() {
        withContext(Dispatchers.Main) {
            // A newer request id makes the cancelled utterance's callback a no-op.
            route.update { it.copy(device = SpeechPlaybackState(it.device.requestId + 1, SpeechPlaybackStatus.IDLE)) }
            device.stop()
        }
        cloud.stop()
    }

    override fun isPlaying(): Boolean = route.value.let {
        if (it.deviceIsActive) it.device.status == SpeechPlaybackStatus.PLAYING else cloud.isPlaying()
    }

    override fun isPaused(): Boolean = route.value.let {
        if (it.deviceIsActive) it.device.status == SpeechPlaybackStatus.PAUSED else cloud.isPaused()
    }

    override fun playbackState(): SpeechPlaybackState = route.value.let {
        if (it.deviceIsActive) it.device else cloud.playbackState()
    }

    private fun Route.withDeviceStatus(requestId: Long, status: SpeechPlaybackStatus): Route =
        if (device.requestId == requestId) copy(device = device.copy(status = status)) else this
}
