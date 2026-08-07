package io.github.jdreioe.wingmate.domain.obf

import kotlin.test.Test
import kotlin.test.assertEquals

class BoardSentenceTest {
    @Test
    fun emptySelectionHasNoSentence() {
        assertEquals("", buildResolvedSentence(emptyList(), emptyMap(), false, "en"))
    }

    @Test
    fun vocalizationTakesPriorityAndUsesBoardLocalization() {
        val selected = listOf(
            button(label = "Hello", vocalization = "Hello spoken"),
            button(label = "world")
        )
        val strings = mapOf(
            "da" to mapOf(
                "Hello spoken" to "Hej",
                "world" to "verden"
            )
        )

        assertEquals("Hej verden", buildResolvedSentence(selected, strings, false, "da-DK"))
    }

    @Test
    fun singleCharacterTokensAreJoinedForSpelling() {
        val selected = listOf(
            button(label = "H"),
            button(label = "i")
        )

        assertEquals("Hi", buildResolvedSentence(selected, emptyMap(), false, "en"))
    }

    @Test
    fun explicitSpaceTokensArePreserved() {
        val selected = listOf(
            button(label = "Hello"),
            button(label = " "),
            button(label = "there")
        )

        assertEquals("Hello there", buildResolvedSentence(selected, emptyMap(), false, "en"))
    }

    @Test
    fun explicitSpaceBetweenKeyboardLettersIsPreserved() {
        val selected = listOf(
            button(label = "d"),
            button(label = " "),
            button(label = "e")
        )

        assertEquals("d e", buildResolvedSentence(selected, emptyMap(), false, "en"))
    }

    @Test
    fun spellingBoardDoesNotAutoInsertSpaces() {
        val selected = listOf(
            button(label = "h"),
            button(label = "e"),
            button(label = "l"),
            button(label = "lo")
        )

        assertEquals("hello", buildResolvedSentence(selected, emptyMap(), true, "en"))
    }

    @Test
    fun spellingBoardPreservesExplicitSpaceTokens() {
        val selected = listOf(
            button(label = "hello"),
            button(label = " "),
            button(label = "world")
        )

        assertEquals("hello world", buildResolvedSentence(selected, emptyMap(), true, "en"))
    }

    private fun button(
        label: String,
        vocalization: String? = null
    ) = ObfButton(
        id = "button-$label",
        label = label,
        vocalization = vocalization
    )
}
