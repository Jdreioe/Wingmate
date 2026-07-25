package io.github.jdreioe.wingmate.ui

import androidx.compose.ui.graphics.ImageBitmap
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import kotlin.test.Test
import kotlin.test.assertEquals

class BoardSentenceTest {
    @Test
    fun emptySelectionHasNoSentence() {
        assertEquals("", buildResolvedSentence(emptyList(), board(), "en"))
    }

    @Test
    fun vocalizationTakesPriorityAndUsesBoardLocalization() {
        val selected = listOf(
            selectedButton(label = "Hello", vocalization = "Hello spoken"),
            selectedButton(label = "world")
        )
        val board = board(
            strings = mapOf(
                "da" to mapOf(
                    "Hello spoken" to "Hej",
                    "world" to "verden"
                )
            )
        )

        assertEquals("Hej verden", buildResolvedSentence(selected, board, "da-DK"))
    }

    @Test
    fun singleCharacterTokensAreJoinedForSpelling() {
        val selected = listOf(
            selectedButton(label = "H"),
            selectedButton(label = "i")
        )

        assertEquals("Hi", buildResolvedSentence(selected, board(), "en"))
    }

    @Test
    fun blankTokensAreIgnored() {
        val selected = listOf(
            selectedButton(label = "Hello"),
            selectedButton(label = " "),
            selectedButton(label = "there")
        )

        assertEquals("Hello there", buildResolvedSentence(selected, board(), "en"))
    }

    private fun board(
        strings: Map<String, Map<String, String>> = emptyMap()
    ) = ObfBoard(
        format = "open-board-0.1",
        id = "board",
        strings = strings
    )

    private fun selectedButton(
        label: String,
        vocalization: String? = null
    ): Pair<ObfButton, ImageBitmap?> = ObfButton(
        id = "button-$label",
        label = label,
        vocalization = vocalization
    ) to null
}
