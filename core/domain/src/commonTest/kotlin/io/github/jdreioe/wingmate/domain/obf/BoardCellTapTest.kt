package io.github.jdreioe.wingmate.domain.obf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BoardCellTapTest {
    private val grid = ObfGrid(
        rows = 3,
        columns = 3,
        order = listOf(
            listOf("field", "field", null),
            listOf("field", "field", null),
            listOf(null, null, null)
        )
    )
    private val button = ObfButton(id = "field", label = "Field")

    @Test
    fun firstTapOnAnOccupiedFieldOpensItsEditorAtTheAnchor() {
        val result = resolveCellTap(grid, row = 1, column = 1, button = button)

        assertIs<CellTapResult.OpenDialog>(result)
        assertEquals(0, result.row)
        assertEquals(0, result.column)
    }

    @Test
    fun tappingTheAnchorOpensItsPropertiesDialog() {
        val result = resolveCellTap(grid, row = 0, column = 0, button = button)

        assertIs<CellTapResult.OpenDialog>(result)
        assertEquals(0, result.row)
        assertEquals(0, result.column)
        assertEquals(button, result.button)
    }

    @Test
    fun tappingAnEmptyCellAlwaysOpensTheNewFieldDialog() {
        val result = resolveCellTap(grid, row = 2, column = 2, button = null)

        assertIs<CellTapResult.OpenDialog>(result)
        assertEquals(2, result.row)
        assertEquals(2, result.column)
    }

    @Test
    fun nullGridFallsBackToOpeningTheDialogForOccupiedCells() {
        val result = resolveCellTap(grid = null, row = 0, column = 0, button = button)

        assertIs<CellTapResult.OpenDialog>(result)
    }
}
