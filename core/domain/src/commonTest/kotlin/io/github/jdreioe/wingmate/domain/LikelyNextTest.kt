package io.github.jdreioe.wingmate.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class LikelyNextTest {
    private val minute = 60_000L

    @Test
    fun aTransitionOutranksPlainFrequency() {
        val history = listOf(
            said("Hello", 0), said("Yes", 1), said("Yes", 2), said("Yes", 3),
            said("Hello", 100), said("How are you?", 101),
            said("Hello", 200),
        )

        assertEquals(listOf("How are you?", "Yes"), suggestions(history))
    }

    @Test
    fun aLongGapSplitsTheConversation() {
        val history = listOf(
            said("Hello", 0), said("Coffee please", 11),
            said("Thanks", 30),
            said("Hello", 100),
        )

        // Coffee came 11 minutes after Hello, so it is only a frequency fallback, ranked by recency.
        assertEquals(listOf("Thanks", "Coffee please"), suggestions(history))
    }

    @Test
    fun theJustSpokenMessageAndHiddenEntriesAreNeverSuggested() {
        val history = listOf(
            said("Water", 0), said("water ", 1), said("Private", 2, visible = false),
            said("Water", 3),
        )

        assertEquals(emptyList(), suggestions(history))
    }

    @Test
    fun suggestionsUseTheLatestEntryAndRespectTheLimit() {
        val history = listOf(
            said("Hi", 0), said("Tea", 1), said("hi", 50), said("tea ", 51), said("Juice", 52), said("Hi", 53),
        )

        val result = likelyNextMessages(history, limit = 1)
        assertEquals(listOf("tea "), result.map { it.saidText })
        assertEquals(51 * minute, result.single().date)
    }

    @Test
    fun emptyHistoryGivesNoSuggestions() {
        assertEquals(emptyList(), suggestions(emptyList()))
    }

    private fun suggestions(history: List<SaidText>) = likelyNextMessages(history, limit = 8).map { it.saidText }

    private fun said(text: String, minutes: Long, visible: Boolean = true) =
        SaidText(saidText = text, date = minutes * minute, visibleInHistory = visible)
}
