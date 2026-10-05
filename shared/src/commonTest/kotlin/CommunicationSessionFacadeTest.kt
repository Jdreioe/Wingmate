import io.github.jdreioe.wingmate.application.CommunicationSessionFacade
import io.github.jdreioe.wingmate.application.QueuedCommunicationSession
import io.github.jdreioe.wingmate.application.SessionNotice
import io.github.jdreioe.wingmate.application.SettingsStateManager
import io.github.jdreioe.wingmate.domain.CommunicationSessionDataSource
import io.github.jdreioe.wingmate.domain.CommunicationSessionSnapshot
import io.github.jdreioe.wingmate.domain.CommunicationStorageError
import io.github.jdreioe.wingmate.domain.CommunicationStorageResult
import io.github.jdreioe.wingmate.domain.MessagePartSource
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.obf.BoardActivationBehavior
import io.github.jdreioe.wingmate.infrastructure.InMemoryCommunicationSessionDataSource
import io.github.jdreioe.wingmate.infrastructure.InMemorySaidTextRepository
import io.github.jdreioe.wingmate.infrastructure.InMemorySettingsRepository
import io.github.jdreioe.wingmate.infrastructure.NoopSpeechService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class CommunicationSessionFacadeTest {
    @Test
    fun addedPhrasesAreSeparatedAndKeepTheirRecording() = facadeTest { facade ->
        val hello = Phrase(id = "hello", text = "Hello", recordingPath = "/hello.m4a", createdAt = 0)
        val world = Phrase(id = "world", text = "world", createdAt = 0)

        val afterHello = facade.activatePhrase(hello, 0, 0, BoardActivationBehavior.SpeakAndAdd)
        val afterWorld = facade.activatePhrase(world, afterHello.cursor, afterHello.cursor, BoardActivationBehavior.SpeakAndAdd)

        val message = facade.state().activeMessage
        assertEquals("Hello world ", message.displayText)
        assertEquals(message.displayText.length, afterWorld.cursor)
        assertEquals(true, afterWorld.shouldSpeak)
        val helloPart = message.parts.first()
        assertEquals(MessagePartSource.Phrase("hello"), helloPart.source)
        assertEquals("/hello.m4a", helloPart.recordingPath)
    }

    @Test
    fun aPhraseReplacesTheSelectedText() = facadeTest { facade ->
        facade.editText("I want coffee")
        val water = Phrase(id = "water", text = "water", createdAt = 0)

        facade.activatePhrase(water, start = 7, endExclusive = 13, BoardActivationBehavior.AddOnly)

        assertEquals("I want water ", facade.state().activeMessage.displayText)
    }

    @Test
    fun speakOnlyPhrasesLeaveTheMessageAlone() = facadeTest { facade ->
        val hello = Phrase(id = "hello", text = "Hello", createdAt = 0)

        val activation = facade.activatePhrase(hello, 0, 0, BoardActivationBehavior.SpeakOnly)

        assertEquals("", facade.state().activeMessage.displayText)
        assertEquals(true, activation.shouldSpeak)
    }

    @Test
    fun aMessageThatCannotBeLoadedAsksForRetry() = facadeTest(FailingDataSource()) { facade ->
        assertEquals(SessionNotice.StorageFailed, facade.state().notice)
    }

    /** Runs [block] once the session has loaded its Message. */
    private fun facadeTest(
        dataSource: CommunicationSessionDataSource = InMemoryCommunicationSessionDataSource(),
        block: (CommunicationSessionFacade) -> Unit,
    ) = runTest {
        // SettingsStateManager loads on Dispatchers.Main.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val session = QueuedCommunicationSession(
                dataSource = dataSource,
                speechService = NoopSpeechService(),
                saidTextRepository = InMemorySaidTextRepository(),
                currentSettings = { Settings() },
                scope = backgroundScope,
            )
            runCurrent()
            block(CommunicationSessionFacade(session, SettingsStateManager(InMemorySettingsRepository())))
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class FailingDataSource : CommunicationSessionDataSource {
        override suspend fun load(): CommunicationStorageResult<CommunicationSessionSnapshot> =
            CommunicationStorageResult.Failure(CommunicationStorageError.Unavailable)

        override suspend fun save(snapshot: CommunicationSessionSnapshot): CommunicationStorageResult<Unit> =
            CommunicationStorageResult.Failure(CommunicationStorageError.WriteFailed)
    }
}
