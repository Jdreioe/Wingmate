package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PageSwipeBoxTest {
    @get:Rule
    val composeRule = createComposeRule()
    private val swipes = mutableListOf<Int>()
    private var taps = 0

    private fun render(enabled: Boolean = true) {
        composeRule.setContent {
            PageSwipeBox(enabled = enabled, onSwipe = { swipes += it }, modifier = Modifier.size(400.dp).testTag("page")) {
                // Like a Page's grid: scrollable content whose Buttons take taps.
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Box(Modifier.fillMaxWidth().height(800.dp).clickable { taps++ }) { Text("Hello") }
                }
            }
        }
    }

    @Test
    fun aDeliberateSwipeMovesOnePageEachWayWithoutPressingTheButton() {
        render()
        composeRule.onNodeWithTag("page").performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag("page").performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        assertEquals(listOf(1, -1), swipes)
        assertEquals(0, taps)
    }

    @Test
    fun shortAndVerticalDragsDoNotNavigateAndTapsStillWork() {
        render()
        composeRule.onNodeWithTag("page").performTouchInput {
            down(center)
            moveBy(Offset(-width * 0.15f, 0f))
            up()
        }
        composeRule.waitForIdle()
        assertEquals(0, taps)
        composeRule.onNodeWithTag("page").performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        assertEquals(0, taps)
        composeRule.onNodeWithTag("page").performClick()
        composeRule.waitForIdle()

        assertEquals(emptyList<Int>(), swipes)
        assertEquals(1, taps)
    }

    @Test
    fun swipingCanBeTurnedOff() {
        render(enabled = false)
        composeRule.onNodeWithTag("page").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals(emptyList<Int>(), swipes)
    }
}
