package io.github.jdreioe.flow

import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.SpeechTextProcessor
import io.github.jdreioe.wingmate.domain.Voice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Drives the Flow popup lifecycle around the shared [SpeechService].
 *
 * Wires the existing [SpeechTextProcessor] → [SpeechService] pipeline; Flow
 * introduces no new speech engine. A new [read] replaces any active reading:
 * it stops the current playback, captures the new selection, and starts again.
 *
 * The controller never logs, stores, or otherwise retains the spoken text.
 */
class FlowController(
    private val speechService: SpeechService,
    private val scope: CoroutineScope,
) {
    private val generation = AtomicLong(0)
    private val _state = MutableStateFlow<FlowState>(FlowState.Hidden)
    val state: StateFlow<FlowState> = _state.asStateFlow()

    private var playbackJob: Job? = null

    /** Read [text] aloud, replacing any active reading. Blank text stops playback. */
    fun read(text: String, voice: Voice? = null, rate: Double? = null) {
        stopPlayback()
        val requestId = generation.incrementAndGet()

        if (text.isBlank()) {
            _state.value = FlowState.Hidden
            return
        }

        _state.value = FlowState.Preparing

        playbackJob = scope.launch {
            try {
                val segments = SpeechTextProcessor.processText(text)
                _state.value = FlowState.Playing
                speechService.speakSegments(segments, voice = voice, rate = rate)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _state.value = FlowState.Hidden
            } finally {
                // Only the current generation may settle the state; a cancelled
                // or replaced reading must not clobber a newer one.
                if (generation.get() == requestId) _state.value = FlowState.Hidden
            }
        }
    }

    fun pause() {
        if (_state.value != FlowState.Playing) return
        scope.launch { speechService.pause() }
        _state.value = FlowState.Paused
    }

    fun resume() {
        if (_state.value != FlowState.Paused) return
        scope.launch { speechService.resume() }
        _state.value = FlowState.Playing
    }

    fun stop() {
        stopPlayback()
        _state.value = FlowState.Hidden
    }

    private fun stopPlayback() {
        generation.incrementAndGet()
        playbackJob?.cancel()
        playbackJob = null
        scope.launch { speechService.stop() }
    }
}