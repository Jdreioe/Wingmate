package io.github.jdreioe.wingmate.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TypingRowHeightsTest {
    // Default template: navigation, six Phrase rows, two Action strips.
    private val defaultMinimums = listOf(48) + List(6) { 0 } + listOf(64, 64)

    @Test
    fun actionStripsKeepTheirHeightAndPhrasesShareTheRest() {
        val heights = typingRowHeights(defaultMinimums, height = 356)

        assertEquals(listOf(48, 64, 64), listOf(heights[0], heights[7], heights[8]))
        assertEquals(180, heights.subList(1, 7).sum())
        assertEquals(356, heights.sum())
    }

    @Test
    fun tooShortTrayNeverShrinksStripsBelowTheirMinimum() {
        val heights = typingRowHeights(defaultMinimums, height = 120)

        assertEquals(listOf(48, 64, 64), listOf(heights[0], heights[7], heights[8]))
        assertEquals(0, heights.subList(1, 7).sum())
    }
}
