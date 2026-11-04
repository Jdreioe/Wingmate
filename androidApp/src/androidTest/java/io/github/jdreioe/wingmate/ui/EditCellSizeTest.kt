package io.github.jdreioe.wingmate.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.domain.obf.GridFieldSpan
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class EditCellSizeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun showEditor(current: GridFieldSpan, allowed: List<GridFieldSpan>, resized: MutableList<GridFieldSpan>) {
        composeRule.setContent {
            AppTheme {
                EditBoardCellDialog(
                    boardName = "Hilsner",
                    row = 0,
                    column = 0,
                    initialLabel = "Hej",
                    initialVocalization = "",
                    initialImageUrl = "",
                    hasExistingValue = true,
                    size = EditCellSize(current, allowed, onResize = { resized += it }),
                    onDismiss = {},
                    onSave = { _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
                    onClearCell = {},
                )
            }
        }
    }

    @Test
    fun stepsThatFitResizeAtOnceAndOthersAreDisabled() {
        val resized = mutableListOf<GridFieldSpan>()
        // A 1x1 Button with one free cell to its right only.
        showEditor(GridFieldSpan(1, 1), listOf(GridFieldSpan(1, 1), GridFieldSpan(1, 2)), resized)

        composeRule.onNodeWithContentDescription(context.getString(R.string.board_resize_increase_height))
            .performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(context.getString(R.string.board_resize_decrease_width)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(context.getString(R.string.board_resize_increase_width))
            .assertIsEnabled().performClick()

        composeRule.runOnIdle { assertEquals(listOf(GridFieldSpan(rows = 1, columns = 2)), resized) }
    }

    @Test
    fun aButtonWithNoRoomSaysHowToMakeRoom() {
        showEditor(GridFieldSpan(1, 1), listOf(GridFieldSpan(1, 1)), mutableListOf())

        composeRule.onNodeWithText(context.getString(R.string.board_dialog_size_no_room)).performScrollTo()
    }
}
