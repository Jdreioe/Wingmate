package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.CommunicationAction
import io.github.jdreioe.wingmate.domain.CommunicationFailureKind
import io.github.jdreioe.wingmate.domain.CommunicationPersistenceStatus
import io.github.jdreioe.wingmate.domain.CommunicationPlaybackStatus
import io.github.jdreioe.wingmate.domain.CommunicationSessionDataSource
import io.github.jdreioe.wingmate.domain.CommunicationSessionSnapshot
import io.github.jdreioe.wingmate.domain.CommunicationStorageError
import io.github.jdreioe.wingmate.domain.CommunicationStorageResult
import io.github.jdreioe.wingmate.domain.Message
import io.github.jdreioe.wingmate.domain.MessagePart
import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.TextSpan
import io.github.jdreioe.wingmate.domain.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class QueuedCommunicationSessionTest {
    @Test
    fun `message changes immediately while persistence runs in background`() = runTest {
        val dataSource = FakeSessionDataSource()
        val session = session(dataSource = dataSource)
        runCurrent()
        dataSource.saveGate = CompletableDeferred()

        session.accept(CommunicationAction.ReplaceRange(TextSpan(0, 0), "hello"))

        assertEquals("hello", session.state.value.activeMessage.displayText)
        runCurrent()
        assertEquals(CommunicationPersistenceStatus.Saving, session.state.value.persistenceStatus)
        dataSource.saveGate?.complete(Unit)
        runCurrent()
        assertEquals(CommunicationPersistenceStatus.Saved, session.state.value.persistenceStatus)
    }

    @Test
    fun `rapid speech requests keep immutable fifo order`() = runTest {
        val speech = RecordingSpeechService(blockFirstRequest = true)
        val session = session(speechService = speech)
        runCurrent()

        session.accept(CommunicationAction.SpeakPart(MessagePart("one"), null))
        session.accept(CommunicationAction.SpeakPart(MessagePart("two"), null))
        speech.firstStarted.await()
        session.accept(CommunicationAction.ReplaceMessage(Message(parts = listOf(MessagePart("changed")))))
        speech.releaseFirst.complete(Unit)
        runCurrent()

        assertEquals(listOf("one", "two"), speech.spoken)
        assertEquals("changed", session.state.value.activeMessage.displayText)
        assertEquals(0, session.state.value.queuedSpeechCount)
    }

    @Test
    fun `stop cancels current playback and discards pending speech`() = runTest {
        val speech = RecordingSpeechService(blockFirstRequest = true)
        val session = session(speechService = speech)
        runCurrent()
        session.accept(CommunicationAction.SpeakPart(MessagePart("one"), null))
        session.accept(CommunicationAction.SpeakPart(MessagePart("two"), null))
        speech.firstStarted.await()

        session.accept(CommunicationAction.Stop)
        runCurrent()

        assertEquals(listOf("one"), speech.spoken)
        assertEquals(1, speech.stopCount)
        assertEquals(0, session.state.value.queuedSpeechCount)
        assertNull(session.state.value.currentSpeechRequestId)
    }

    @Test
    fun `pause keeps current and pending speech until resume`() = runTest {
        val speech = RecordingSpeechService(blockFirstRequest = true)
        val session = session(speechService = speech)
        runCurrent()
        session.accept(CommunicationAction.SpeakPart(MessagePart("one"), null))
        session.accept(CommunicationAction.SpeakPart(MessagePart("two"), null))
        speech.firstStarted.await()

        session.accept(CommunicationAction.Pause)
        runCurrent()

        assertEquals(CommunicationPlaybackStatus.Paused, session.state.value.playbackStatus)
        assertEquals(1, session.state.value.queuedSpeechCount)
        assertEquals(1, speech.pauseCount)

        session.accept(CommunicationAction.Resume)
        speech.releaseFirst.complete(Unit)
        runCurrent()

        assertEquals(listOf("one", "two"), speech.spoken)
        assertEquals(1, speech.resumeCount)
    }

    @Test
    fun `pause during preparation prevents audio from starting`() = runTest {
        val speech = RecordingSpeechService()
        val session = session(speechService = speech)
        runCurrent()

        session.accept(CommunicationAction.SpeakPart(MessagePart("one"), null))
        session.accept(CommunicationAction.Pause)
        runCurrent()

        assertTrue(speech.spoken.isEmpty())
        assertEquals(CommunicationPlaybackStatus.Paused, session.state.value.playbackStatus)

        session.accept(CommunicationAction.Resume)
        advanceTimeBy(20)
        runCurrent()

        assertEquals(listOf("one"), speech.spoken)
    }

    @Test
    fun `successful full message records the spoken snapshot once`() = runTest {
        val history = FakeSaidTextRepository()
        val session = session(saidTextRepository = history)
        runCurrent()
        session.accept(CommunicationAction.ReplaceMessage(Message(parts = listOf(MessagePart("hello")))))
        session.accept(CommunicationAction.SpeakActive(Voice(name = "voice")))
        session.accept(CommunicationAction.ReplaceRange(TextSpan(0, 5), "changed"))
        runCurrent()

        assertEquals(listOf("hello"), history.items.map { it.saidText })
        assertEquals("changed", session.state.value.activeMessage.displayText)
    }

    @Test
    fun `preview speech and failed speech do not enter history`() = runTest {
        val history = FakeSaidTextRepository()
        val speech = RecordingSpeechService(failText = "bad")
        val session = session(speechService = speech, saidTextRepository = history)
        runCurrent()
        session.accept(CommunicationAction.SpeakPart(MessagePart("preview"), null))
        session.accept(CommunicationAction.ReplaceMessage(Message(parts = listOf(MessagePart("bad")))))
        session.accept(CommunicationAction.SpeakActive(null))
        runCurrent()

        assertTrue(history.items.isEmpty())
        assertEquals(CommunicationFailureKind.Playback, session.state.value.lastFailure?.kind)
    }

    @Test
    fun `recording-only button joins the speech queue`() = runTest {
        val speech = RecordingSpeechService()
        val session = session(speechService = speech)
        runCurrent()

        session.accept(
            CommunicationAction.SpeakPart(
                MessagePart(displayText = "", recordingPath = "audio/button.wav"),
                null,
            )
        )
        runCurrent()

        assertEquals(listOf("audio/button.wav"), speech.recordings)
    }

    @Test
    fun `persistence failure keeps state dirty until retry succeeds`() = runTest {
        val dataSource = FakeSessionDataSource(failWrites = true)
        val session = session(dataSource = dataSource)
        runCurrent()
        session.accept(CommunicationAction.ReplaceRange(TextSpan(0, 0), "hello"))
        runCurrent()

        assertEquals("hello", session.state.value.activeMessage.displayText)
        assertEquals(CommunicationPersistenceStatus.Failed, session.state.value.persistenceStatus)
        assertNotNull(session.state.value.lastFailure)

        dataSource.failWrites = false
        session.accept(CommunicationAction.RetryPersistence)
        runCurrent()

        assertEquals(CommunicationPersistenceStatus.Saved, session.state.value.persistenceStatus)
        assertNull(session.state.value.lastFailure)
        assertEquals("hello", dataSource.snapshot.activeMessage.displayText)
    }

    @Test
    fun `active and held messages restore without queued audio`() = runTest {
        val dataSource = FakeSessionDataSource(
            snapshot = CommunicationSessionSnapshot(
                activeMessage = Message(parts = listOf(MessagePart("active"))),
                heldMessage = Message(parts = listOf(MessagePart("held"))),
            )
        )
        val session = session(dataSource = dataSource)
        runCurrent()

        assertEquals("active", session.state.value.activeMessage.displayText)
        assertEquals("held", session.state.value.heldMessage?.displayText)
        assertEquals(0, session.state.value.queuedSpeechCount)
    }

    private fun kotlinx.coroutines.test.TestScope.session(
        dataSource: FakeSessionDataSource = FakeSessionDataSource(),
        speechService: RecordingSpeechService = RecordingSpeechService(),
        saidTextRepository: FakeSaidTextRepository = FakeSaidTextRepository(),
    ) = QueuedCommunicationSession(
        dataSource = dataSource,
        speechService = speechService,
        saidTextRepository = saidTextRepository,
        currentSettings = { Settings(primaryLanguage = "en-US", historyVisible = true) },
        scope = backgroundScope,
    )
}

private class FakeSessionDataSource(
    var snapshot: CommunicationSessionSnapshot = CommunicationSessionSnapshot(),
    var failWrites: Boolean = false,
) : CommunicationSessionDataSource {
    var saveGate: CompletableDeferred<Unit>? = null

    override suspend fun load(): CommunicationStorageResult<CommunicationSessionSnapshot> =
        CommunicationStorageResult.Success(snapshot)

    override suspend fun save(
        snapshot: CommunicationSessionSnapshot,
    ): CommunicationStorageResult<Unit> {
        saveGate?.await()
        return if (failWrites) {
            CommunicationStorageResult.Failure(CommunicationStorageError.WriteFailed)
        } else {
            this.snapshot = snapshot
            CommunicationStorageResult.Success(Unit)
        }
    }
}

private class FakeSaidTextRepository : SaidTextRepository {
    val items = mutableListOf<SaidText>()

    override suspend fun add(item: SaidText): SaidText = item.also(items::add)
    override suspend fun list(): List<SaidText> = items.toList()
    override suspend fun deleteAll() = items.clear()
    override suspend fun addAll(items: List<SaidText>) {
        this.items += items
    }
}

private class RecordingSpeechService(
    private val blockFirstRequest: Boolean = false,
    private val failText: String? = null,
) : SpeechService {
    val spoken = mutableListOf<String>()
    val recordings = mutableListOf<String>()
    val firstStarted = CompletableDeferred<Unit>()
    val releaseFirst = CompletableDeferred<Unit>()
    var stopCount = 0
    var pauseCount = 0
    var resumeCount = 0
    private var requestCount = 0

    override suspend fun speak(text: String, voice: Voice?, pitch: Double?, rate: Double?) = Unit
    override suspend fun speakSegments(
        segments: List<SpeechSegment>,
        voice: Voice?,
        pitch: Double?,
        rate: Double?,
    ) = Unit

    override suspend fun speakWithoutHistory(
        text: String,
        voice: Voice?,
        pitch: Double?,
        rate: Double?,
        cacheAudio: Boolean,
    ) {
        spoken += text
        requestCount++
        if (requestCount == 1) {
            firstStarted.complete(Unit)
            if (blockFirstRequest) releaseFirst.await()
        }
        if (text == failText) error("failed")
    }

    override suspend fun speakSegmentsWithoutHistory(
        segments: List<SpeechSegment>,
        voice: Voice?,
        pitch: Double?,
        rate: Double?,
        cacheAudio: Boolean,
    ) = speakWithoutHistory(segments.joinToString("") { it.text }, voice, pitch, rate, cacheAudio)

    override suspend fun speakRecordedAudio(
        audioFilePath: String,
        textForHistory: String?,
        voice: Voice?,
    ): Boolean {
        recordings += audioFilePath
        return true
    }

    override suspend fun pause() {
        pauseCount++
    }
    override suspend fun stop() {
        stopCount++
        releaseFirst.complete(Unit)
    }
    override suspend fun resume() {
        resumeCount++
    }
    override fun isPlaying(): Boolean = false
    override fun isPaused(): Boolean = false
}
