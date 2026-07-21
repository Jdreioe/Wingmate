package io.github.jdreioe.wingmate.ui

import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull

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

    @Test
    fun draggingOntoAnotherFieldSwapsTheirLocations() {
        val grid = emptyGrid(rows = 2, columns = 3).copy(
            order = listOf(
                listOf("first", null, "second"),
                listOf(null, null, null)
            )
        )

        val swapped = assertNotNull(grid.moveOrSwapField(0, 0, 0, 2))

        assertEquals("second", swapped.order[0][0])
        assertEquals("first", swapped.order[0][2])
    }

    @Test
    fun draggingOntoAnEmptyCellMovesTheWholeSpanningField() {
        val grid = emptyGrid(rows = 3, columns = 4)
            .withFieldSpan(0, 0, "wide", rowSpan = 1, columnSpan = 2)!!

        val moved = assertNotNull(grid.moveOrSwapField(0, 1, 2, 1))

        assertEquals(listOf(null, null, null, null), moved.order[0])
        assertEquals(listOf(null, "wide", "wide", null), moved.order[2])
    }

    @Test
    fun swappingDifferentSpansRejectsOverlapsAndNeighborCollisions() {
        val grid = emptyGrid(rows = 2, columns = 4).copy(
            order = listOf(
                listOf("wide", "wide", "small", "neighbor"),
                listOf(null, null, null, null)
            )
        )

        assertNull(grid.moveOrSwapField(0, 0, 0, 2))
        assertEquals(grid, grid.moveOrSwapField(0, 0, 0, 1))
    }

    @Test
    fun pageCanGrowAndCanOnlyShrinkAcrossEmptyEdges() {
        val grid = emptyGrid(rows = 2, columns = 2).copy(
            order = listOf(
                listOf("one", null),
                listOf(null, null)
            )
        )

        val grown = assertNotNull(grid.resized(newRows = 3, newColumns = 4))
        assertEquals(3, grown.rows)
        assertEquals(4, grown.columns)
        assertEquals("one", grown.order[0][0])
        assertNotNull(grid.resized(newRows = 1, newColumns = 1))

        val occupiedEdge = grid.copy(
            order = listOf(
                listOf("one", null),
                listOf(null, "edge")
            )
        )
        assertNull(occupiedEdge.resized(newRows = 1, newColumns = 2))
        assertNull(occupiedEdge.resized(newRows = 2, newColumns = 1))
    }

    private fun emptyGrid(rows: Int, columns: Int) = ObfGrid(
        rows = rows,
        columns = columns,
        order = List(rows) { List(columns) { null } }
    )
}
