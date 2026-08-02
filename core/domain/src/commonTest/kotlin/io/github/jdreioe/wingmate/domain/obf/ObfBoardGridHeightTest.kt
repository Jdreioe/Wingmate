package io.github.jdreioe.wingmate.domain.obf

import kotlin.test.Test
import kotlin.test.assertEquals

class ObfBoardGridHeightTest {
    @Test
    fun gridHeightFractionIsStoredAndClamped() {
        val board = ObfBoard(format = "open-board-0.1", id = "keyboard")
            .withGridHeightFraction(1.4f)

        assertEquals(1f, board.gridHeightFraction)
    }
}
