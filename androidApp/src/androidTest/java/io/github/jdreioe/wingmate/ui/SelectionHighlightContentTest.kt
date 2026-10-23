package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SelectionHighlightContentTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun disablingFeedbackClearsTheCurrentSelection() {
        var duration by mutableLongStateOf(60_000)
        composeRule.setContent {
            AppTheme {
                val selection = rememberSelectionHighlightState(duration)
                Button(onClick = { selection.select("a") }) { Text("Select") }
                if (selection.buttonId != null) Box(Modifier.size(10.dp).testTag("highlight"))
            }
        }
        composeRule.onNodeWithText("Select").performClick()
        composeRule.onAllNodesWithTag("highlight").assertCountEquals(1)
        composeRule.runOnIdle { duration = 0 }
        composeRule.onAllNodesWithTag("highlight").assertCountEquals(0)
    }

    @Test
    fun selectionKeepsFocusAndHasBothBlackAndWhiteOutlines() {
        var density = 1f
        val board = ObfBoard(
            id = "page", format = "open-board-0.1",
            buttons = listOf(ObfButton(id = "a", label = "A", backgroundColor = "#000000")),
            grid = ObfGrid(rows = 1, columns = 1, order = listOf(listOf("a"))),
        )
        composeRule.setContent {
            AppTheme {
                density = LocalDensity.current.density
                ObfBoardContent(
                    state = ObfBoardUiState(
                        board = board, showMessageBar = false, focusedCell = 0 to 0,
                        highlightedButtonId = "a", settings = Settings(highContrastMode = true),
                    ),
                    onButtonClick = {}, modifier = Modifier.size(120.dp),
                )
            }
        }
        composeRule.onAllNodesWithTag("board-focus-ring", useUnmergedTree = true).assertCountEquals(1)
        val pixels = composeRule.onNodeWithTag("selection-highlight", useUnmergedTree = true).captureToImage().toPixelMap()
        assertEquals(Color.Black, pixels[(9 * density).toInt(), pixels.height / 2])
        assertEquals(Color.White, pixels[(11 * density).toInt(), pixels.height / 2])
    }
}
