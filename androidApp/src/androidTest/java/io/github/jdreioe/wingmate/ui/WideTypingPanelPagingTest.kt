package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.absoluteValue
import org.junit.Rule
import org.junit.Test

class WideTypingPanelPagingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var held by mutableStateOf<String?>(null)

    private fun showPanel() {
        composeRule.setContent {
            AppTheme {
                Box(Modifier.size(900.dp, 520.dp)) {
                    WideTypingLayout(
                        panelWidth = 304.dp,
                        message = {},
                        predictions = null,
                        typingScreen = {},
                        appActions = {},
                        speech = WideSpeechState.Idle,
                        onSpeak = {}, onPause = {}, onResume = {},
                        stopEnabled = false, onStop = {},
                        keyboardShown = false, onShowKeyboard = {}, onHideKeyboard = {},
                        heldMessage = held, onSwapHeld = {},
                        actions = { rowModifier ->
                            Column {
                                repeat(20) { row -> Text("Row $row", rowModifier(row).height(56.dp)) }
                            }
                        },
                        settings = Settings(),
                    )
                }
            }
        }
    }

    /** The top of the first row at least partly shown, in the panel's scroll area. */
    private fun firstVisibleRowTop() = (0 until 20)
        .map { composeRule.onNodeWithText("Row $it") }
        .filter { it.isDisplayed() }
        .minOf { it.getUnclippedBoundsInRoot().top }

    @Test
    fun aHeldMessageAppearingAboveTheRowsKeepsTheTopEdgeOnAWholeRow() {
        showPanel()
        composeRule.onNodeWithText("More actions").performClick()
        composeRule.waitForIdle()
        val edge = firstVisibleRowTop()

        composeRule.runOnIdle { held = "Could you open the window, please?" }
        composeRule.waitForIdle()

        // No clipped sliver of a row above the edge, where a near miss on Stop would land.
        assertEquals(edge.value, firstVisibleRowTop().value, 1f)
    }

    @Test
    fun aLongerHeldMessageReplacingAShortOneKeepsTheTopEdgeOnAWholeRow() {
        held = "Yes"
        showPanel()
        composeRule.onNodeWithText("More actions").performClick()
        composeRule.waitForIdle()
        val edge = firstVisibleRowTop()

        // Swapping in a Message that wraps to two lines grows the held card above the rows.
        composeRule.runOnIdle { held = "Could you open the window, please? And close the door behind you." }
        composeRule.waitForIdle()

        assertEquals(edge.value, firstVisibleRowTop().value, 1f)
    }

    @Test
    fun earlierUndoesMoreOnePressAtATimeAndEndsAtTheFirstRow() {
        showPanel()

        // Row 0 is laid out even when scrolled away (the grid is not lazy), so its top tracks the scroll.
        fun rowsTop() = composeRule.onNodeWithText("Row 0").getUnclippedBoundsInRoot().top

        // Presses until the button disables; every press must move at least one whole row (none is
        // spent on a few pixels of padding, which a switch user would read as a press that did nothing).
        fun pressUntilDisabled(label: String) {
            var presses = 0
            while (SemanticsProperties.Disabled !in composeRule.onNodeWithText(label).fetchSemanticsNode().config) {
                val before = rowsTop()
                composeRule.onNodeWithText(label).performClick()
                composeRule.waitForIdle()
                val moved = (rowsTop() - before).value.absoluteValue
                assertTrue("$label press ${presses + 1} moved only ${moved}dp", moved >= 56f)
                presses++
                check(presses < 50) { "$label never reached the end" }
            }
        }

        composeRule.onNodeWithText("Earlier actions").assertIsNotEnabled()
        pressUntilDisabled("More actions")
        composeRule.onNodeWithText("Row 19").assertIsDisplayed()
        composeRule.onNodeWithText("Earlier actions").assertIsEnabled()
        pressUntilDisabled("Earlier actions")
        composeRule.onNodeWithText("Row 0").assertIsDisplayed()
        composeRule.onNodeWithText("More actions").assertIsEnabled()
    }
}
