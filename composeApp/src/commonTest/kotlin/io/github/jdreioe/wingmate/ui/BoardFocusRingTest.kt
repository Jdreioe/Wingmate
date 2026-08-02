package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BoardFocusRingTest {

    private val grid = ObfGrid(
        rows = 2,
        columns = 2,
        order = listOf(listOf("a", null), listOf(null, "b"))
    )
    private val buttonsById = mapOf(
        "a" to ObfButton(id = "a", label = "A"),
        "b" to ObfButton(id = "b", label = "B")
    )

    @Composable
    private fun host(focus: Pair<Int, Int>?) {
        MaterialTheme {
            Box(Modifier.size(400.dp)) {
                SpanningBoardGrid(
                    rows = grid.rows,
                    columns = grid.columns,
                    items = buildBoardGridItems(grid, buttonsById),
                    modifier = Modifier.fillMaxSize(),
                    focusedCell = focus
                ) { Box(Modifier.fillMaxSize().background(Color.LightGray)) }
            }
        }
    }

    @Test
    fun focusRingAppearsOnTheFocusedButtonCell() = runComposeUiTest {
        setContent { host(1 to 1) }
        val ring = onNodeWithTag("board-focus-ring")
        ring.assertExists()
        val bounds = ring.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left > 200f, "ring x was ${bounds.left}")
        assertTrue(bounds.top > 200f, "ring y was ${bounds.top}")
    }

    @Test
    fun focusRingOnFirstCellStartsAtOrigin() = runComposeUiTest {
        setContent { host(0 to 0) }
        val ring = onNodeWithTag("board-focus-ring")
        ring.assertExists()
        val bounds = ring.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left < 10f, "ring x was ${bounds.left}")
        assertTrue(bounds.top < 10f, "ring y was ${bounds.top}")
    }

    @Test
    fun noFocusRingWhenCellHasNoButton() = runComposeUiTest {
        setContent { host(0 to 1) }
        onNodeWithTag("board-focus-ring").assertDoesNotExist()
    }

    @Test
    fun noFocusRingWhenFocusedCellIsNull() = runComposeUiTest {
        setContent { host(null) }
        onNodeWithTag("board-focus-ring").assertDoesNotExist()
    }
}