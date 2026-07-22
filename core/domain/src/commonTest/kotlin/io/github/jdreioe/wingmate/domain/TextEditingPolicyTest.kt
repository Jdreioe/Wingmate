package io.github.jdreioe.wingmate.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextEditingPolicyTest {
    @Test
    fun mergeNormalizesOverlappingAndAdjacentSpans() {
        assertEquals(
            listOf(TextSpan(1, 8)),
            TextEditingPolicy.merge(listOf(TextSpan(5, 8), TextSpan(1, 5), TextSpan(-2, 0)), 10),
        )
    }

    @Test
    fun toggleAddsAndRemovesOnlyTheSelection() {
        val initial = listOf(TextSpan(2, 8))
        assertTrue(TextEditingPolicy.isFullyCovered(TextSpan(3, 6), initial, 10))
        assertFalse(TextEditingPolicy.isFullyCovered(TextSpan(1, 6), initial, 10))
        assertEquals(
            listOf(TextSpan(2, 3), TextSpan(6, 8)),
            TextEditingPolicy.toggle(initial, TextSpan(3, 6), 10),
        )
        assertEquals(
            listOf(TextSpan(1, 8)),
            TextEditingPolicy.toggle(initial, TextSpan(1, 4), 10),
        )
    }

    @Test
    fun editBeforeSpanShiftsIt() {
        assertEquals(
            listOf(TextSpan(4, 7)),
            TextEditingPolicy.adjustForReplacement(
                textLength = 8,
                edit = TextSpan(0, 1),
                replacementLength = 3,
                spans = listOf(TextSpan(2, 5)),
            ),
        )
    }

    @Test
    fun editInsideSpanPreservesTheMarkedTextAroundIt() {
        assertEquals(
            listOf(TextSpan(1, 7)),
            TextEditingPolicy.adjustForReplacement(
                textLength = 8,
                edit = TextSpan(3, 5),
                replacementLength = 3,
                spans = listOf(TextSpan(1, 6)),
            ),
        )
    }

    @Test
    fun completeTextEditKeepsSpansAfterTheEditAligned() {
        assertEquals(
            listOf(TextSpan(11, 14)),
            TextEditingPolicy.adjustAfterEdit(
                oldText = "say hello",
                newText = "please say hello",
                spans = listOf(TextSpan(4, 7)),
            ),
        )
    }

    @Test
    fun completeWordReplacesAPartialWordAtTheCursor() {
        assertEquals(
            TextEditResult("say hello now", 10),
            TextEditingPolicy.completeWord("say he now", 6, "hello"),
        )
    }

    @Test
    fun completeWordInsertsSuggestionWhenThereIsNoMatchingPartial() {
        assertEquals(
            TextEditResult("hello world ", 12),
            TextEditingPolicy.completeWord("hello", 5, "world"),
        )
    }
}
