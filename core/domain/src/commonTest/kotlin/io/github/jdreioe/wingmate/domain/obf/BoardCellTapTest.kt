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
    fun firstTapOnAnOccupiedFieldSelectsIt() {
        val result = resolveCellTap(grid, selectedField = null, row = 1, column = 1, button = button)

        assertIs<CellTapResult.Select>(result)
        assertEquals(0 to 0, result.anchor)
    }

    @Test
    fun secondTapOnTheSelectedFieldOpensItsPropertiesDialog() {
        val result = resolveCellTap(grid, selectedField = 0 to 0, row = 1, column = 1, button = button)

        assertIs<CellTapResult.OpenDialog>(result)
        assertEquals(1, result.row)
        assertEquals(1, result.column)
        assertEquals(button, result.button)
    }

    @Test
    fun tappingAnotherFieldSelectsItInsteadOfOpeningTheDialog() {
        val result = resolveCellTap(grid, selectedField = 0 to 0, row = 2, column = 2, button = null)

        assertIs<CellTapResult.OpenDialog>(result)
    }

    @Test
    fun tappingAnEmptyCellAlwaysOpensTheNewFieldDialog() {
        val result = resolveCellTap(grid, selectedField = null, row = 2, column = 2, button = null)

        assertIs<CellTapResult.OpenDialog>(result)
    }

    @Test
    fun tappingAnEmptyCellClearsSelectionBeforeOpeningTheDialog() {
        val result = resolveCellTap(grid, selectedField = 0 to 0, row = 2, column = 2, button = null)

        assertIs<CellTapResult.OpenDialog>(result)
    }

    @Test
    fun nullGridFallsBackToOpeningTheDialogForOccupiedCells() {
        val result = resolveCellTap(grid = null, selectedField = null, row = 0, column = 0, button = button)

        assertIs<CellTapResult.OpenDialog>(result)
    }
}
