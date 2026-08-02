package io.github.jdreioe.wingmate.ui

import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfButtonType
import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import kotlin.test.Test
import kotlin.test.assertEquals

class OrderedPredictionButtonIdsTest {

    private fun board(buttons: List<ObfButton>, grid: ObfGrid?): ObfBoard =
        ObfBoard(format = "open-board-0.1", id = "b", buttons = buttons, grid = grid)

    @Test
    fun ordersPredictorsByGridPosition() {
        val a = ObfButton(id = "a", action = ":prediction")
        val b = ObfButton(id = "b", action = ":predictions")
        val plain = ObfButton(id = "plain", action = "+x")
        val board = board(
            listOf(plain, a, b),
            grid = ObfGrid(
                rows = 2,
                columns = 3,
                order = listOf(
                    listOf("plain", "b", null),
                    listOf("a", null, null)
                )
            )
        )
        assertEquals(listOf("b", "a"), orderedPredictionButtonIds(board, showHiddenButtons = false))
    }

    @Test
    fun fallbackToButtonsListOrderWithoutGrid() {
        val board = board(
            listOf(
                ObfButton(id = "x", action = ":prediction"),
                ObfButton(id = "y", action = ":prediction")
            ),
            grid = null
        )
        assertEquals(listOf("x", "y"), orderedPredictionButtonIds(board, showHiddenButtons = false))
    }

    @Test
    fun includesLegacyNGramTypeButExcludesHidden() {
        val legacy = ObfButton(id = "legacy").withType(ObfButtonType.NGramPrediction)
        val hidden = ObfButton(id = "hidden", action = ":prediction", hidden = true)
        val board = board(listOf(hidden, legacy), grid = null)
        assertEquals(listOf("legacy"), orderedPredictionButtonIds(board, showHiddenButtons = false))
        assertEquals(listOf("hidden", "legacy"), orderedPredictionButtonIds(board, showHiddenButtons = true))
    }
}