package io.github.jdreioe.wingmate.ui

import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class BoardGridSpanTest {
    @Test
    fun fieldCanSpanSeveralRowsAndColumns() {
        val grid = emptyGrid(rows = 3, columns = 4)

        val expanded = grid.withFieldSpan(
            row = 0,
            column = 1,
            buttonId = "people",
            rowSpan = 2,
            columnSpan = 3
        )!!

        assertEquals(
            listOf(
                listOf(null, "people", "people", "people"),
                listOf(null, "people", "people", "people"),
                listOf(null, null, null, null)
            ),
            expanded.order
        )
        assertEquals(GridFieldSpan(rows = 2, columns = 3), expanded.fieldSpanAt(0, 1))
    }

    @Test
    fun resizingMovesTheWholeExistingFieldFromItsAnchor() {
        val expanded = emptyGrid(rows = 3, columns = 4)
            .withFieldSpan(0, 0, "field", rowSpan = 2, columnSpan = 2)!!

        val resized = expanded.withFieldSpan(0, 0, "field", rowSpan = 1, columnSpan = 3)!!

        assertEquals(listOf("field", "field", "field", null), resized.order[0])
        assertEquals(listOf(null, null, null, null), resized.order[1])
    }

    @Test
    fun sizesThatWouldCoverAnotherFieldAreNotOfferedOrApplied() {
        val grid = emptyGrid(rows = 2, columns = 3).copy(
            order = listOf(
                listOf(null, null, "occupied"),
                listOf(null, null, null)
            )
        )

        val available = grid.availableFieldSpansAt(0, 0)

        assertFalse(GridFieldSpan(rows = 1, columns = 3) in available)
        assertNull(grid.withFieldSpan(0, 0, "new", rowSpan = 1, columnSpan = 3))
    }

    private fun emptyGrid(rows: Int, columns: Int) = ObfGrid(
        rows = rows,
        columns = columns,
        order = List(rows) { List(columns) { null } }
    )
}
