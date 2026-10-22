package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PhraseGridSizingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun fitsWholeRowsAfterResizingAndKeepsRemainingPhrasesReachable() {
        val buttons = List(12) { ObfButton(id = "$it", label = "Phrase ${it + 1}") }
        val board = ObfBoard(
            format = "open-board-0.1",
            id = "phrase-sizing-test",
            buttons = buttons,
            grid = ObfGrid(6, 2, buttons.chunked(2).map { row -> row.map { it.id } }),
        )
        var viewportHeight by mutableStateOf(246.dp)
        var minimumHeight by mutableStateOf(72.dp)
        composeRule.setContent {
            AppTheme {
                Box(Modifier.width(320.dp).height(viewportHeight).testTag("viewport")) {
                    ObfBoardView(
                        board = board,
                        onButtonClick = {},
                        showMessageBar = false,
                        scrollingMinimumCellHeight = minimumHeight,
                    )
                }
            }
        }

        fun assertRowsFit(visibleRows: Int) {
            val viewport = composeRule.onNodeWithTag("viewport").getUnclippedBoundsInRoot()
            val first = composeRule.onNodeWithText("Phrase 1").getUnclippedBoundsInRoot()
            val last = composeRule.onNodeWithText("Phrase ${visibleRows * 2}")
            val lastBounds = last.getUnclippedBoundsInRoot()
            assertTrue("Respect the minimum button size", first.bottom - first.top >= minimumHeight - 1.dp)
            assertEquals(
                "Fill the viewport with whole rows",
                viewport.bottom.value - 8f,
                lastBounds.bottom.value,
                1f,
            )
            last.assertIsDisplayed()
        }

        assertRowsFit(visibleRows = 2)
        composeRule.runOnIdle { viewportHeight = 326.dp }
        assertRowsFit(visibleRows = 3)
        composeRule.runOnIdle { minimumHeight = 144.dp }
        assertRowsFit(visibleRows = 2)

        composeRule.onNodeWithText("Phrase 12").performScrollTo().assertIsDisplayed()
    }
}
