package io.github.jdreioe.wingmate.domain.obf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoardButtonVisibilityTest {
    private val hidden = ObfButton(id = "hidden", hidden = true)
    private val visible = ObfButton(id = "visible")

    @Test
    fun hiddenButtonRequiresEditModeOrSessionOverride() {
        assertFalse(isBoardButtonVisible(hidden, isEditMode = false, showHiddenButtons = false))
        assertTrue(isBoardButtonVisible(hidden, isEditMode = true, showHiddenButtons = false))
        assertTrue(isBoardButtonVisible(hidden, isEditMode = false, showHiddenButtons = true))
    }

    @Test
    fun ordinaryButtonIsAlwaysVisible() {
        assertTrue(isBoardButtonVisible(visible, isEditMode = false, showHiddenButtons = false))
    }

    @Test
    fun fieldFontScaleIsLargerForBiggerFieldsAndClamped() {
        assertEquals(1f, fieldFontScale(1, 1))
        assertTrue(fieldFontScale(2, 2) > fieldFontScale(1, 1))
        assertEquals(2f, fieldFontScale(10, 10))
        assertEquals(1f, fieldFontScale(0, 0))
        assertTrue(fieldFontScale(4, 4) in 1f..2f)
    }

    @Test
    fun bulkMaskingHelpersToggleAndUpdateVisibilityWhilePreservingGridPositions() {
        val page1Grid = ObfGrid(rows = 2, columns = 2, order = listOf(listOf("b1", "b2"), listOf(null, "b3")))
        val page1 = ObfBoard(
            format = "open-board-0.1",
            id = "p1",
            name = "Home",
            buttons = listOf(
                ObfButton(id = "b1", label = "More", hidden = false),
                ObfButton(id = "b2", label = "Stop", hidden = true),
                ObfButton(id = "b3", label = "Eat", hidden = false),
            ),
            grid = page1Grid,
        )
        val page2 = ObfBoard(
            format = "open-board-0.1",
            id = "p2",
            name = "Food",
            buttons = listOf(ObfButton(id = "b4", label = "Apple", hidden = false)),
            grid = ObfGrid(rows = 1, columns = 1, order = listOf(listOf("b4"))),
        )
        val graph = BoardSetGraph(
            boardSet = ObfBoardSet(
                id = "s1",
                name = "Core",
                rootBoardId = "p1",
                boardIds = listOf("p1", "p2"),
                createdAt = 1L,
                updatedAt = 1L,
            ),
            boards = listOf(page1, page2),
        )

        val toggled = toggleDraftButtonHidden(graph, "p1", "b1")
        assertTrue(toggled.boardsById.getValue("p1").buttons.first { it.id == "b1" }.hidden)
        assertEquals(page1Grid, toggled.boardsById.getValue("p1").grid)
        assertFalse(toggleDraftButtonHidden(toggled, "p1", "b1").boardsById.getValue("p1").buttons.first { it.id == "b1" }.hidden)
        assertEquals(graph, toggleDraftButtonHidden(graph, "p1", "missing"))

        val hiddenPage = setDraftPageButtonsHidden(graph, "p1", hidden = true)
        assertTrue(hiddenPage.boardsById.getValue("p1").buttons.all { it.hidden })
        assertFalse(hiddenPage.boardsById.getValue("p2").buttons.single().hidden)
        assertEquals(page1Grid, hiddenPage.boardsById.getValue("p1").grid)
        assertEquals(hiddenPage, setDraftPageButtonsHidden(hiddenPage, "p1", hidden = true))

        val hiddenScreen = setDraftScreenButtonsHidden(graph, hidden = true)
        assertTrue(hiddenScreen.boards.all { board -> board.buttons.all { it.hidden } })
        assertEquals(hiddenScreen, setDraftScreenButtonsHidden(hiddenScreen, hidden = true))

        val shownScreen = setDraftScreenButtonsHidden(hiddenScreen, hidden = false)
        assertTrue(shownScreen.boards.all { board -> board.buttons.none { it.hidden } })
        assertEquals(page1Grid, shownScreen.boardsById.getValue("p1").grid)
    }
}
