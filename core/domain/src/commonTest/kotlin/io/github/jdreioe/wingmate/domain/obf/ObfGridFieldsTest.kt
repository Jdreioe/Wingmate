package io.github.jdreioe.wingmate.domain.obf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ObfGridFieldsTest {

    private fun grid(order: List<List<String?>>, rows: Int = order.size, columns: Int = order.first().size): ObfGrid =
        ObfGrid(rows = rows, columns = columns, order = order)

    @Test
    fun fieldItems_detectsSpanningRectangles() {
        val g = grid(
            listOf(
                listOf("a", "a", null),
                listOf("a", "a", "b"),
                listOf(null, null, "b")
            )
        )
        val items = g.fieldItems()
        val a = items.first { it.buttonId == "a" }
        assertEquals(0, a.row)
        assertEquals(0, a.column)
        assertEquals(2, a.rowSpan)
        assertEquals(2, a.columnSpan)
        val b = items.first { it.buttonId == "b" }
        assertEquals(1, b.row)
        assertEquals(2, b.column)
        assertEquals(2, b.rowSpan)
        assertEquals(1, b.columnSpan)
    }

    @Test
    fun fieldItems_includesEmptyCells() {
        val g = grid(
            listOf(
                listOf("a", null),
                listOf(null, null)
            )
        )
        val items = g.fieldItems()
        assertEquals(4, items.size)
        assertEquals(3, items.count { it.buttonId == null })
    }

    @Test
    fun fieldSpanAt_matchesRepeatedButton() {
        val g = grid(
            listOf(
                listOf("a", "a"),
                listOf("a", "a")
            )
        )
        assertEquals(GridFieldSpan(2, 2), g.fieldSpanAt(0, 0))
        assertEquals(GridFieldSpan(2, 2), g.fieldSpanAt(1, 1))
    }

    @Test
    fun fieldAnchorAt_isTopLeft() {
        val g = grid(
            listOf(
                listOf(null, "b"),
                listOf("a", "b")
            )
        )
        assertEquals(0 to 1, g.fieldAnchorAt(0, 1))
        assertEquals(0 to 1, g.fieldAnchorAt(1, 1))
    }

    @Test
    fun availableFieldSpansAt_excludesOccupiedCells() {
        val g = grid(
            listOf(
                listOf("a", null),
                listOf(null, "b")
            )
        )
        val spans = g.availableFieldSpansAt(0, 0)
        // 1x1 and 1x2 and 2x1 (2x2 touches b) → blocked
        assertEquals(
            listOf(GridFieldSpan(1, 1), GridFieldSpan(1, 2), GridFieldSpan(2, 1)),
            spans
        )
    }

    @Test
    fun withFieldSpan_growsIntoFreeCells() {
        val g = grid(
            listOf(
                listOf("a", null),
                listOf(null, "b")
            )
        )
        val grown = g.withFieldSpan(0, 0, "a", 2, 2)
        assertNull(grown) // blocked by b
        val vertical = g.withFieldSpan(0, 0, "a", 2, 1)
        assertNotNull(vertical)
        assertEquals(
            listOf(listOf("a", null), listOf("a", "b")),
            vertical!!.order
        )
    }

    @Test
    fun withFieldSpan_shrinksAndClearsOldCells() {
        val g = grid(
            listOf(
                listOf("a", "a"),
                listOf("a", "a")
            )
        )
        val shrunk = g.withFieldSpan(0, 0, "a", 1, 1)
        assertNotNull(shrunk)
        assertEquals(
            listOf(listOf("a", null), listOf(null, null)),
            shrunk!!.order
        )
    }

    @Test
    fun resized_rejectsRemovingOccupiedCells() {
        val g = grid(
            listOf(
                listOf("a", null),
                listOf(null, "b")
            )
        )
        assertNull(g.resized(1, 2))
        assertNull(g.resized(2, 1))
        val grown = g.resized(3, 3)
        assertNotNull(grown)
        assertEquals(3, grown!!.rows)
        assertEquals(3, grown.columns)
        assertEquals("a", grown.order[0][0])
        assertEquals("b", grown.order[1][1])
    }

    @Test
    fun moveOrSwapField_swapsSpans() {
        val g = grid(
            listOf(
                listOf("a", "a", null),
                listOf("a", "a", "b")
            )
        )
        // Swap a (2x2) with b (1x1): b must fit where a was
        val swapped = g.moveOrSwapField(0, 0, 1, 2)
        assertNull(swapped) // b (1x1) placed at a's anchor, a needs 2x2 at b's spot — 2x2 at (1,2) out of bounds
        val swappedSmall = grid(
            listOf(
                listOf("a", "a"),
                listOf("a", "a")
            )
        ).let { it.moveOrSwapField(0, 0, 0, 0) }
        assertEquals(
            listOf(listOf("a", "a"), listOf("a", "a")),
            swappedSmall!!.order
        )
    }

    @Test
    fun normalizedOrder_padsRaggedInput() {
        val g = ObfGrid(rows = 2, columns = 3, order = listOf(listOf("a")))
        assertEquals(
            listOf(listOf("a", null, null), listOf(null, null, null)),
            g.normalizedOrder()
        )
    }
}
