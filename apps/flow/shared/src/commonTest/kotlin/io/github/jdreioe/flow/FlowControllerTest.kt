package io.github.jdreioe.flow

import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FlowControllerTest {

    private class FakeSpeechService : SpeechService {
        val spoken = mutableListOf<String>()
        var pauseCount = 0
        var resumeCount = 0
        var stopCount = 0
        var gate: CompletableDeferred<Unit>? = null
        var failWith: Throwable? = null

        override suspend fun speak(text: String, voice: Voice?, pitch: Double?, rate: Double?) {
            spoken += text
            failWith?.let { throw it }
            gate?.await()
        }

        override suspend fun speakSegments(
            segments: List<SpeechSegment>,
            voice: Voice?,
            pitch: Double?,
            rate: Double?,
        ) {
            spoken += segments.joinToString(" ") { it.text }
            failWith?.let { throw it }
            gate?.await()
        }

        override suspend fun pause() { pauseCount++ }
        override suspend fun stop() { stopCount++ }
        override suspend fun resume() { resumeCount++ }
        override fun isPlaying() = false
        override fun isPaused() = false
    }

    private fun controller(service: FakeSpeechService, scope: CoroutineScope): FlowController =
        FlowController(service, scope)

    @Test
    fun read_splitsTextAndSpeaksSegments() = runTest {
        val service = FakeSpeechService()
        val flow = controller(service, this)

        flow.read("Start <en>hello</en> end")
        advanceUntilIdle()

        assertEquals(FlowState.Hidden, flow.state.value)
        assertEquals(1, service.spoken.size)
        assertTrue(service.spoken[0].contains("hello"))
    }

    @Test
    fun read_replacesActiveReading() = runTest {
        val service = FakeSpeechService()
        val flow = controller(service, this)

        val firstGate = CompletableDeferred<Unit>()
        service.gate = firstGate

        flow.read("first reading")
        assertEquals(FlowState.Preparing, flow.state.value)
        advanceUntilIdle()
        assertEquals(FlowState.Playing, flow.state.value)
        assertEquals(1, service.spoken.size)

        service.gate = null
        flow.read("replacement reading")
        advanceUntilIdle()

        assertEquals(FlowState.Hidden, flow.state.value)
        assertEquals(2, service.spoken.size)
        assertEquals("replacement reading", service.spoken.last())
        assertTrue(service.stopCount >= 1)
        firstGate.complete(Unit)
    }

    @Test
    fun pause_resume_togglesState() = runTest {
        val service = FakeSpeechService()
        val flow = controller(service, this)

        val gate = CompletableDeferred<Unit>()
        service.gate = gate

        flow.read("persistent text")
        advanceUntilIdle()
        assertEquals(FlowState.Playing, flow.state.value)

        flow.pause()
        advanceUntilIdle()
        assertEquals(FlowState.Paused, flow.state.value)
        assertEquals(1, service.pauseCount)

        flow.resume()
        advanceUntilIdle()
        assertEquals(FlowState.Playing, flow.state.value)
        assertEquals(1, service.resumeCount)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(FlowState.Hidden, flow.state.value)
    }

    @Test
    fun stop_returnsToHidden() = runTest {
        val service = FakeSpeechService()
        val flow = controller(service, this)

        val gate = CompletableDeferred<Unit>()
        service.gate = gate

        flow.read("persistent text")
        advanceUntilIdle()
        assertEquals(FlowState.Playing, flow.state.value)

        flow.stop()
        advanceUntilIdle()
        assertEquals(FlowState.Hidden, flow.state.value)
        assertTrue(service.stopCount >= 1)

        gate.complete(Unit)
    }

    @Test
    fun blankText_stopsPlaybackWithoutSpeaking() = runTest {
        val service = FakeSpeechService()
        val flow = controller(service, this)

        flow.read("   ")
        advanceUntilIdle()

        assertEquals(FlowState.Hidden, flow.state.value)
        assertTrue(service.spoken.isEmpty())
        assertTrue(service.stopCount >= 1)
    }

    @Test
    fun speechFailure_returnsToHiddenWithoutCrashing() = runTest {
        val service = FakeSpeechService()
        val flow = controller(service, this)
        service.failWith = IllegalStateException("no TTS engine")

        flow.read("will fail")
        advanceUntilIdle()

        assertEquals(FlowState.Hidden, flow.state.value)
    }

    @Test
    fun naturalCompletion_returnsToHidden() = runTest {
        val service = FakeSpeechService()
        val flow = controller(service, this)

        val gate = CompletableDeferred<Unit>()
        service.gate = gate

        flow.read("finished text")
        advanceUntilIdle()
        assertEquals(FlowState.Playing, flow.state.value)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(FlowState.Hidden, flow.state.value)
    }
}