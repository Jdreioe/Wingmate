import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import io.github.jdreioe.wingmate.application.CommunicationFacade
import io.github.jdreioe.wingmate.application.bloc.PhraseListStore
import io.github.jdreioe.wingmate.application.bloc.PhraseListStoreFactory
import io.github.jdreioe.wingmate.application.usecase.AddPhraseUseCase
import io.github.jdreioe.wingmate.application.usecase.DeletePhraseUseCase
import io.github.jdreioe.wingmate.application.usecase.GetPhrasesAndCategoriesUseCase
import io.github.jdreioe.wingmate.application.usecase.UpdatePhraseUseCase
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.PhraseRepository
import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.infrastructure.InMemoryPhraseRepository
import io.github.jdreioe.wingmate.infrastructure.InMemorySaidTextRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    fun historyListsOnlyVisibleSaidTextsAsPhrases() = withFakeMain {
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

    @Test
    fun movingAPhraseTakesTheTargetsPlaceAcrossCategories() = withFakeMain {
        val phraseRepo = InMemoryPhraseRepository()
        phraseRepo.add(Phrase(id = "a", text = "A", createdAt = 1L))
        phraseRepo.add(Phrase(id = "food", text = "Food", linkedBoardId = "food", isGridItem = false, createdAt = 2L))
        phraseRepo.add(Phrase(id = "b", text = "B", createdAt = 3L))
        val store = facade(phraseRepository = phraseRepo).phraseListStore()

        store.accept(PhraseListStore.Intent.MovePhrase(phraseId = "b", targetId = "a"))

        withTimeout(5_000) {
            while (phraseRepo.getAll().map { it.id } != listOf("b", "a", "food")) delay(10)
        }
    }

    @Test
    fun addedPhrasesKeepTheirVocalizationImageAndRecording() = withFakeMain {
        val phraseRepo = InMemoryPhraseRepository()
        val store = facade(phraseRepository = phraseRepo).phraseListStore()

        store.accept(
            PhraseListStore.Intent.AddPhrase(
                text = "Hi",
                name = "Hello there",
                imageUrl = "/tmp/hi.png",
                recordingPath = "/tmp/hi.m4a",
            )
        )

        val added = withTimeout(5_000) {
            var phrase = phraseRepo.getAll().firstOrNull()
            while (phrase == null) {
                delay(10)
                phrase = phraseRepo.getAll().firstOrNull()
            }
            phrase
        }
        assertEquals("Hello there", added.name)
        assertEquals("/tmp/hi.png", added.imageUrl)
        assertEquals("/tmp/hi.m4a", added.recordingPath)
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
            phraseRepository = phraseRepo,
        ).create()
        return CommunicationFacade(
            phraseListStore = phraseListStore,
            saidTextRepository = saidRepo,
        )
    }
}
