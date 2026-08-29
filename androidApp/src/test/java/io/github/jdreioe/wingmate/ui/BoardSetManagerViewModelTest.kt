package io.github.jdreioe.wingmate.ui

import androidx.lifecycle.SavedStateHandle
import io.github.jdreioe.wingmate.application.KeyboardPreset
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.domain.obf.ScreenKind
import io.github.jdreioe.wingmate.infrastructure.BoardImportResult
import io.github.jdreioe.wingmate.infrastructure.QuickCoreDownloadProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BoardSetManagerViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initialize loads board sets and workspace actions update the route`() = runTest {
        val operations = FakeBoardSetManagerOperations().apply {
            boardSets += boardSet("set-1", "Core words")
        }
        val viewModel = BoardSetManagerViewModel(SavedStateHandle(), operations)

        viewModel.onAction(BoardSetManagerAction.Initialize(false, null))

        assertFalse(viewModel.state.value.isLoading)
        assertEquals(listOf("set-1"), viewModel.state.value.boardSets.map { it.id })
        assertEquals(1, operations.cacheAllCalls)

        viewModel.onAction(BoardSetManagerAction.EditClicked("set-1"))
        assertEquals(
            BoardSetManagerRoute.Workspace("set-1", BoardWorkspaceMode.Edit),
            viewModel.state.value.route,
        )

        viewModel.onAction(BoardSetManagerAction.WorkspaceExited)
        assertEquals(BoardSetManagerRoute.Library, viewModel.state.value.route)
    }

    @Test
    fun `system typing screen can be opened for editing but never as a startup screen`() = runTest {
        val operations = FakeBoardSetManagerOperations().apply {
            boardSets += boardSet("typing", "Typing").copy(kind = ScreenKind.Typing)
        }
        val runViewModel = BoardSetManagerViewModel(SavedStateHandle(), operations)

        runViewModel.onAction(
            BoardSetManagerAction.Initialize(
                createOnLaunch = false,
                initialBoardSetId = "typing",
                initialMode = BoardWorkspaceMode.Run,
            )
        )

        assertEquals(BoardSetManagerRoute.Library, runViewModel.state.value.route)

        val editViewModel = BoardSetManagerViewModel(SavedStateHandle(), operations)
        editViewModel.onAction(
            BoardSetManagerAction.Initialize(
                createOnLaunch = false,
                initialBoardSetId = "typing",
                initialMode = BoardWorkspaceMode.Edit,
            )
        )

        assertEquals(
            BoardSetManagerRoute.Workspace("typing", BoardWorkspaceMode.Edit),
            editViewModel.state.value.route,
        )
    }

    @Test
    fun `saved workspace route and dialogs are restored after recreation`() {
        val savedState = SavedStateHandle()
        val first = BoardSetManagerViewModel(savedState, FakeBoardSetManagerOperations())

        first.onAction(BoardSetManagerAction.OpenClicked("set-7"))
        first.onAction(BoardSetManagerAction.CreateClicked)
        first.onAction(BoardSetManagerAction.CreateNameChanged("My board"))
        first.onAction(BoardSetManagerAction.CreateRowsChanged("6"))
        first.onAction(BoardSetManagerAction.SettingsClicked)

        val restored = BoardSetManagerViewModel(savedState, FakeBoardSetManagerOperations())

        assertEquals(
            BoardSetManagerRoute.Workspace("set-7", BoardWorkspaceMode.Run),
            restored.state.value.route,
        )
        assertTrue(restored.state.value.showCreateDialog)
        assertEquals("My board", restored.state.value.createDraft.name)
        assertEquals("6", restored.state.value.createDraft.rowsText)
        assertTrue(restored.state.value.showSettings)
    }

    @Test
    fun `protected deletion waits for access before deleting`() = runTest {
        val operations = FakeBoardSetManagerOperations().apply {
            boardSets += boardSet("set-1", "Core words")
            deleteRequiresUnlock = true
        }
        val viewModel = BoardSetManagerViewModel(SavedStateHandle(), operations)
        viewModel.onAction(BoardSetManagerAction.Initialize(false, null))

        viewModel.onAction(BoardSetManagerAction.DeleteClicked("set-1"))
        viewModel.onAction(BoardSetManagerAction.DeleteConfirmed)

        assertTrue(viewModel.state.value.showDeleteAccessDialog)
        assertEquals("set-1", viewModel.state.value.pendingProtectedDeleteId)
        assertTrue(operations.deletedIds.isEmpty())

        viewModel.onAction(BoardSetManagerAction.DeleteAccessGranted)

        assertFalse(viewModel.state.value.showDeleteAccessDialog)
        assertEquals(listOf("set-1"), operations.deletedIds)
        assertTrue(viewModel.state.value.boardSets.isEmpty())
    }

    @Test
    fun `creating a blank board set opens it in edit mode`() = runTest {
        val operations = FakeBoardSetManagerOperations()
        val viewModel = BoardSetManagerViewModel(SavedStateHandle(), operations)
        viewModel.onAction(BoardSetManagerAction.Initialize(false, null))

        viewModel.onAction(
            BoardSetManagerAction.CreateSubmitted(
                name = " My board ",
                rows = 4,
                columns = 8,
                template = BoardSetTemplate.Blank,
                keyboardPreset = KeyboardPreset.Qwerty,
                defaultBoardName = "Home",
            )
        )

        assertEquals("My board", operations.createdNames.single())
        assertEquals(
            BoardSetManagerRoute.Workspace("created-1", BoardWorkspaceMode.Edit),
            viewModel.state.value.route,
        )
        assertFalse(viewModel.state.value.isCreating)
    }

    @Test
    fun `back action resets the route and emits navigation once`() = runTest {
        val viewModel = BoardSetManagerViewModel(SavedStateHandle(), FakeBoardSetManagerOperations())
        viewModel.onAction(BoardSetManagerAction.OpenClicked("set-1"))
        val event = async { viewModel.events.first() }

        viewModel.onAction(BoardSetManagerAction.BackClicked)

        assertEquals(BoardSetManagerRoute.Library, viewModel.state.value.route)
        assertEquals(BoardSetManagerEvent.NavigateBack, event.await())
    }

    private fun boardSet(id: String, name: String) = ObfBoardSet(
        id = id,
        name = name,
        rootBoardId = "$id-root",
        boardIds = listOf("$id-root"),
        createdAt = 1L,
        updatedAt = 2L,
    )

    private inner class FakeBoardSetManagerOperations : BoardSetManagerOperations {
        override val quickCoreProgress = MutableStateFlow(QuickCoreDownloadProgress())
        val boardSets = mutableListOf<ObfBoardSet>()
        val deletedIds = mutableListOf<String>()
        val createdNames = mutableListOf<String>()
        var deleteRequiresUnlock = false
        var cacheAllCalls = 0

        override suspend fun listBoardSets() = boardSets.toList()
        override suspend fun getBoardSet(id: String) = boardSets.firstOrNull { it.id == id }
        override suspend fun duplicateBoardSet(id: String) = Unit
        override suspend fun toggleLocked(id: String) = Unit
        override suspend fun deleteBoardSet(id: String) {
            deletedIds += id
            boardSets.removeAll { it.id == id }
        }

        override suspend fun cacheAll() {
            cacheAllCalls++
        }

        override suspend fun cacheBoardSet(id: String) = Unit
        override suspend fun requiresDeleteUnlock() = deleteRequiresUnlock
        override suspend fun importBoardSet(): BoardImportResult = BoardImportResult.Cancelled
        override suspend fun importQuickCore(slug: String): BoardImportResult = BoardImportResult.Cancelled
        override suspend fun renameBoardSet(id: String, name: String) = null

        override suspend fun createBoardSet(
            name: String,
            rows: Int,
            columns: Int,
            rootBoardName: String,
        ): ObfBoardSet {
            createdNames += name
            return boardSet("created-1", name).also(boardSets::add)
        }

        override suspend fun createCalculatorBoardSet(name: String) = boardSet("calculator-1", name)
        override suspend fun createKeyboardBoardSet(name: String, preset: KeyboardPreset) =
            boardSet("keyboard-1", name)
    }
}
