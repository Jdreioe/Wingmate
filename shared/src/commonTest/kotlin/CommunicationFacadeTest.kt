import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import io.github.jdreioe.wingmate.application.CommunicationFacade
import io.github.jdreioe.wingmate.application.HistoryStatus
import io.github.jdreioe.wingmate.application.bloc.PhraseListStore
import io.github.jdreioe.wingmate.application.bloc.PhraseListStoreFactory
import io.github.jdreioe.wingmate.application.usecase.AddPhraseUseCase
import io.github.jdreioe.wingmate.application.usecase.DeletePhraseUseCase
import io.github.jdreioe.wingmate.application.usecase.GetPhrasesAndCategoriesUseCase
import io.github.jdreioe.wingmate.application.usecase.UpdatePhraseUseCase
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.PhraseRepository
import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.domain.SaidTextRepository
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
import kotlin.test.assertTrue

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
    fun likelyNextUsesVisibleHistoryAndClearingRemovesSuggestions() = withFakeMain {
        val repository = InMemorySaidTextRepository()
        val facade = facade(repository)
        repository.add(SaidText(id = 1, saidText = "Hello", createdAt = 1000L))
        repository.add(SaidText(id = 2, saidText = "Water", audioFilePath = "/tmp/water.m4a", createdAt = 2000L))
        repository.add(SaidText(id = 3, saidText = "Private", createdAt = 3000L, visibleInHistory = false))
        repository.add(SaidText(id = 4, saidText = "Hello", createdAt = 4000L))

        val result = facade.likelyNext()
        assertEquals(listOf("Water"), result.phrases.map { it.text })
        assertEquals("/tmp/water.m4a", result.phrases.single().recordingPath)
        repository.deleteAll()
        assertTrue(facade.likelyNext().phrases.isEmpty())
    }

    @Test
    fun historyListsOnlyVisibleSaidTextsAsPhrases() = withFakeMain {
        val saidRepo = InMemorySaidTextRepository()
        val facade = facade(saidRepo)

        saidRepo.add(SaidText(id = 1, saidText = "Hello", audioFilePath = "/tmp/hello.m4a", createdAt = 1000L))
        saidRepo.add(SaidText(id = 2, saidText = "Hidden", createdAt = 2000L, visibleInHistory = false))

        val result = facade.history()
        val history = result.phrases

        assertEquals(HistoryStatus.Success, result.status)
        assertEquals(1, history.size)
        assertEquals("history-1", history[0].id)
        assertEquals("Hello", history[0].text)
        assertEquals("/tmp/hello.m4a", history[0].recordingPath)
        assertEquals(1000L, history[0].createdAt)
    }

    @Test
    fun unreadableHistoryIsAFailureRatherThanAnEmptyHistory() = withFakeMain {
        val facade = facade(saidRepo = object : SaidTextRepository by InMemorySaidTextRepository() {
            override suspend fun list(): List<SaidText> = throw IllegalStateException("database closed")
        })

        val result = facade.history()

        assertEquals(HistoryStatus.PersistenceFailure, result.status)
        assertTrue(result.isRetryable)
        assertTrue(result.phrases.isEmpty())
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

    @Test
    fun restorePhrasesRebuildsDeletedCategoryAndChildrenWithOriginalIds() = withFakeMain {
        val phraseRepo = InMemoryPhraseRepository()
        val category = Phrase(id = "cat-1", text = "Food", linkedBoardId = "cat-1", isGridItem = false, createdAt = 1L)
        val child = Phrase(id = "p-1", text = "Water", parentId = "cat-1", createdAt = 2L, recordingPath = "/tmp/water.m4a")
        phraseRepo.add(category)
        phraseRepo.add(child)
        val store = facade(phraseRepository = phraseRepo).phraseListStore()

        store.accept(PhraseListStore.Intent.DeleteCategory(categoryId = "cat-1"))
        withTimeout(5_000) {
            while (phraseRepo.getAll().isNotEmpty()) delay(10)
        }

        store.accept(PhraseListStore.Intent.RestorePhrases(listOf(category, child)))
        withTimeout(5_000) {
            while (phraseRepo.getAll().size != 2) delay(10)
        }
        assertEquals(listOf(category, child), phraseRepo.getAll())
    }

    @Test
    fun undoLastDeletionIsSerializedAndRestoresOriginalRepositoryOrder() = withFakeMain {
        val phraseRepo = InMemoryPhraseRepository()
        val first = Phrase(id = "p-0", text = "First", createdAt = 1L)
        val category = Phrase(id = "cat-1", text = "Food", linkedBoardId = "cat-1", isGridItem = false, createdAt = 2L)
        val childA = Phrase(id = "p-1", text = "Water", parentId = "cat-1", createdAt = 3L, recordingPath = "/tmp/water.m4a")
        val middle = Phrase(id = "p-2", text = "Other", createdAt = 4L)
        val childB = Phrase(id = "p-3", text = "Apple", parentId = "cat-1", createdAt = 5L)
        listOf(first, category, childA, middle, childB).forEach { phraseRepo.add(it) }
        val store = facade(phraseRepository = phraseRepo).phraseListStore()

        // Wait for initial load before deleting.
        withTimeout(5_000) {
            while (store.state.isLoading) delay(10)
        }

        // Delete the interleaved category subtree, then immediately trigger undo.
        store.accept(PhraseListStore.Intent.DeleteCategory(categoryId = "cat-1"))
        withTimeout(5_000) {
            while (store.state.lastDeletedUndo == null) delay(10)
        }
        val undoId = store.state.lastDeletedUndo!!.id
        assertEquals(true, store.state.lastDeletedUndo?.includesSubItems)
        assertEquals(listOf("p-0", "p-2"), phraseRepo.getAll().map { it.id })

        // Mismatched undo/dismiss IDs must not affect a newer deletion banner.
        store.accept(PhraseListStore.Intent.DismissDeletionUndo(undoId + 999))
        store.accept(PhraseListStore.Intent.UndoLastDeletion(undoId + 999))
        delay(50)
        assertEquals(undoId, store.state.lastDeletedUndo?.id)
        assertEquals(listOf("p-0", "p-2"), phraseRepo.getAll().map { it.id })

        store.accept(PhraseListStore.Intent.UndoLastDeletion(undoId))
        withTimeout(5_000) {
            while (store.state.lastDeletedUndo != null || phraseRepo.getAll().size != 5) delay(10)
        }
        assertEquals(
            listOf("p-0", "cat-1", "p-1", "p-2", "p-3"),
            phraseRepo.getAll().map { it.id },
        )
    }

    @Test
    fun failedDeletionDoesNotEmitUndoState() = withFakeMain {
        val base = InMemoryPhraseRepository()
        val category = Phrase(id = "cat-1", text = "Food", linkedBoardId = "cat-1", isGridItem = false, createdAt = 1L)
        val phrase = Phrase(id = "p-1", text = "Stay", parentId = "cat-1", createdAt = 2L, recordingPath = "/tmp/stay.m4a")
        base.add(category)
        base.add(phrase)
        val failingRepo = object : PhraseRepository by base {
            override suspend fun deleteAll(ids: List<String>) {
                throw IllegalStateException("disk full")
            }
        }
        val store = facade(phraseRepository = failingRepo).phraseListStore()
        withTimeout(5_000) {
            while (store.state.isLoading) delay(10)
        }

        store.accept(PhraseListStore.Intent.DeleteCategory(categoryId = "cat-1"))
        withTimeout(5_000) {
            while (store.state.error == null) delay(10)
        }
        assertEquals(null, store.state.lastDeletedUndo)
        assertEquals(listOf(category, phrase), base.getAll())
    }

    @Test
    fun undoRestoreStatusIsScopedAndUnrelatedErrorsDoNotFailPendingUndo() = withFakeMain {
        val base = InMemoryPhraseRepository()
        val p1 = Phrase(id = "p-1", text = "Keep", createdAt = 1L)
        val p2 = Phrase(id = "p-2", text = "Remove", createdAt = 2L)
        base.add(p1)
        base.add(p2)
        var failRestore = true
        val repo = object : PhraseRepository by base {
            override suspend fun update(phrase: Phrase): Phrase {
                throw IllegalStateException("unrelated update failure")
            }

            override suspend fun restoreAtIndices(entries: List<Pair<Phrase, Int>>) {
                if (failRestore) throw IllegalStateException("restore failed")
                base.restoreAtIndices(entries)
            }
        }
        val store = facade(phraseRepository = repo).phraseListStore()
        withTimeout(5_000) {
            while (store.state.isLoading) delay(10)
        }

        store.accept(PhraseListStore.Intent.DeletePhrase(phraseId = "p-2"))
        withTimeout(5_000) {
            while (store.state.lastDeletedUndo == null) delay(10)
        }
        val undoId = store.state.lastDeletedUndo!!.id
        assertEquals(PhraseListStore.UndoRestoreStatus.Idle, store.state.lastDeletedUndo?.restoreStatus)

        // An unrelated UpdatePhrase failure sets store.error without flipping lastDeletedUndo.restoreStatus.
        store.accept(PhraseListStore.Intent.UpdatePhrase(id = "p-1", text = "Broken", name = null))
        withTimeout(5_000) {
            while (store.state.error == null) delay(10)
        }
        assertEquals(PhraseListStore.UndoRestoreStatus.Idle, store.state.lastDeletedUndo?.restoreStatus)

        // A failing restore marks this specific undo entry as Failed while retaining it for retry.
        store.accept(PhraseListStore.Intent.UndoLastDeletion(undoId))
        withTimeout(5_000) {
            while (store.state.lastDeletedUndo?.restoreStatus != PhraseListStore.UndoRestoreStatus.Failed) delay(10)
        }
        assertEquals(undoId, store.state.lastDeletedUndo?.id)

        // Retrying once storage recovers restores the phrase and clears lastDeletedUndo.
        failRestore = false
        store.accept(PhraseListStore.Intent.UndoLastDeletion(undoId))
        withTimeout(5_000) {
            while (store.state.lastDeletedUndo != null) delay(10)
        }
        assertEquals(listOf("p-1", "p-2"), base.getAll().map { it.id })
    }

    @Test
    fun deletePublishesUndoEvenWhenPostDeleteReloadFails() = withFakeMain {
        val base = InMemoryPhraseRepository()
        val p1 = Phrase(id = "p-1", text = "Keep", createdAt = 1L)
        val p2 = Phrase(id = "p-2", text = "Remove", createdAt = 2L)
        base.add(p1)
        base.add(p2)
        var failNextReload = false
        val repo = object : PhraseRepository by base {
            override suspend fun deleteAll(ids: List<String>) {
                base.deleteAll(ids)
                failNextReload = true
            }

            override suspend fun getAll(): List<Phrase> {
                if (failNextReload) {
                    failNextReload = false
                    throw IllegalStateException("post-delete reload failed")
                }
                return base.getAll()
            }
        }
        val store = facade(phraseRepository = repo).phraseListStore()
        withTimeout(5_000) {
            while (store.state.isLoading) delay(10)
        }

        store.accept(PhraseListStore.Intent.DeletePhrase(phraseId = "p-2"))
        withTimeout(5_000) {
            while (store.state.lastDeletedUndo == null || store.state.error == null) delay(10)
        }
        val undo = store.state.lastDeletedUndo!!
        assertEquals(listOf("p-2"), undo.phrases.map { it.id })
        assertEquals(listOf("p-1"), store.state.phrases.map { it.id })

        // Undo still restores the committed deletion once storage reads succeed again.
        store.accept(PhraseListStore.Intent.UndoLastDeletion(undo.id))
        withTimeout(5_000) {
            while (store.state.lastDeletedUndo != null) delay(10)
        }
        assertEquals(listOf("p-1", "p-2"), base.getAll().map { it.id })
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
        saidRepo: SaidTextRepository = InMemorySaidTextRepository(),
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
