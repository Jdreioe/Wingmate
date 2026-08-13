package io.github.jdreioe.wingmate.ui

import androidx.lifecycle.SavedStateHandle
import io.github.jdreioe.wingmate.domain.obf.BoardSetGraph
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardWorkspaceStateTest {
    @Test
    fun `following a board link records the current board and back restores it`() {
        val navigation = BoardWorkspaceState(selectedBoardId = "home")

        val linked = navigation.openBoard("people")

        assertEquals("people", linked.selectedBoardId)
        assertEquals(listOf("home"), linked.boardStack)
        assertTrue(linked.canNavigateBack)
        assertEquals(navigation, linked.navigateBack())
    }

    @Test
    fun `selecting a board or going home clears navigation history`() {
        val navigation = BoardWorkspaceState(
            selectedBoardId = "people",
            boardStack = listOf("home"),
        )

        assertEquals(
            BoardWorkspaceState(selectedBoardId = "places"),
            navigation.selectBoard("places"),
        )
        assertEquals(
            BoardWorkspaceState(selectedBoardId = "home"),
            navigation.goHome("home"),
        )
    }

    @Test
    fun `workspace location is restored after recreation`() {
        val savedState = SavedStateHandle()
        val first = BoardWorkspaceViewModel(savedState)
        first.onAction(
            BoardWorkspaceAction.Initialize(
                graph = graph(name = "Core words", boardIds = listOf("home", "people")),
                startInEditMode = false,
            )
        )
        first.onAction(BoardWorkspaceAction.OpenBoard("people"))

        val restored = BoardWorkspaceViewModel(savedState)

        assertEquals("people", restored.state.value.selectedBoardId)
        assertEquals(listOf("home"), restored.state.value.boardStack)
    }

    @Test
    fun `initialization falls back to the home board when restored board was removed`() {
        val savedState = SavedStateHandle(
            mapOf(
                "board_workspace.selected_board_id" to "removed-board",
                "board_workspace.board_stack" to arrayListOf("home", "removed-board"),
            )
        )
        val viewModel = BoardWorkspaceViewModel(savedState)

        viewModel.onAction(
            BoardWorkspaceAction.Initialize(
                graph = graph(name = "Core words", boardIds = listOf("home", "people")),
                startInEditMode = false,
            )
        )

        assertEquals("home", viewModel.state.value.selectedBoardId)
        assertTrue(viewModel.state.value.boardStack.isEmpty())
    }

    @Test
    fun `composed message survives recreation and can be edited afterwards`() {
        val savedState = SavedStateHandle()
        val first = BoardWorkspaceViewModel(savedState)
        first.onAction(
            BoardWorkspaceAction.ReplaceSentence(
                listOf(
                    ObfButton(id = "hello", label = "Hello", vocalization = "Hello"),
                    ObfButton(id = "world", label = "world", vocalization = "world"),
                )
            )
        )

        val restored = BoardWorkspaceViewModel(savedState)
        restored.onAction(BoardWorkspaceAction.RemoveLastSentenceButton)

        assertEquals(listOf("Hello"), restored.state.value.selectedButtons.map { it.vocalization })
    }

    @Test
    fun `edit session only records meaningful changes and undo restores the draft`() {
        val original = graph(name = "Core words")
        val session = BoardSetEditSession(original, original)

        assertEquals(session, session.apply(original))

        val changed = original.copy(boardSet = original.boardSet.copy(name = "My words"))
        val edited = session.apply(changed)

        assertTrue(edited.isDirty)
        assertEquals(listOf(original), edited.undoStack)
        assertEquals(original, edited.undo().draft)
        assertFalse(edited.undo().isDirty)
    }

    @Test
    fun `failed save keeps communication and edited draft visible`() {
        val original = graph(name = "Core words")
        val edited = original.copy(boardSet = original.boardSet.copy(name = "My words"))
        val viewModel = BoardWorkspaceViewModel(SavedStateHandle())
        val sentence = ObfButton(id = "hello", label = "Hello", vocalization = "Hello")
        viewModel.onAction(BoardWorkspaceAction.Initialize(original, startInEditMode = true))
        viewModel.onAction(BoardWorkspaceAction.ReplaceSentence(listOf(sentence)))
        viewModel.onAction(BoardWorkspaceAction.ApplyEdit(edited))
        viewModel.onAction(BoardWorkspaceAction.SaveStarted)

        viewModel.onAction(BoardWorkspaceAction.SaveFailed("Could not save"))

        assertEquals(BoardWorkspaceMode.Edit, viewModel.state.value.mode)
        assertEquals(edited, viewModel.state.value.activeGraph)
        assertEquals(listOf(sentence), viewModel.state.value.selectedButtons)
        assertEquals(
            BoardWorkspaceContentStatus.RecoverableFailure("Could not save"),
            viewModel.state.value.contentStatus,
        )
    }

    @Test
    fun `retrying a failed load returns to loading without clearing communication`() {
        val viewModel = BoardWorkspaceViewModel(SavedStateHandle())
        val sentence = ObfButton(id = "hello", label = "Hello", vocalization = "Hello")
        viewModel.onAction(BoardWorkspaceAction.ReplaceSentence(listOf(sentence)))
        viewModel.onAction(BoardWorkspaceAction.LoadFailed("Could not load"))

        viewModel.onAction(BoardWorkspaceAction.RetryLoad)

        assertEquals(BoardWorkspaceContentStatus.Loading, viewModel.state.value.contentStatus)
        assertEquals(1, viewModel.state.value.loadRequestId)
        assertEquals(listOf(sentence), viewModel.state.value.selectedButtons)
        assertEquals(null, viewModel.state.value.statusMessage)
    }

    @Test
    fun `dirty edit asks for confirmation and undo restores original graph`() {
        val original = graph(name = "Core words")
        val viewModel = BoardWorkspaceViewModel(SavedStateHandle())
        viewModel.onAction(BoardWorkspaceAction.Initialize(original, startInEditMode = true))
        viewModel.onAction(
            BoardWorkspaceAction.ApplyEdit(
                original.copy(boardSet = original.boardSet.copy(name = "My words"))
            )
        )

        viewModel.onAction(BoardWorkspaceAction.FinishEditingClicked)
        assertTrue(viewModel.state.value.showFinishDialog)

        viewModel.onAction(BoardWorkspaceAction.KeepEditing)
        viewModel.onAction(BoardWorkspaceAction.UndoEdit)
        assertEquals(original, viewModel.state.value.activeGraph)
        assertFalse(viewModel.state.value.editSession?.isDirty ?: true)
    }

    private fun graph(
        name: String,
        boardIds: List<String> = listOf("home"),
    ): BoardSetGraph {
        val boards = boardIds.map { boardId ->
            ObfBoard(
                format = "open-board-0.1",
                id = boardId,
                name = boardId.replaceFirstChar(Char::uppercase),
            )
        }
        return BoardSetGraph(
            boardSet = ObfBoardSet(
                id = "core",
                name = name,
                rootBoardId = boards.first().id,
                boardIds = boards.map(ObfBoard::id),
                createdAt = 1L,
                updatedAt = 1L,
            ),
            boards = boards,
        )
    }
}
