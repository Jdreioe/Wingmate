package io.github.jdreioe.wingmate.ui

import androidx.lifecycle.SavedStateHandle
import io.github.jdreioe.wingmate.domain.obf.BoardSetGraph
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import io.github.jdreioe.wingmate.domain.obf.ObfLoadBoard
import io.github.jdreioe.wingmate.domain.obf.findWords
import io.github.jdreioe.wingmate.domain.obf.withHomeFieldsBottomLeft
import org.junit.Assert.assertNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    fun `swiping moves one Page in Screen order, stops at the ends, and keeps Back`() {
        val pages = listOf("home", "food", "drinks").map { ObfBoard(format = "open-board-0.1", id = it) }
        val graph = BoardSetGraph(
            ObfBoardSet(id = "s", name = "S", rootBoardId = "home", boardIds = listOf("home", "food", "drinks"), createdAt = 1, updatedAt = 1),
            pages,
        )
        val onFood = BoardWorkspaceState(savedGraph = graph, selectedBoardId = "food", boardStack = listOf("home"))

        assertEquals("drinks", onFood.swipePage(1).selectedBoardId)
        assertEquals("home", onFood.swipePage(-1).selectedBoardId)
        assertEquals(listOf("home"), onFood.swipePage(1).boardStack)
        assertEquals(onFood.swipePage(1), onFood.swipePage(1).swipePage(1))
        assertEquals(onFood.copy(mode = BoardWorkspaceMode.Edit), onFood.copy(mode = BoardWorkspaceMode.Edit).swipePage(1))
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
    fun `the Message bar's Back returns through followed Pages but never leaves the Screen`() {
        val viewModel = BoardWorkspaceViewModel(SavedStateHandle())
        viewModel.onAction(
            BoardWorkspaceAction.Initialize(graph = graph(name = "Core words", boardIds = listOf("home", "people")), startInEditMode = false)
        )
        viewModel.onAction(BoardWorkspaceAction.OpenBoard("people"))

        viewModel.onAction(BoardWorkspaceAction.PreviousPageClicked)
        assertEquals("home", viewModel.state.value.selectedBoardId)

        viewModel.onAction(BoardWorkspaceAction.PreviousPageClicked)
        assertEquals("home", viewModel.state.value.selectedBoardId)
        assertFalse(viewModel.state.value.canNavigateBack)
    }

    @Test
    fun `stay here persists while remaining on the page and resets on every page exit`() {
        val viewModel = BoardWorkspaceViewModel(SavedStateHandle())
        viewModel.onAction(
            BoardWorkspaceAction.Initialize(
                graph = graph(name = "Core words", boardIds = listOf("home", "people", "actions")),
                startInEditMode = false,
            )
        )
        viewModel.onAction(BoardWorkspaceAction.OpenBoard("people"))
        assertFalse(viewModel.state.value.stayOnCurrentPage)

        viewModel.onAction(BoardWorkspaceAction.ToggleStayOnCurrentPage)
        assertTrue(viewModel.state.value.stayOnCurrentPage)

        // Staying on the same Page after a selection keeps the toggle active.
        viewModel.onAction(BoardWorkspaceAction.RestorePosition("people", listOf("home")))
        assertTrue(viewModel.state.value.stayOnCurrentPage)

        // Returning to another Page resets the toggle.
        viewModel.onAction(BoardWorkspaceAction.RestorePosition("home", emptyList()))
        assertFalse(viewModel.state.value.stayOnCurrentPage)

        // Navigating back resets the toggle.
        viewModel.onAction(BoardWorkspaceAction.OpenBoard("people"))
        viewModel.onAction(BoardWorkspaceAction.ToggleStayOnCurrentPage)
        viewModel.onAction(BoardWorkspaceAction.PreviousPageClicked)
        assertFalse(viewModel.state.value.stayOnCurrentPage)

        // Swiping to another Page resets the toggle.
        viewModel.onAction(BoardWorkspaceAction.OpenBoard("people"))
        viewModel.onAction(BoardWorkspaceAction.ToggleStayOnCurrentPage)
        viewModel.onAction(BoardWorkspaceAction.SwipePage(1))
        assertEquals("actions", viewModel.state.value.selectedBoardId)
        assertFalse(viewModel.state.value.stayOnCurrentPage)

        // Following a linked Page or going Home resets the toggle.
        viewModel.onAction(BoardWorkspaceAction.ToggleStayOnCurrentPage)
        viewModel.onAction(BoardWorkspaceAction.GoHome("home"))
        assertFalse(viewModel.state.value.stayOnCurrentPage)
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
    fun `legacy composed message can be imported exactly once`() {
        val buttons = listOf(
            ObfButton(id = "hello", label = "Hello", vocalization = "Hello"),
            ObfButton(id = "world", label = "world", vocalization = "world"),
        )
        val savedState = SavedStateHandle(
            mapOf("board_workspace.sentence_buttons" to Json.encodeToString(buttons))
        )
        val viewModel = BoardWorkspaceViewModel(savedState)

        assertEquals(buttons, viewModel.consumeLegacySentenceButtons())
        assertTrue(viewModel.consumeLegacySentenceButtons().isEmpty())
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
    fun `failed save keeps edited draft visible`() {
        val original = graph(name = "Core words")
        val edited = original.copy(boardSet = original.boardSet.copy(name = "My words"))
        val viewModel = BoardWorkspaceViewModel(SavedStateHandle())
        viewModel.onAction(BoardWorkspaceAction.Initialize(original, startInEditMode = true))
        viewModel.onAction(BoardWorkspaceAction.ApplyEdit(edited))
        viewModel.onAction(BoardWorkspaceAction.SaveStarted)

        viewModel.onAction(BoardWorkspaceAction.SaveFailed("Could not save"))

        assertEquals(BoardWorkspaceMode.Edit, viewModel.state.value.mode)
        assertEquals(edited, viewModel.state.value.activeGraph)
        assertEquals(
            BoardWorkspaceContentStatus.RecoverableFailure("Could not save"),
            viewModel.state.value.contentStatus,
        )
    }

    @Test
    fun `retrying a failed load returns to loading`() {
        val viewModel = BoardWorkspaceViewModel(SavedStateHandle())
        viewModel.onAction(BoardWorkspaceAction.LoadFailed("Could not load"))

        viewModel.onAction(BoardWorkspaceAction.RetryLoad)

        assertEquals(BoardWorkspaceContentStatus.Loading, viewModel.state.value.contentStatus)
        assertEquals(1, viewModel.state.value.loadRequestId)
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

    @Test
    fun `word finder guidance navigates to start page when needed, steps through pages, and completes on target`() {
        val home = ObfBoard(
            format = "open-board-0.1",
            id = "home",
            name = "Home",
            buttons = listOf(
                ObfButton(id = "to-food", label = "Food", loadBoard = ObfLoadBoard(id = "food", name = "Food")),
                ObfButton(id = "to-drinks", label = "Drinks", loadBoard = ObfLoadBoard(id = "drinks", name = "Drinks")),
            ),
            grid = ObfGrid(1, 2, listOf(listOf("to-food", "to-drinks"))),
        )
        val food = ObfBoard(
            format = "open-board-0.1",
            id = "food",
            name = "Food",
            buttons = listOf(ObfButton(id = "apple", label = "Apple")),
            grid = ObfGrid(1, 1, listOf(listOf("apple"))),
        )
        val drinks = ObfBoard(
            format = "open-board-0.1",
            id = "drinks",
            name = "Drinks",
            buttons = listOf(ObfButton(id = "water", label = "Water")),
            grid = ObfGrid(1, 1, listOf(listOf("water"))),
        )
        val graph = BoardSetGraph(
            boardSet = ObfBoardSet(
                id = "core",
                name = "Core",
                rootBoardId = "home",
                boardIds = listOf("home", "food", "drinks"),
                createdAt = 1L,
                updatedAt = 1L,
            ),
            boards = listOf(home, food, drinks),
        )
        val viewModel = BoardWorkspaceViewModel(SavedStateHandle())
        viewModel.onAction(BoardWorkspaceAction.Initialize(graph, startInEditMode = false))
        viewModel.onAction(BoardWorkspaceAction.OpenBoard("drinks"))
        assertEquals("drinks", viewModel.state.value.selectedBoardId)

        // Apple is unreachable from Drinks, so Word Finder falls back to Home.
        val match = graph.findWords("apple", currentBoardId = "drinks").single()
        assertTrue(match.startsFromRoot)

        // Starting guidance navigates to Home first and highlights Food without modifying vocabulary.
        viewModel.onAction(BoardWorkspaceAction.StartWordFinderGuidance(match))
        assertEquals("home", viewModel.state.value.selectedBoardId)
        assertTrue(viewModel.state.value.boardStack.isEmpty())
        assertEquals("to-food", viewModel.state.value.guidedButtonId)
        assertEquals(graph.withHomeFieldsBottomLeft(), viewModel.state.value.activeGraph)

        // Activating Food advances guidance and opening Food highlights Apple.
        viewModel.onAction(BoardWorkspaceAction.WordFinderButtonActivated("home", "to-food"))
        viewModel.onAction(BoardWorkspaceAction.OpenBoard("food"))
        assertEquals("food", viewModel.state.value.selectedBoardId)
        assertEquals("apple", viewModel.state.value.guidedButtonId)

        // Stepping back re-syncs guidance to Home -> Food.
        viewModel.onAction(BoardWorkspaceAction.PreviousPageClicked)
        assertEquals("home", viewModel.state.value.selectedBoardId)
        assertEquals("to-food", viewModel.state.value.guidedButtonId)

        // Returning to Food and activating Apple completes and clears guidance.
        viewModel.onAction(BoardWorkspaceAction.OpenBoard("food"))
        assertEquals("apple", viewModel.state.value.guidedButtonId)
        viewModel.onAction(BoardWorkspaceAction.WordFinderButtonActivated("food", "apple"))
        assertNull(viewModel.state.value.wordFinderGuidance)
        assertNull(viewModel.state.value.guidedButtonId)
    }

    @Test
    fun `word finder guidance can be cancelled and clears when entering edit mode`() {
        val home = ObfBoard(
            format = "open-board-0.1",
            id = "home",
            name = "Home",
            buttons = listOf(ObfButton(id = "apple", label = "Apple")),
            grid = ObfGrid(1, 1, listOf(listOf("apple"))),
        )
        val graph = BoardSetGraph(
            boardSet = ObfBoardSet(
                id = "core",
                name = "Core",
                rootBoardId = "home",
                boardIds = listOf("home"),
                createdAt = 1L,
                updatedAt = 1L,
            ),
            boards = listOf(home),
        )
        val match = graph.findWords("apple", currentBoardId = "home").single()
        val viewModel = BoardWorkspaceViewModel(SavedStateHandle())
        viewModel.onAction(BoardWorkspaceAction.Initialize(graph, startInEditMode = false))

        viewModel.onAction(BoardWorkspaceAction.StartWordFinderGuidance(match))
        assertEquals("apple", viewModel.state.value.guidedButtonId)

        viewModel.onAction(BoardWorkspaceAction.StopWordFinderGuidance)
        assertNull(viewModel.state.value.wordFinderGuidance)

        viewModel.onAction(BoardWorkspaceAction.StartWordFinderGuidance(match))
        viewModel.onAction(BoardWorkspaceAction.StartEditing)
        assertNull(viewModel.state.value.wordFinderGuidance)
    }

    @Test
    fun `bulk masking edits are undoable in the edit session`() {
        val original = BoardSetGraph(
            boardSet = ObfBoardSet(
                id = "core",
                name = "Core words",
                rootBoardId = "home",
                boardIds = listOf("home", "food"),
                createdAt = 1L,
                updatedAt = 1L,
            ),
            boards = listOf(
                ObfBoard(
                    format = "open-board-0.1",
                    id = "home",
                    name = "Home",
                    buttons = listOf(ObfButton(id = "b1", label = "More", hidden = false)),
                ),
                ObfBoard(
                    format = "open-board-0.1",
                    id = "food",
                    name = "Food",
                    buttons = listOf(ObfButton(id = "b2", label = "Apple", hidden = false)),
                ),
            ),
        )
        val viewModel = BoardWorkspaceViewModel(SavedStateHandle())
        viewModel.onAction(BoardWorkspaceAction.Initialize(original, startInEditMode = true))

        val hiddenScreen = io.github.jdreioe.wingmate.domain.obf.setDraftScreenButtonsHidden(original, hidden = true)
        viewModel.onAction(BoardWorkspaceAction.ApplyEdit(hiddenScreen))
        assertTrue(viewModel.state.value.activeGraph!!.boards.all { b -> b.buttons.all { it.hidden } })

        viewModel.onAction(BoardWorkspaceAction.UndoEdit)
        assertTrue(viewModel.state.value.activeGraph!!.boards.all { b -> b.buttons.none { it.hidden } })
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
