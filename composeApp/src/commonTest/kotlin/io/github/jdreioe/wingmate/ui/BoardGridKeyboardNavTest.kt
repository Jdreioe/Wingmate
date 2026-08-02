package io.github.jdreioe.wingmate.ui

import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoardGridKeyboardNavTest {

    private val visible: (ObfButton) -> Boolean = { !it.hidden }

    private fun grid(order: List<List<String?>>): ObfGrid = ObfGrid(
        rows = order.size,
        columns = order.first().size,
        order = order
    )

    private fun buttons(vararg ids: String): Map<String, ObfButton> =
        ids.associateWith { ObfButton(id = it, label = it) }

    @Test
    fun firstFocusableCellIsEmptyWhenThereAreNoButtons() {
        val grid = grid(listOf(listOf(null, null), listOf(null, null)))
        assertNull(firstFocusableBoardCell(grid, emptyMap(), visible))
    }

    @Test
    fun firstFocusableCellIsRowMajorFirstVisibleButton() {
        val grid = grid(listOf(listOf("a", null), listOf("b", "c")))
        val buttons = buttons("a", "b", "c")
        assertEquals(0 to 0, firstFocusableBoardCell(grid, buttons, visible))
    }

    @Test
    fun firstFocusableCellSkipsHiddenAndEmptyLeadingCells() {
        val grid = grid(listOf(listOf(null, "h"), listOf("a", "b")))
        val buttons = mapOf(
            "a" to ObfButton(id = "a", label = "a"),
            "h" to ObfButton(id = "h", label = "h", hidden = true),
            "b" to ObfButton(id = "b", label = "b")
        )
        assertEquals(1 to 0, firstFocusableBoardCell(grid, buttons, visible))
    }

    @Test
    fun stepRightAdvancesColumnAndSkipsEmptyCells() {
        val grid = grid(listOf(listOf("a", null, "b", "c")))
        val buttons = buttons("a", "b", "c")
        assertEquals(0 to 2, stepFocusableBoardCell(grid, buttons, visible, 0 to 0, 0, 1))
        assertEquals(0 to 3, stepFocusableBoardCell(grid, buttons, visible, 0 to 2, 0, 1))
    }

    @Test
    fun stepDownAdvancesRow() {
        val grid = grid(listOf(listOf("a", "b"), listOf("c", "d")))
        val buttons = buttons("a", "b", "c", "d")
        assertEquals(1 to 0, stepFocusableBoardCell(grid, buttons, visible, 0 to 0, 1, 0))
    }

    @Test
    fun stepLeftAtEdgeKeepsCurrentCell() {
        val grid = grid(listOf(listOf("a", "b")))
        val buttons = buttons("a", "b")
        assertEquals(0 to 0, stepFocusableBoardCell(grid, buttons, visible, 0 to 0, 0, -1))
    }

    @Test
    fun stepUpAtEdgeKeepsCurrentCell() {
        val grid = grid(listOf(listOf("a"), listOf("b")))
        val buttons = buttons("a", "b")
        assertEquals(0 to 0, stepFocusableBoardCell(grid, buttons, visible, 0 to 0, -1, 0))
    }

    @Test
    fun stepFromNullFallsBackToFirstFocusableCell() {
        val grid = grid(listOf(listOf(null, "a"), listOf("b", "c")))
        val buttons = buttons("a", "b", "c")
        assertEquals(0 to 1, stepFocusableBoardCell(grid, buttons, visible, null, 0, 1))
        assertEquals(0 to 1, stepFocusableBoardCell(grid, buttons, visible, null, -1, 0))
    }

    @Test
    fun stepSkipsHiddenButtons() {
        val grid = grid(listOf(listOf("a", "h", "b")))
        val buttons = mapOf(
            "a" to ObfButton(id = "a", label = "a"),
            "h" to ObfButton(id = "h", label = "h", hidden = true),
            "b" to ObfButton(id = "b", label = "b")
        )
        assertEquals(0 to 2, stepFocusableBoardCell(grid, buttons, visible, 0 to 0, 0, 1))
    }

    @Test
    fun keyboardTemplateNavigatesAcrossKeys() {
        val boards = io.github.jdreioe.wingmate.application.KeyboardBoardTemplate.boards()
        val board = boards.first()
        val grid = board.grid!!
        val buttonsById = board.buttons.associateBy { it.id }
        val isVisible: (ObfButton) -> Boolean = { !it.hidden }

        val first = firstFocusableBoardCell(grid, buttonsById, isVisible)
        assertEquals(0 to 0, first)

        // Row 0 is the prediction row. Row 1 is letters a..j. Stepping down from
        // the first prediction cell should land on the first letter row cell.
        val down = stepFocusableBoardCell(grid, buttonsById, isVisible, first, 1, 0)
        assertEquals(1 to 0, down)

        // Stepping right across the letter row should advance through cells,
        // skipping the repeated prediction ids that span two columns.
        val rightOfDown = stepFocusableBoardCell(grid, buttonsById, isVisible, down, 0, 1)
        assertEquals(1 to 1, rightOfDown)
    }
}