package io.github.jdreioe.wingmate.ui

import io.github.jdreioe.wingmate.domain.PhraseRecordingService
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.infrastructure.ImageCacher
import io.github.jdreioe.wingmate.infrastructure.NoopSpeechService
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhraseMediaControllerTest {

    @Test
    fun failedStartShowsTypedErrorAndNamesFileByDraftIdOnly() = runTest {
        val recorder = FakeRecorder(startResult = Result.failure(IllegalStateException("private phrase text")))
        val media = controller(recorder)

        media.startRecording()

        assertEquals(listOf<String?>("draft-1"), recorder.hints)
        assertEquals(PhraseMediaError.StartFailed, media.state.value.error)
        assertEquals(PhraseRecordingStatus.Idle, media.state.value.recordingStatus)
    }

    @Test
    fun finishingBeforeSaveKeepsNewRecording() = runTest {
        val recorder = FakeRecorder(stopResult = Result.success("/rec/new.m4a"))
        val media = controller(recorder, recordingPath = "/rec/old.m4a")
        media.startRecording()

        assertTrue(media.finishRecording())

        assertEquals("/rec/new.m4a", media.state.value.recordingPath)
        assertEquals(PhraseRecordingStatus.Idle, media.state.value.recordingStatus)
    }

    @Test
    fun failedFinalizeBlocksSaveAndKeepsPreviousRecording() = runTest {
        val recorder = FakeRecorder(stopResult = Result.failure(IllegalStateException("Recorded audio file is empty.")))
        val media = controller(recorder, recordingPath = "/rec/old.m4a")
        media.startRecording()

        assertFalse(media.finishRecording())

        assertEquals("/rec/old.m4a", media.state.value.recordingPath)
        assertEquals(PhraseMediaError.FinalizeFailed, media.state.value.error)
        assertEquals(PhraseRecordingStatus.Idle, media.state.value.recordingStatus)
    }

    @Test
    fun cancelledStartReleasesRecorderAndPropagates() = runTest {
        val recorder = FakeRecorder(startGate = CompletableDeferred())
        val media = controller(recorder)
        val job = launch { media.startRecording() }
        runCurrent()
        assertTrue(recorder.active)

        job.cancel()
        runCurrent()

        assertTrue(job.isCancelled)
        assertFalse(recorder.active)
        assertEquals(PhraseRecordingStatus.Idle, media.state.value.recordingStatus)
        assertNull(media.state.value.error)
    }

    @Test
    fun cancellationWinsWhenRecorderCleanupThrows() = runTest {
        val recorder = FakeRecorder(
            startGate = CompletableDeferred(),
            stopFailure = IllegalStateException("recorder already released"),
        )
        val media = controller(recorder)
        val job = launch { media.startRecording() }
        runCurrent()

        job.cancel()
        runCurrent()

        // A leaked cleanup exception would fail this test scope instead of cancelling the job.
        assertTrue(job.isCancelled)
        assertEquals(1, recorder.stops)
        assertEquals(PhraseRecordingStatus.Idle, media.state.value.recordingStatus)
    }

    @Test
    fun releaseStopsRecorderLeftRunningAtDisposal() = runTest {
        val recorder = FakeRecorder()
        val media = controller(recorder)
        media.startRecording()

        media.release()
        runCurrent()

        assertEquals(1, recorder.stops)
        assertFalse(recorder.active)
    }

    @Test
    fun playbackFailureIsShownButCancellationIsNot() = runTest {
        val failing = controller(FakeRecorder(), speech = FakeSpeech { false }, recordingPath = "/rec/a.m4a")
        failing.playRecording(textForHistory = null)
        assertEquals(PhraseMediaError.PlaybackFailed, failing.state.value.error)

        val cancelled = controller(FakeRecorder(), speech = FakeSpeech { awaitCancellation() }, recordingPath = "/rec/a.m4a")
        val job = launch { cancelled.playRecording(textForHistory = null) }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
        assertNull(cancelled.state.value.error)
    }

    @Test
    fun symbolFallsBackToRemoteUrlWhenCachingFails() = runTest {
        val images = object : ImageCacher {
            override suspend fun getCachedImagePath(url: String): String = throw IOException("offline")
        }
        val media = controller(FakeRecorder(), images = images)

        media.selectSymbol("https://symbols.example/cat.png")

        assertEquals("https://symbols.example/cat.png", media.state.value.imageUrl)
    }

    private fun TestScope.controller(
        recorder: PhraseRecordingService,
        speech: SpeechService? = null,
        images: ImageCacher? = null,
        recordingPath: String = "",
    ) = PhraseMediaController(
        recordings = recorder,
        speech = speech,
        images = images,
        releaseScope = backgroundScope,
        recordingHint = "draft-1",
        recordingPath = recordingPath,
    )

    private class FakeRecorder(
        private val startResult: Result<String> = Result.success("/rec/draft-1.m4a"),
        private val stopResult: Result<String> = Result.success("/rec/draft-1.m4a"),
        private val startGate: CompletableDeferred<Unit>? = null,
        private val stopFailure: Exception? = null,
    ) : PhraseRecordingService {
        override val isSupported = true
        val hints = mutableListOf<String?>()
        var active = false
        var stops = 0

        override fun isRecording() = active

        override suspend fun startRecording(phraseIdHint: String?): Result<String> {
            hints += phraseIdHint
            if (startResult.isSuccess) active = true
            startGate?.await()
            return startResult
        }

        override suspend fun stopRecording(): Result<String> {
            stops++
            active = false
            stopFailure?.let { throw it }
            return stopResult
        }
    }

    private class FakeSpeech(
        private val play: suspend () -> Boolean,
    ) : SpeechService by NoopSpeechService() {
        override suspend fun speakRecordedAudio(audioFilePath: String, textForHistory: String?, voice: Voice?): Boolean =
            play()
    }
}
