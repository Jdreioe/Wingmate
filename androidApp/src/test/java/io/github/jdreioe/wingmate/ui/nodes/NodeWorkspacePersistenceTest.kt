package io.github.jdreioe.wingmate.ui.nodes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.github.jdreioe.wingmate.domain.*
import io.github.jdreioe.wingmate.domain.nodes.*
import io.github.jdreioe.wingmate.infrastructure.FileNodeWorkspaceStorage
import io.github.jdreioe.wingmate.infrastructure.InMemoryFileStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NodeWorkspacePersistenceTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private var sequence = 0
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { models.clear(); Dispatchers.resetMain() }
    private fun model(storage: NodeWorkspaceStorage? = null, session: CommunicationSession? = null, voice: suspend () -> Voice? = { null }) =
        NodeWorkspaceViewModel(SavedStateHandle(), storage, session, voice).also { models.put("${sequence++}", it) }

    @Test fun `one vocabulary word can have repeated occurrences and replacement affects only selected occurrence`() = runTest(dispatcher) {
        val vm = model()
        vm.onAction(NodeAction.Input("I think I want coffee"))
        vm.onAction(NodeAction.AddWords)
        assertEquals(4, vm.state.value.library.vocabulary.size)
        assertEquals("I think I want coffee", vm.state.value.preview)
        vm.convertWord("tea", "tea ")
        vm.onAction(NodeAction.ReplaceWord("tea"))
        assertEquals("I think I want tea", vm.state.value.preview)
        vm.onAction(NodeAction.Select(1))
        vm.onAction(NodeAction.Delete)
        assertEquals("think I want tea", vm.state.value.preview)
        vm.onAction(NodeAction.NewMessage)
        assertTrue(vm.state.value.document.graph.nodes.isEmpty())
        assertEquals(5, vm.state.value.library.vocabulary.size)
        vm.onAction(NodeAction.Undo)
        assertEquals("think I want tea", vm.state.value.preview)
    }

    @Test fun `named workspaces positions frequencies and invalid drafts survive a fresh viewmodel`() = runTest(dispatcher) {
        val files = InMemoryFileStorage()
        val storage = FileNodeWorkspaceStorage(files, dispatcher)
        val vm = model(storage)
        advanceUntilIdle()
        vm.recordTyping("", "coffee coffee ")
        vm.onAction(NodeAction.Input("I want coffee"))
        vm.onAction(NodeAction.AddWords)
        vm.onAction(NodeAction.Move(1, 83f, 64f))
        vm.onAction(NodeAction.Draft("<speak>unfinished"))
        vm.onAction(NodeAction.WorkspaceName("Drinks"))
        vm.onAction(NodeAction.RenameWorkspace)
        val original = vm.state.value.document
        vm.onAction(NodeAction.WorkspaceName("Questions"))
        vm.onAction(NodeAction.CreateWorkspace)
        vm.onAction(NodeAction.Input("Where"))
        vm.onAction(NodeAction.AddWords)
        vm.onAction(NodeAction.OpenWorkspace("main"))
        advanceUntilIdle()
        assertEquals(NodeStorageStatus.Saved, vm.state.value.storageStatus)
        val restored = model(FileNodeWorkspaceStorage(files, dispatcher))
        advanceUntilIdle()
        assertEquals(original, restored.state.value.document)
        assertEquals(listOf("Drinks", "Questions"), restored.state.value.library.workspaces.map { it.name })
        assertEquals(2, restored.state.value.frequencies["coffee"])
        assertTrue(restored.state.value.dirty)
        assertNotNull(files.load(FileNodeWorkspaceStorage.FILE_NAME))
    }

    @Test fun `failed saves retain edits and retry writes latest snapshot in order`() = runTest(dispatcher) {
        val storage = FakeNodeStorage()
        val vm = model(storage)
        runCurrent()
        storage.saveGate = CompletableDeferred()
        vm.onAction(NodeAction.Input("coffee"))
        vm.onAction(NodeAction.AddWords)
        runCurrent()
        vm.onAction(NodeAction.Input("tea"))
        vm.onAction(NodeAction.AddWords)
        storage.failSave = true
        storage.saveGate!!.complete(Unit)
        runCurrent()
        assertEquals(NodeStorageStatus.SaveFailed, vm.state.value.storageStatus)
        assertEquals("coffee tea", vm.state.value.preview)
        storage.failSave = false
        vm.onAction(NodeAction.RetryStorage)
        runCurrent()
        assertEquals(NodeStorageStatus.Saved, vm.state.value.storageStatus)
        assertEquals(vm.state.value.library, storage.value)
    }

    @Test fun `unreadable saved data is never overwritten and queued conversion resumes on retry`() = runTest(dispatcher) {
        val storage = FakeNodeStorage().apply { failLoad = true }
        val vm = model(storage)
        runCurrent()
        vm.convertWord("coffee", "coffee ")
        vm.onAction(NodeAction.Input("overwrite"))
        vm.onAction(NodeAction.AddWords)
        runCurrent()
        assertEquals(NodeStorageStatus.LoadFailed, vm.state.value.storageStatus)
        assertNull(storage.value)
        storage.failLoad = false
        vm.onAction(NodeAction.RetryStorage)
        runCurrent()
        assertEquals(listOf("coffee"), vm.state.value.library.vocabulary.map { it.text })
        assertTrue(vm.state.value.document.graph.nodes.isEmpty())
        assertEquals(NodeStorageStatus.Saved, vm.state.value.storageStatus)
    }

    @Test fun `storage rejects malformed files and invalid graph connections`() = runTest(dispatcher) {
        val files = InMemoryFileStorage()
        files.save(FileNodeWorkspaceStorage.FILE_NAME, "{unfinished")
        val storage = FileNodeWorkspaceStorage(files, dispatcher)
        assertTrue(runCatching { storage.load() }.isFailure)
        assertEquals("{unfinished", files.load(FileNodeWorkspaceStorage.FILE_NAME))
        val invalid = NodeWorkspaceLibrary().withDocument(NodeDocument(SentenceGraph(listOf(
            SentenceNode(1, SentenceContent.Word("I"), 0f, 0f, next = 1),
        )))).withVocabulary(listOf("I"))
        assertTrue(runCatching { storage.save(invalid) }.isFailure)
        assertEquals("{unfinished", files.load(FileNodeWorkspaceStorage.FILE_NAME))
    }

    @Test fun `speak captures message before voice lookup and stop cancels a pending lookup`() = runTest(dispatcher) {
        val session = FakeNodeSession()
        val gate = CompletableDeferred<Voice?>()
        val vm = model(session = session, voice = { gate.await() })
        vm.onAction(NodeAction.Input("I want coffee"))
        vm.onAction(NodeAction.AddWords)
        vm.onAction(NodeAction.Speak)
        runCurrent()
        vm.convertWord("tea", "tea ")
        vm.onAction(NodeAction.ReplaceWord("tea"))
        gate.complete(null)
        runCurrent()
        val spoken = session.actions.single() as CommunicationAction.SpeakMessage
        assertEquals("I want coffee", spoken.message.displayText)
        assertEquals(listOf(SpeechSegment("I want coffee")), spoken.segments)
        assertEquals("I want tea", vm.state.value.preview)
        assertEquals("existing message", session.state.value.activeMessage.displayText)
        val stopped = model(session = session, voice = { awaitCancellation() })
        stopped.onAction(NodeAction.Input("hello"))
        stopped.onAction(NodeAction.AddWords)
        stopped.onAction(NodeAction.Speak)
        runCurrent()
        stopped.onAction(NodeAction.Stop)
        runCurrent()
        assertEquals(CommunicationAction.Stop, session.actions.last())
        assertFalse(stopped.state.value.resolvingVoice)
        assertEquals("hello", stopped.state.value.preview)
    }

    @Test fun `speech segments keep literal words and every pause in its place`() {
        val graph = SentenceGraph.fromContents(listOf(
            SentenceContent.Pause(200), SentenceContent.Word("I"), SentenceContent.Word("&"),
            SentenceContent.Pause(300), SentenceContent.Pause(100), SentenceContent.Word("I"), SentenceContent.Pause(500),
        ))
        assertEquals(listOf(SpeechSegment("", 200), SpeechSegment("I &", 400), SpeechSegment("I", 500)), graph.toSpeechSegments(1))
    }
}

private class FakeNodeStorage : NodeWorkspaceStorage {
    var value: NodeWorkspaceLibrary? = null
    var failLoad = false
    var failSave = false
    var saveGate: CompletableDeferred<Unit>? = null
    override suspend fun load(): NodeWorkspaceLibrary? { if (failLoad) error("unavailable"); return value }
    override suspend fun save(library: NodeWorkspaceLibrary) {
        saveGate?.await()
        if (failSave) error("unavailable")
        value = library
    }
}

private class FakeNodeSession : CommunicationSession {
    override val state = MutableStateFlow(CommunicationSessionState(snapshot = CommunicationSessionSnapshot(activeMessage = Message(parts = listOf(MessagePart("existing message")))), isInitialized = true))
    val actions = mutableListOf<CommunicationAction>()
    override fun accept(action: CommunicationAction) { actions += action }
    override suspend fun reloadAfterRestore() = Unit
}
