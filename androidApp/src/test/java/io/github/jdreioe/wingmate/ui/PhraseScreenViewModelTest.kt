package io.github.jdreioe.wingmate.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import io.github.jdreioe.wingmate.application.QueuedCommunicationSession
import io.github.jdreioe.wingmate.application.TypingScreenProjector
import io.github.jdreioe.wingmate.domain.CategoryItem
import io.github.jdreioe.wingmate.domain.CommunicationAction
import io.github.jdreioe.wingmate.domain.CommunicationSessionSnapshot
import io.github.jdreioe.wingmate.domain.Message
import io.github.jdreioe.wingmate.domain.MessagePart
import io.github.jdreioe.wingmate.domain.CommunicationSessionState
import io.github.jdreioe.wingmate.domain.MessagePartSource
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.PredictionResult
import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SpeechPolicy
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.obf.BoardActivationBehavior
import io.github.jdreioe.wingmate.domain.obf.BoardSetGraph
import io.github.jdreioe.wingmate.domain.obf.ObfButtonActionEffect
import io.github.jdreioe.wingmate.domain.phraseSubtree
import io.github.jdreioe.wingmate.infrastructure.InMemoryCommunicationSessionDataSource
import io.github.jdreioe.wingmate.infrastructure.InMemorySaidTextRepository
import io.github.jdreioe.wingmate.infrastructure.NoopSpeechService
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhraseScreenViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- Speak vs insert: the shared Message activation rules decide ---

    @Test
    fun `speak-only Phrase speaks it without changing the Message`() = runTest {
        val operations = operations(BoardActivationBehavior.SpeakOnly)
        val viewModel = PhraseScreenViewModel(SavedStateHandle(), operations)
        viewModel.type("Hello")

        viewModel.onAction(PhraseScreenAction.ActivatePhrase(water))

        assertEquals("Hello", operations.message)
        val spoken = operations.communicated.filterIsInstance<CommunicationAction.SpeakPart>().single()
        assertEquals(MessagePartSource.Phrase("water"), spoken.part.source)
        assertEquals("I would like some water", spoken.part.spokenText)
    }

    @Test
    fun `add-only Phrase is inserted at the cursor and stays silent`() = runTest {
        val operations = operations(BoardActivationBehavior.AddOnly)
        val viewModel = PhraseScreenViewModel(SavedStateHandle(), operations)
        viewModel.type("I want ")

        viewModel.onAction(PhraseScreenAction.ActivatePhrase(water))

        assertEquals("I want Water", operations.message)
        assertTrue(operations.messageSources.contains(MessagePartSource.Phrase("water")))
        assertEquals(TextFieldValue("I want Water", TextRange(12)), viewModel.state.value.input)
        assertTrue(operations.communicated.none { it is CommunicationAction.SpeakPart })
    }

    @Test
    fun `sentence-only policy composes silently until the Message is spoken explicitly`() = runTest {
        val operations = operations(
            BoardActivationBehavior.SpeakAndAdd,
            Settings(primaryLanguage = "en-US", speechPolicy = SpeechPolicy.SentenceOnly),
        )
        val viewModel = PhraseScreenViewModel(SavedStateHandle(), operations)

        viewModel.onAction(PhraseScreenAction.ActivatePhrase(water))
        assertTrue("Water" in operations.message)
        assertTrue(operations.communicated.none { it is CommunicationAction.SpeakPart })

        viewModel.onAction(PhraseScreenAction.Control(ObfButtonActionEffect.Speak))
        assertEquals(1, operations.communicated.filterIsInstance<CommunicationAction.SpeakActive>().size)
    }

    @Test
    fun `Phrase linked to a Category opens it instead of speaking`() = runTest {
        val operations = operations(BoardActivationBehavior.SpeakOnly)
        val viewModel = PhraseScreenViewModel(SavedStateHandle(), operations)
        val folder = phrase("drinks", "Drinks").copy(linkedBoardId = "drinks-board")

        viewModel.onAction(PhraseScreenAction.ActivatePhrase(folder))

        assertEquals("drinks", viewModel.state.value.selectedCategory?.id)
        assertTrue(operations.communicated.none { it is CommunicationAction.SpeakPart })
    }

    // --- Explicit controls and predictions ---

    @Test
    fun `explicit controls edit and speak the shared Message`() = runTest {
        val operations = operations()
        val viewModel = PhraseScreenViewModel(SavedStateHandle(), operations)
        val events = collectEvents(viewModel)

        viewModel.onAction(PhraseScreenAction.Control(ObfButtonActionEffect.AppendText("hi")))
        assertEquals("hi", operations.message)
        viewModel.onAction(PhraseScreenAction.Control(ObfButtonActionEffect.Backspace))
        assertEquals("h", operations.message)
        viewModel.onAction(PhraseScreenAction.Control(ObfButtonActionEffect.Speak))
        viewModel.onAction(PhraseScreenAction.Control(ObfButtonActionEffect.Clear))
        assertEquals("", operations.message)
        assertEquals("", viewModel.state.value.input.text)

        // An empty Message has nothing to speak; an unsupported button explains itself.
        viewModel.onAction(PhraseScreenAction.Control(ObfButtonActionEffect.Speak))
        viewModel.onAction(PhraseScreenAction.Control(ObfButtonActionEffect.Unsupported("unknown")))

        assertEquals(1, operations.communicated.filterIsInstance<CommunicationAction.SpeakActive>().size)
        assertEquals(listOf<PhraseScreenEvent>(PhraseScreenEvent.ContentUnavailable), events)
    }

    @Test
    fun `stale predictions are cancelled when the Message changes`() = runTest {
        val operations = operations().apply {
            predictionSource = { text ->
                when (text) {
                    "he" -> flow { delay(1_000); emit(PredictionResult(words = listOf("hello"))) }
                    "wa" -> flowOf(PredictionResult(words = listOf("water")))
                    else -> flowOf(PredictionResult())
                }
            }
        }
        val viewModel = PhraseScreenViewModel(SavedStateHandle(), operations)

        viewModel.type("he")
        advanceTimeBy(300)
        viewModel.type("wa")
        advanceTimeBy(2_000)
        assertEquals(listOf("water"), viewModel.state.value.predictions.words)

        viewModel.type("")
        assertEquals(PredictionResult(), viewModel.state.value.predictions)
    }

    @Test
    fun `choosing a prediction completes the partial word in the Message`() = runTest {
        val operations = operations()
        val viewModel = PhraseScreenViewModel(SavedStateHandle(), operations)
        viewModel.type("I want wa")

        viewModel.onAction(PhraseScreenAction.CompleteWord("water"))

        assertTrue(operations.message.startsWith("I want water"))
        assertEquals(operations.message, viewModel.state.value.input.text)
    }

    // --- Category navigation, loading, and recreation ---

    @Test
    fun `cursor restoration waits for persisted Message loading`() = runTest {
        val operations = operations()
        val delayedCommunication = MutableStateFlow(CommunicationSessionState())
        val delayedOperations = object : PhraseScreenOperations by operations {
            override val communication = delayedCommunication
        }
        val saved = SavedStateHandle(mapOf("cursor" to 6, "cursorEnd" to 11))
        val viewModel = PhraseScreenViewModel(saved, delayedOperations)

        delayedCommunication.value = CommunicationSessionState(
            snapshot = CommunicationSessionSnapshot(activeMessage = Message(listOf(MessagePart("Hello again")))),
            isInitialized = true,
        )

        assertEquals("Hello again", viewModel.state.value.input.text)
        assertEquals(TextRange(6, 11), viewModel.state.value.input.selection)
    }

    @Test
    fun `selected Category, dialog draft, and Message survive recreation`() = runTest {
        val operations = operations()
        val savedState = SavedStateHandle()
        val first = PhraseScreenViewModel(savedState, operations)
        first.type("Hi")
        first.onAction(PhraseScreenAction.SelectPage(TypingPageSelection.Category(food)))
        first.onAction(PhraseScreenAction.RequestDialog(TypingDialog.AddCategory))
        first.onAction(PhraseScreenAction.CategoryName("Snacks"))

        val restored = PhraseScreenViewModel(savedState, operations).state.value

        assertEquals(TypingPageSelection.Category(food), restored.selectedPage)
        assertEquals(TypingDialog.AddCategory, restored.dialog)
        assertEquals("Snacks", restored.categoryName)
        assertEquals("Hi", restored.input.text)
    }

    @Test
    fun `failed Category load blocks editing until retry without touching the Message`() = runTest {
        val operations = operations().apply { failCategories = true }
        val viewModel = PhraseScreenViewModel(SavedStateHandle(), operations)
        val events = collectEvents(viewModel)
        viewModel.type("Help")
        assertEquals(TypingLoadStatus.Failed, viewModel.state.value.categoryStatus)

        viewModel.onAction(PhraseScreenAction.RequestDialog(TypingDialog.AddPhrase))
        assertNull(viewModel.state.value.dialog)
        assertEquals(listOf<PhraseScreenEvent>(PhraseScreenEvent.ContentUnavailable), events)

        operations.failCategories = false
        viewModel.onAction(PhraseScreenAction.RetryLoad)

        assertEquals(TypingLoadStatus.Ready, viewModel.state.value.categoryStatus)
        assertEquals(listOf(food, people), viewModel.state.value.categories)
        assertEquals("Help", operations.message)
    }

    @Test
    fun `failed Phrase load blocks editing until retry`() = runTest {
        val operations = operations().apply { failPhrases = true }
        val viewModel = PhraseScreenViewModel(SavedStateHandle(), operations)
        assertEquals(TypingLoadStatus.Failed, viewModel.state.value.phraseStatus)
        assertFalse(viewModel.state.value.vocabularyReady)

        operations.failPhrases = false
        viewModel.onAction(PhraseScreenAction.RetryLoad)

        assertEquals(TypingLoadStatus.Ready, viewModel.state.value.phraseStatus)
        assertEquals(listOf(water, coffee), viewModel.state.value.phrases)
        assertTrue(viewModel.state.value.vocabularyReady)
    }

    // --- Failed writes keep the draft and the Message, then retry ---

    @Test
    fun `failed Category save keeps the draft and Message, and retry opens the new Category`() = runTest {
        val operations = operations()
        val viewModel = PhraseScreenViewModel(SavedStateHandle(), operations)
        viewModel.type("Hello")
        viewModel.onAction(PhraseScreenAction.RequestDialog(TypingDialog.AddCategory))
        viewModel.onAction(PhraseScreenAction.CategoryName("Snacks"))
        operations.failNextWrites = 1

        viewModel.onAction(PhraseScreenAction.SaveCategory)

        viewModel.state.value.let { state ->
            assertTrue(state.mutationFailed)
            assertFalse(state.mutationInProgress)
            assertEquals(TypingDialog.AddCategory, state.dialog)
            assertEquals("Snacks", state.categoryName)
        }
        assertEquals("Hello", operations.message)

        viewModel.onAction(PhraseScreenAction.RetryMutation)

        viewModel.state.value.let { state ->
            assertFalse(state.mutationFailed)
            assertNull(state.dialog)
            assertEquals("", state.categoryName)
            assertEquals("Snacks", state.selectedCategory?.name)
            assertTrue(state.categories.any { it.name == "Snacks" })
        }
        assertEquals("Hello", operations.message)
    }

    @Test
    fun `failed Phrase edit keeps the edited draft open, and retry saves it as an edit`() = runTest {
        val operations = operations()
        val viewModel = PhraseScreenViewModel(SavedStateHandle(), operations)
        viewModel.type("Hello")
        viewModel.onAction(PhraseScreenAction.RequestDialog(TypingDialog.EditPhrase, phraseId = "water"))
        val draft = water.copy(text = "Cold water")
        operations.failNextWrites = 1

        viewModel.onAction(PhraseScreenAction.SavePhrase(draft))

        viewModel.state.value.let { state ->
            assertTrue(state.mutationFailed)
            assertEquals(TypingDialog.EditPhrase, state.dialog)
            assertEquals(draft, state.dialogPhrase)
        }
        assertTrue(operations.saved.isEmpty())
        assertEquals("Hello", operations.message)

        viewModel.onAction(PhraseScreenAction.RetryMutation)

        assertEquals(listOf(draft to true), operations.saved)
        assertNull(viewModel.state.value.dialog)
        assertEquals("Hello", operations.message)
    }

    @Test
    fun `dismissing a failed edit discards its retry action`() = runTest {
        val operations = operations()
        val viewModel = PhraseScreenViewModel(SavedStateHandle(), operations)
        viewModel.type("Hello")
        viewModel.onAction(PhraseScreenAction.RequestDialog(TypingDialog.EditPhrase, phraseId = "water"))
        operations.failNextWrites = 1
        viewModel.onAction(PhraseScreenAction.SavePhrase(water.copy(text = "Cold water")))
        assertTrue(viewModel.state.value.mutationFailed)

        viewModel.onAction(PhraseScreenAction.DismissDialog)
        viewModel.onAction(PhraseScreenAction.RetryMutation)

        assertNull(viewModel.state.value.failedMutation)
        assertTrue(operations.saved.isEmpty())
        assertEquals("Hello", operations.message)
    }

    // --- Editing access ---

    @Test
    fun `opening an editor waits for unlock, and denying it opens nothing`() = runTest {
        val operations = operations().apply { locked = true }
        val viewModel = PhraseScreenViewModel(SavedStateHandle(), operations)
        val request = PhraseScreenAction.RequestDialog(TypingDialog.EditPhrase, phraseId = "water")

        viewModel.onAction(request)
        assertEquals(TypingDialog.Unlock, viewModel.state.value.dialog)
        viewModel.onAction(PhraseScreenAction.DismissDialog)
        assertNull(viewModel.state.value.dialog)

        viewModel.onAction(request)
        operations.locked = false
        viewModel.onAction(PhraseScreenAction.Unlocked)
        assertEquals(TypingDialog.EditPhrase, viewModel.state.value.dialog)
        assertEquals(water, viewModel.state.value.dialogPhrase)
    }

    @Test
    fun `an access check that finishes after the user backed out opens nothing`() = runTest {
        val accessCheck = CompletableDeferred<Unit>()
        val operations = operations().apply { unlockCheck = accessCheck }
        val viewModel = PhraseScreenViewModel(SavedStateHandle(), operations)

        viewModel.onAction(PhraseScreenAction.RequestDialog(TypingDialog.EditPhrase, phraseId = "water"))
        viewModel.onAction(PhraseScreenAction.DismissDialog)
        accessCheck.complete(Unit)

        assertNull(viewModel.state.value.dialog)
    }

    @Test
    fun `save after relock keeps edit versus add once unlocked`() = runTest {
        for ((dialog, editing) in listOf(TypingDialog.EditPhrase to true, TypingDialog.AddPhrase to false)) {
            val operations = operations()
            val viewModel = PhraseScreenViewModel(SavedStateHandle(), operations)
            viewModel.onAction(PhraseScreenAction.RequestDialog(dialog, phraseId = "water".takeIf { editing }))
            val draft = if (editing) water.copy(text = "Cold water") else phrase("tea", "Tea")
            operations.locked = true

            viewModel.onAction(PhraseScreenAction.SavePhrase(draft))
            assertEquals(TypingDialog.Unlock, viewModel.state.value.dialog)
            assertTrue(operations.saved.isEmpty())

            operations.locked = false
            viewModel.onAction(PhraseScreenAction.Unlocked)

            assertEquals(listOf(draft to editing), operations.saved)
            assertNull(viewModel.state.value.dialog)
        }
    }

    @Test
    fun `denying unlock on save returns to the form with its draft and writes nothing`() = runTest {
        for ((dialog, editing) in listOf(TypingDialog.EditPhrase to true, TypingDialog.AddPhrase to false)) {
            val operations = operations()
            val viewModel = PhraseScreenViewModel(SavedStateHandle(), operations)
            viewModel.type("Hello")
            viewModel.onAction(PhraseScreenAction.RequestDialog(dialog, phraseId = "water".takeIf { editing }))
            val draft = if (editing) water.copy(text = "Cold water") else phrase("tea", "Tea")
            operations.locked = true

            viewModel.onAction(PhraseScreenAction.SavePhrase(draft))
            viewModel.onAction(PhraseScreenAction.DismissDialog)

            assertEquals(dialog, viewModel.state.value.dialog)
            assertEquals(draft, viewModel.state.value.dialogPhrase)
            // A late unlock signal must not replay the denied write.
            operations.locked = false
            viewModel.onAction(PhraseScreenAction.Unlocked)
            assertTrue(operations.saved.isEmpty())
            assertEquals("Hello", operations.message)
        }
    }

    @Test
    fun `recreation during unlock returns to the form without replaying its write`() = runTest {
        val operations = operations()
        val savedState = SavedStateHandle()
        val first = PhraseScreenViewModel(savedState, operations)
        first.onAction(PhraseScreenAction.RequestDialog(TypingDialog.EditPhrase, phraseId = "water"))
        val draft = water.copy(text = "Cold water")
        operations.locked = true
        first.onAction(PhraseScreenAction.SavePhrase(draft))

        operations.locked = false
        val restored = PhraseScreenViewModel(savedState, operations)

        assertEquals(TypingDialog.EditPhrase, restored.state.value.dialog)
        assertEquals(draft, restored.state.value.dialogPhrase)
        assertTrue(operations.saved.isEmpty())
    }

    // --- Delete and undo ---

    @Test
    fun `undo is offered only after a delete succeeds and restores that deletion`() = runTest {
        val operations = operations()
        val viewModel = PhraseScreenViewModel(SavedStateHandle(), operations)
        val events = collectEvents(viewModel)
        operations.failNextWrites = 1

        viewModel.onAction(PhraseScreenAction.DeletePhrase("water"))
        assertTrue(viewModel.state.value.mutationFailed)
        assertTrue(events.isEmpty())
        assertTrue(operations.items.contains(water))

        viewModel.onAction(PhraseScreenAction.RetryMutation)
        val deleted = events.filterIsInstance<PhraseScreenEvent.Deleted>().single()
        assertFalse(operations.items.contains(water))

        viewModel.onAction(PhraseScreenAction.UndoDelete(deleted.deletionId))
        assertEquals(listOf(water to false), operations.saved)
        assertTrue(operations.items.contains(water))
    }

    @Test
    fun `undoing an older deletion does not restore a newer unrelated one`() = runTest {
        val operations = operations()
        val viewModel = PhraseScreenViewModel(SavedStateHandle(), operations)
        val events = collectEvents(viewModel)

        viewModel.onAction(PhraseScreenAction.DeletePhrase("water"))
        viewModel.onAction(PhraseScreenAction.DeletePhrase("coffee"))
        val (waterDeletion, coffeeDeletion) = events.filterIsInstance<PhraseScreenEvent.Deleted>()

        viewModel.onAction(PhraseScreenAction.UndoDelete(waterDeletion.deletionId))
        assertEquals(listOf(water to false), operations.saved)

        viewModel.onAction(PhraseScreenAction.UndoDelete(coffeeDeletion.deletionId))
        assertEquals(listOf(water to false, coffee to false), operations.saved)
    }

    // --- Cancellation ---

    @Test
    fun `a superseded template load is cancelled rather than reported as a failure`() = runTest {
        val firstLoad = CompletableDeferred<Unit>()
        val operations = operations().apply { templateGate = firstLoad }
        val viewModel = PhraseScreenViewModel(SavedStateHandle(), operations)
        val statuses = mutableListOf<TypingLoadStatus>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.map { it.templateStatus }.toList(statuses)
        }

        operations.templateGate = null
        operations.settings.update { it.copy(gridColumns = 6) }
        firstLoad.complete(Unit)

        assertEquals(TypingLoadStatus.Ready, viewModel.state.value.templateStatus)
        assertEquals(6, viewModel.state.value.template?.rootBoard?.grid?.columns)
        assertFalse(TypingLoadStatus.Failed in statuses)
    }

    // --- Helpers ---

    private fun PhraseScreenViewModel.type(text: String) =
        onAction(PhraseScreenAction.EditInput(TextFieldValue(text, TextRange(text.length))))

    private fun TestScope.collectEvents(viewModel: PhraseScreenViewModel): List<PhraseScreenEvent> =
        mutableListOf<PhraseScreenEvent>().also { events ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }
        }

    private fun TestScope.operations(
        activation: BoardActivationBehavior = BoardActivationBehavior.SpeakOnly,
        settings: Settings = Settings(primaryLanguage = "en-US"),
    ) = FakePhraseScreenOperations(backgroundScope, activation, settings).also { runCurrent() }

    private companion object {
        val water = phrase("water", "Water").copy(name = "I would like some water")
        val coffee = phrase("coffee", "Coffee")
        val food = CategoryItem(id = "food", name = "Food")
        val people = CategoryItem(id = "people", name = "People")

        fun phrase(id: String, text: String) = Phrase(id = id, text = text, createdAt = 1L)
    }

    /**
     * In-memory operations around a real [QueuedCommunicationSession], so Message
     * edits follow the shared domain rules instead of a hand-written copy.
     */
    private class FakePhraseScreenOperations(
        scope: CoroutineScope,
        private val activation: BoardActivationBehavior,
        initialSettings: Settings,
    ) : PhraseScreenOperations {
        override val settings = MutableStateFlow(initialSettings)
        private val session = QueuedCommunicationSession(
            dataSource = InMemoryCommunicationSessionDataSource(),
            speechService = NoopSpeechService(),
            saidTextRepository = InMemorySaidTextRepository(),
            currentSettings = { settings.value },
            scope = scope,
        )
        var items = listOf(water, coffee)
        override val communication: StateFlow<CommunicationSessionState> = session.state

        val communicated = mutableListOf<CommunicationAction>()
        val saved = mutableListOf<Pair<Phrase, Boolean>>()
        val message: String get() = session.state.value.activeMessage.displayText
        val messageSources: List<MessagePartSource> get() = session.state.value.activeMessage.parts.map { it.source }

        var failNextWrites = 0
        var failCategories = false
        var failPhrases = false
        var locked = false
        var unlockCheck: CompletableDeferred<Unit>? = null
        var templateGate: CompletableDeferred<Unit>? = null
        var predictionSource: (String) -> Flow<PredictionResult> = { flowOf(PredictionResult()) }
        private val categoryItems = mutableListOf(food, people)

        private fun write() {
            if (failNextWrites > 0) {
                failNextWrites--
                throw IOException("Storage unavailable")
            }
        }

        override suspend fun phrases(): List<Phrase> {
            if (failPhrases) throw IOException("Storage unavailable")
            return items
        }
        override fun communicate(action: CommunicationAction) {
            communicated += action
            session.accept(action)
        }

        override suspend fun savePhrase(phrase: Phrase, editing: Boolean) {
            write()
            saved += phrase to editing
            items = if (editing) items.map { if (it.id == phrase.id) phrase else it } else items + phrase
        }

        override suspend fun deletePhrase(id: String) {
            write()
            val removed = phraseSubtree(items, id).map { it.id }.toSet()
            items = items.filterNot { it.id in removed }
        }

        override suspend fun movePhrase(from: Int, to: Int) = write()

        override suspend fun template(columns: Int, reset: Boolean): BoardSetGraph {
            templateGate?.await()
            val graph = TypingScreenProjector.defaultTemplate(columns, now = 1L)
            return graph.copy(
                boardSet = graph.boardSet.copy(
                    screenSettings = graph.boardSet.screenSettings.copy(activationBehavior = activation),
                ),
            )
        }

        override suspend fun categories(): List<CategoryItem> {
            if (failCategories) throw IOException("Storage unavailable")
            return categoryItems.toList()
        }

        override suspend fun addCategory(category: CategoryItem): CategoryItem {
            write()
            return category.copy(id = "category-${categoryItems.size + 1}").also { categoryItems += it }
        }

        override suspend fun moveCategory(from: Int, to: Int) = write()
        override suspend fun deleteCategory(id: String) {
            write()
            categoryItems.removeAll { it.id == id }
        }

        override suspend fun history(): List<SaidText> = emptyList()
        override suspend fun voice(): Voice? = null
        override suspend fun requiresUnlock(): Boolean {
            unlockCheck?.await()
            return locked
        }

        override fun predictions(text: String) = predictionSource(text)
        override fun report(name: String, vararg properties: Pair<String, String>) = Unit
    }
}
