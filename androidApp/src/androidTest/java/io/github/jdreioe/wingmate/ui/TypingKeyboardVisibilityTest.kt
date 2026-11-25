package io.github.jdreioe.wingmate.ui

import android.graphics.Insets
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.filters.SdkSuppress
import io.github.jdreioe.wingmate.configureEdgeToEdgeWindow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@SdkSuppress(minSdkVersion = 30)
class TypingKeyboardVisibilityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var previousKeyboardHeight = Float.NaN

    @Before
    fun rememberKeyboardHeight() {
        composeRule.activityRule.scenario.onActivity { it.configureEdgeToEdgeWindow() }
        previousKeyboardHeight = composeRule.activity.getSharedPreferences("typing-screen-ui", 0)
            .getFloat("keyboard-height-dp", Float.NaN)
    }

    @After
    fun restoreKeyboardHeight() {
        composeRule.activity.getSharedPreferences("typing-screen-ui", 0).edit().apply {
            if (previousKeyboardHeight.isNaN()) remove("keyboard-height-dp")
            else putFloat("keyboard-height-dp", previousKeyboardHeight)
        }.commit()
    }

    @Test
    fun keyboardRetractionDoesNotDropTheMessageBeforeTheTrayReturns() {
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
        composeRule.setPhoneContent {
            AppTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) { PhraseScreen() }
            }
        }
        composeRule.mainClock.autoAdvance = false
        val keyboardBottom = 900
        applyKeyboardInsets(visible = true, bottom = keyboardBottom)
        composeRule.mainClock.advanceTimeBy(300)
        composeRule.onNodeWithContentDescription("Show Typing Screen").assertExists()
        val input = composeRule.onNode(hasSetTextAction())
        val initialTop = input.fetchSemanticsNode().boundsInRoot.top
        val root = composeRule.activity.window.decorView
        val animation = WindowInsetsAnimation(WindowInsets.Type.ime(), null, 160)
        val hiddenInsets = keyboardInsets(visible = false, bottom = 0)

        // Deliver the same callbacks as a native IME retraction, with explicit frames
        // so the layout assertion is independent of the emulator's keyboard and focus.
        composeRule.runOnUiThread {
            root.dispatchWindowInsetsAnimationPrepare(animation)
            root.dispatchApplyWindowInsets(hiddenInsets)
            root.dispatchWindowInsetsAnimationStart(
                animation,
                WindowInsetsAnimation.Bounds(Insets.NONE, Insets.of(0, 0, 0, keyboardBottom)),
            )
        }
        val framePositions = (0..8).map { frame ->
            val fraction = frame / 8f
            val frameInsets = keyboardInsets(visible = true, bottom = (keyboardBottom * (1 - fraction)).toInt())
            composeRule.runOnUiThread {
                animation.fraction = fraction
                root.dispatchWindowInsetsAnimationProgress(frameInsets, listOf(animation))
            }
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            input.fetchSemanticsNode().boundsInRoot.top
        }
        composeRule.runOnUiThread { root.dispatchWindowInsetsAnimationEnd(animation) }
        composeRule.mainClock.advanceTimeBy(400)
        val finalTop = input.fetchSemanticsNode().boundsInRoot.top
        val maximumTop = maxOf(initialTop, finalTop) + 2f
        assertTrue(
            "Message dropped past its resting position during retraction: " +
                "start=$initialTop, end=$finalTop, maximum=${framePositions.maxOrNull()}",
            framePositions.all { it <= maximumTop },
        )
        composeRule.onNodeWithContentDescription("Show keyboard").assertExists()
    }

    @Test
    fun settledKeyboardDoesNotMoveTheMessageWhenTheTrayDelayExpires() {
        // A remembered tray height that differs from the keyboard about to open.
        composeRule.activity.getSharedPreferences("typing-screen-ui", 0).edit()
            .putFloat("keyboard-height-dp", 450f).commit()
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
        composeRule.setPhoneContent {
            AppTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) { PhraseScreen() }
            }
        }
        composeRule.mainClock.autoAdvance = false
        applyKeyboardInsets(visible = true, bottom = 900)
        composeRule.mainClock.advanceTimeBy(300)
        val input = composeRule.onNode(hasSetTextAction())
        val top = input.fetchSemanticsNode().boundsInRoot.top
        // The keyboard has settled. A pending tray timeout must not move it again.
        composeRule.mainClock.advanceTimeBy(800)
        composeRule.waitForIdle()
        assertEquals(top, input.fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    @Test
    fun floatingKeyboardDoesNotReopenTheTypingTray() {
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
        composeRule.setPhoneContent { AppTheme { PhraseScreen() } }
        applyKeyboardInsets(visible = true, bottom = 900)
        composeRule.onNodeWithContentDescription("Show Typing Screen").assertExists()

        // A floating keyboard remains visible but no longer covers the window edge.
        applyKeyboardInsets(visible = true, bottom = 0)
        composeRule.onNodeWithContentDescription("Show Typing Screen").assertExists()
        composeRule.onNodeWithContentDescription("Show keyboard").assertDoesNotExist()

        applyKeyboardInsets(visible = false, bottom = 0)
        composeRule.onNodeWithContentDescription("Show keyboard").assertExists()
    }

    private fun applyKeyboardInsets(visible: Boolean, bottom: Int) {
        val insets = keyboardInsets(visible, bottom)
        composeRule.runOnUiThread {
            composeRule.activity.window.decorView.dispatchApplyWindowInsets(insets)
        }
        composeRule.waitForIdle()
    }

    private fun keyboardInsets(visible: Boolean, bottom: Int): WindowInsets = composeRule.runOnIdle {
        val root = composeRule.activity.window.decorView
        WindowInsets.Builder(root.rootWindowInsets)
            .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, bottom))
            .setVisible(WindowInsets.Type.ime(), visible)
            .build()
    }
}
