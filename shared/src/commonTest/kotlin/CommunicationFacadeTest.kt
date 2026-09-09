import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import io.github.jdreioe.wingmate.application.CommunicationFacade
import io.github.jdreioe.wingmate.application.bloc.PhraseListStoreFactory
import io.github.jdreioe.wingmate.application.usecase.AddPhraseUseCase
import io.github.jdreioe.wingmate.application.usecase.DeletePhraseUseCase
import io.github.jdreioe.wingmate.application.usecase.GetAllItemsUseCase
import io.github.jdreioe.wingmate.application.usecase.GetPhrasesAndCategoriesUseCase
import io.github.jdreioe.wingmate.application.usecase.MovePhraseUseCase
import io.github.jdreioe.wingmate.application.usecase.UpdatePhraseUseCase
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.PhraseRepository
import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.infrastructure.InMemoryPhraseRepository
import io.github.jdreioe.wingmate.infrastructure.InMemorySaidTextRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommunicationFacadeTest {
    // PhraseListStore's CoroutineExecutor runs on Dispatchers.Main. Faking Main
    // with the runTest scheduler (inside each test) lets the store's virtual-time
    // delays resolve instead of hanging.
    private fun withFakeMain(block: suspend CoroutineScope.() -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun historyListsOnlyVisibleSaidTextsAsPhrases() = runBlocking {
        val saidRepo = InMemorySaidTextRepository()
        val facade = facade(saidRepo)

        saidRepo.add(SaidText(id = 1, saidText = "Hello", audioFilePath = "/tmp/hello.m4a", createdAt = 1000L))
        saidRepo.add(SaidText(id = 2, saidText = "Hidden", createdAt = 2000L, visibleInHistory = false))

        val history = facade.listHistoryAsPhrases()

        assertEquals(1, history.size)
        assertEquals("history-1", history[0].id)
        assertEquals("Hello", history[0].text)
        assertEquals("/tmp/hello.m4a", history[0].recordingPath)
        assertEquals(1000L, history[0].createdAt)
    }

    @Test
    fun nullRecordingPathThroughFacadeClearsTheStoredRecording() = withFakeMain {
        val phraseRepo = InMemoryPhraseRepository()
        val facade = facade(phraseRepository = phraseRepo)
        phraseRepo.add(Phrase(id = "p1", text = "Hello", createdAt = 1L, recordingPath = "/tmp/hello.m4a"))

        facade.updatePhraseRecording(phraseId = "p1", recordingPath = null)

        assertEquals(null, awaitRecordingPath(phraseRepo, "p1") { it == null })
    }

    @Test
    fun nonNullRecordingPathThroughFacadeStoresTheNewPath() = withFakeMain {
        val phraseRepo = InMemoryPhraseRepository()
        val facade = facade(phraseRepository = phraseRepo)
        phraseRepo.add(Phrase(id = "p1", text = "Hello", createdAt = 1L))

        facade.updatePhraseRecording(phraseId = "p1", recordingPath = "/tmp/new.m4a")

        assertEquals("/tmp/new.m4a", awaitRecordingPath(phraseRepo, "p1") { it == "/tmp/new.m4a" })
    }

    /** Store intents process asynchronously; wait until the phrase reaches the expected path. */
    private suspend fun awaitRecordingPath(
        phraseRepo: PhraseRepository,
        phraseId: String,
        expected: (String?) -> Boolean,
    ): String? {
        return withTimeout(5_000) {
            var current = phraseRepo.getAll().first { it.id == phraseId }.recordingPath
            while (!expected(current)) {
                delay(10)
                current = phraseRepo.getAll().first { it.id == phraseId }.recordingPath
            }
            current
        }
    }

    private fun facade(
        saidRepo: io.github.jdreioe.wingmate.domain.SaidTextRepository = InMemorySaidTextRepository(),
        phraseRepository: PhraseRepository = InMemoryPhraseRepository(),
    ): CommunicationFacade {
        val phraseRepo = phraseRepository
        val phraseListStore = PhraseListStoreFactory(
            storeFactory = DefaultStoreFactory(),
            getPhrasesAndCategoriesUseCase = GetPhrasesAndCategoriesUseCase(phraseRepo),
            addPhraseUseCase = AddPhraseUseCase(phraseRepo),
            deletePhraseUseCase = DeletePhraseUseCase(phraseRepo),
            updatePhraseUseCase = UpdatePhraseUseCase(phraseRepo),
            movePhraseUseCase = MovePhraseUseCase(phraseRepo),
            getAllItemsUseCase = GetAllItemsUseCase(phraseRepo),
            phraseRepository = phraseRepo,
        ).create()
        return CommunicationFacade(
            phraseListStore = phraseListStore,
            saidTextRepository = saidRepo,
        )
    }
}
