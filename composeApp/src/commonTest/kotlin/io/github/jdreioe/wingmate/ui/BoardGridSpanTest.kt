package io.github.jdreioe.wingmate.ui

import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

    @Test
    fun fieldCanGrowHorizontallyVerticallyAndDiagonally() {
        val grid = emptyGrid(rows = 3, columns = 3).withFieldSpan(0, 0, "field", rowSpan = 1, columnSpan = 1)!!

        val horizontal = assertNotNull(grid.withFieldSpan(0, 0, "field", rowSpan = 1, columnSpan = 3))
        val vertical = assertNotNull(grid.withFieldSpan(0, 0, "field", rowSpan = 3, columnSpan = 1))
        val diagonal = assertNotNull(grid.withFieldSpan(0, 0, "field", rowSpan = 2, columnSpan = 2))

        assertEquals(listOf("field", "field", "field"), horizontal.order[0])
        assertEquals(listOf("field", null, null), vertical.order[0])
        assertEquals(listOf("field", "field", null), diagonal.order[0])
        assertEquals(listOf("field", "field", null), diagonal.order[1])
    }

    @Test
    fun fieldFontScaleGrowsWithSpanAreaAndRemainsBounded() {
        assertEquals(1f, fieldFontScale(rowSpan = 1, columnSpan = 1))
        assertTrue(fieldFontScale(rowSpan = 1, columnSpan = 2) > 1f)
        assertTrue(fieldFontScale(rowSpan = 2, columnSpan = 2) > fieldFontScale(1, 2))
        assertEquals(2f, fieldFontScale(rowSpan = 20, columnSpan = 20))
    }

    @Test
    fun fieldCanShrinkToSingleCellKeepingItsAnchor() {
        val expanded = emptyGrid(rows = 3, columns = 3)
            .withFieldSpan(1, 1, "field", rowSpan = 2, columnSpan = 2)!!

        val shrunk = assertNotNull(expanded.withFieldSpan(1, 1, "field", rowSpan = 1, columnSpan = 1))

        assertEquals("field", shrunk.order[1][1])
        assertEquals(null, shrunk.order[2][1])
        assertEquals(null, shrunk.order[1][2])
        assertEquals(GridFieldSpan(rows = 1, columns = 1), shrunk.fieldSpanAt(1, 1))
    }

    @Test
    fun shrinkingFreesCellsForNeighboringFields() {
        val grid = emptyGrid(rows = 2, columns = 3)
            .withFieldSpan(0, 0, "wide", rowSpan = 1, columnSpan = 2)!!

        val shrunk = assertNotNull(grid.withFieldSpan(0, 0, "wide", rowSpan = 1, columnSpan = 1))
        val placed = assertNotNull(shrunk.withFieldSpan(0, 1, "new", rowSpan = 1, columnSpan = 2))

        assertEquals(listOf("wide", "new", "new"), placed.order[0])
    }

    @Test
    fun resizingBeyondBoardBoundsIsRejected() {
        val grid = emptyGrid(rows = 2, columns = 2).withFieldSpan(1, 1, "field", rowSpan = 1, columnSpan = 1)!!

        assertNull(grid.withFieldSpan(1, 1, "field", rowSpan = 2, columnSpan = 1))
        assertNull(grid.withFieldSpan(1, 1, "field", rowSpan = 1, columnSpan = 2))
        assertNull(grid.withFieldSpan(0, 0, "field", rowSpan = 3, columnSpan = 1))
    }

    @Test
    fun resizingCannotCrossIntoAnotherField() {
        val grid = emptyGrid(rows = 3, columns = 3).copy(
            order = listOf(
                listOf("field", "field", "occupied"),
                listOf("field", "field", null),
                listOf(null, null, null)
            )
        )

        assertNull(grid.withFieldSpan(0, 0, "field", rowSpan = 2, columnSpan = 3))
        assertNull(grid.withFieldSpan(0, 0, "field", rowSpan = 3, columnSpan = 3))
        assertNotNull(grid.withFieldSpan(0, 0, "field", rowSpan = 2, columnSpan = 2))
    }

    @Test
    fun availableSpansIncludeCurrentSizeAndSingleCell() {
        val grid = emptyGrid(rows = 3, columns = 3).copy(
            order = listOf(
                listOf("field", "field", "occupied"),
                listOf("field", "field", null),
                listOf(null, null, null)
            )
        )

        val available = grid.availableFieldSpansAt(0, 0)

        assertTrue(GridFieldSpan(rows = 2, columns = 2) in available)
        assertTrue(GridFieldSpan(rows = 1, columns = 1) in available)
        assertFalse(GridFieldSpan(rows = 3, columns = 3) in available)
        assertFalse(GridFieldSpan(rows = 2, columns = 3) in available)
    }

    @Test
    fun fieldAnchorIsTheTopLeftOfTheOccupiedCells() {
        val grid = emptyGrid(rows = 3, columns = 4)
            .withFieldSpan(1, 2, "field", rowSpan = 2, columnSpan = 2)!!

        assertEquals(1 to 2, grid.fieldAnchorAt(1, 2))
        assertEquals(1 to 2, grid.fieldAnchorAt(2, 3))
        assertEquals(null, grid.fieldAnchorAt(0, 0))
        assertEquals(null, grid.fieldAnchorAt(1, 1))
    }

    @Test
    fun importedRepeatedIdFieldsReportAnchorAndSpan() {
        val grid = emptyGrid(rows = 2, columns = 3).copy(
            order = listOf(
                listOf("same", "same", "same"),
                listOf(null, "same", null)
            )
        )

        assertEquals(0 to 0, grid.fieldAnchorAt(0, 0))
        assertEquals(GridFieldSpan(rows = 2, columns = 3), grid.fieldSpanAt(0, 0))
    }

    @Test
    fun fieldAnchorIgnoresEmptyCells() {
        val grid = emptyGrid(rows = 2, columns = 2)

        assertEquals(null, grid.fieldAnchorAt(0, 0))
        assertEquals(null, grid.fieldAnchorAt(1, 1))
    }

    private fun emptyGrid(rows: Int, columns: Int) = ObfGrid(
        rows = rows,
        columns = columns,
        order = List(rows) { List(columns) { null } }
    )
}
