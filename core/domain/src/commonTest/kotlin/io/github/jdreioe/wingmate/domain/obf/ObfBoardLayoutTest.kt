package io.github.jdreioe.wingmate.domain.obf

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObfBoardLayoutTest {

    @Test
    fun allButtonsHavePositionFields_returnsTrue() {
        val board = ObfBoard(
            format = "open-board-0.1",
            id = "b",
            buttons = listOf(
                ObfButton(id = "1", top = 0.0, left = 0.0, width = 0.5, height = 0.5),
                ObfButton(id = "2", top = 0.0, left = 0.5, width = 0.5, height = 0.5)
            )
        )
        assertTrue(board.isAbsoluteLayout)
    }

    @Test
    fun anyButtonMissingPosition_returnsFalse() {
        val board = ObfBoard(
            format = "open-board-0.1",
            id = "b",
            buttons = listOf(
                ObfButton(id = "1", top = 0.0, left = 0.0, width = 0.5, height = 0.5),
                ObfButton(id = "2", top = 0.0, left = 0.5, width = null, height = 0.5)
            )
        )
        assertFalse(board.isAbsoluteLayout)
    }

    @Test
    fun noButtons_returnsFalse() {
        val board = ObfBoard(
            format = "open-board-0.1",
            id = "b",
            buttons = emptyList()
        )
        assertFalse(board.isAbsoluteLayout)
    }

    @Test
    fun allPositionFieldsAreNull_returnsFalse() {
        val board = ObfBoard(
            format = "open-board-0.1",
            id = "b",
            buttons = listOf(
                ObfButton(id = "1"),
                ObfButton(id = "2")
            )
        )
        assertFalse(board.isAbsoluteLayout)
    }
}
