package io.github.jdreioe.wingmate.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SelectionHighlightTest {

    @Test
    fun newlyActivatedTarget_isVisibleWithinDuration() {
        val highlight = SelectionHighlight()
        highlight.activate("target-a", nowMillis = 1000L)
        assertEquals("target-a", highlight.highlightedTarget(nowMillis = 1000L, durationMillis = 500L))
        assertEquals("target-a", highlight.highlightedTarget(nowMillis = 1499L, durationMillis = 500L))
    }

    @Test
    fun highlightExpiresExactlyAtBoundary() {
        val highlight = SelectionHighlight()
        highlight.activate("target-a", nowMillis = 1000L)
        assertNull(highlight.highlightedTarget(nowMillis = 1500L, durationMillis = 500L))
    }

    @Test
    fun zeroDuration_disablesHighlightEntirely() {
        val highlight = SelectionHighlight()
        highlight.activate("target-a", nowMillis = 1000L)
        assertNull(highlight.highlightedTarget(nowMillis = 1000L, durationMillis = 0L))
    }

    @Test
    fun rapidReactivation_movesWindowForwardWithoutStaleClear() {
        val highlight = SelectionHighlight()
        highlight.activate("target-a", nowMillis = 1000L)
        // A rapid re-activation of the same target resets the window from the newest hit.
        highlight.activate("target-a", nowMillis = 1400L)
        assertEquals("target-a", highlight.highlightedTarget(nowMillis = 1600L, durationMillis = 500L))
        assertNull(highlight.highlightedTarget(nowMillis = 1901L, durationMillis = 500L))
    }

    @Test
    fun switchingTargets_highlightsOnlyTheLatest() {
        val highlight = SelectionHighlight()
        highlight.activate("target-a", nowMillis = 1000L)
        highlight.activate("target-b", nowMillis = 1100L)
        assertEquals("target-b", highlight.highlightedTarget(nowMillis = 1200L, durationMillis = 500L))
    }

    @Test
    fun clear_immediatelyEndsTheHighlight() {
        val highlight = SelectionHighlight()
        highlight.activate("target-a", nowMillis = 1000L)
        highlight.clear()
        assertNull(highlight.highlightedTarget(nowMillis = 1000L, durationMillis = 500L))
    }

    @Test
    fun generation_bumpsOnEveryActivation() {
        val highlight = SelectionHighlight()
        assertEquals(0L, highlight.generation)
        highlight.activate("target-a", nowMillis = 1000L)
        assertEquals(1L, highlight.generation)
        highlight.activate("target-a", nowMillis = 1100L)
        assertEquals(2L, highlight.generation)
        highlight.clear()
        assertEquals(3L, highlight.generation)
    }
}
