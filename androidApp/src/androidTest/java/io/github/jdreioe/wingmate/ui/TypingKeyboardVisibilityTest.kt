package io.github.jdreioe.wingmate.ui

import android.graphics.Insets
import android.os.SystemClock
import android.view.WindowInsets
import android.view.WindowManager
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
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
    private var previousTrayHeight = Float.NaN

    @Before
    fun rememberKeyboardHeight() {
        composeRule.activityRule.scenario.onActivity { it.configureEdgeToEdgeWindow() }
        previousKeyboardHeight = composeRule.activity.getSharedPreferences("typing-screen-ui", 0)
            .getFloat("keyboard-height-dp", Float.NaN)
        previousTrayHeight = composeRule.activity.getSharedPreferences("typing-screen-ui", 0)
            .getFloat("tray-height-dp", Float.NaN)
    }

    @After
    fun restoreKeyboardHeight() {
        composeRule.activity.getSharedPreferences("typing-screen-ui", 0).edit().apply {
            if (previousKeyboardHeight.isNaN()) remove("keyboard-height-dp")
            else putFloat("keyboard-height-dp", previousKeyboardHeight)
            if (previousTrayHeight.isNaN()) remove("tray-height-dp")
            else putFloat("tray-height-dp", previousTrayHeight)
        }.commit()
    }

    @Test
    fun keyboardRetractionDoesNotDropTheMessageBeforeTheTrayReturns() {
        composeRule.activity.getSharedPreferences("typing-screen-ui", 0).edit()
            .remove("tray-height-dp").commit()
        composeRule.setContent {
            AppTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) { PhraseScreen() }
            }
        }
        val input = composeRule.onNode(hasSetTextAction())
        input.performClick()
        composeRule.runOnUiThread {
            val window = composeRule.activity.window
            window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            window.insetsController!!.show(WindowInsets.Type.ime())
        }
        composeRule.waitUntil(5_000) {
            composeRule.activity.window.decorView.rootWindowInsets
                .isVisible(WindowInsets.Type.ime())
        }
        // Wait for the native keyboard animation and remembered height to settle.
        waitForNativeAnimation()
        composeRule.waitForIdle()
        val node = input.fetchSemanticsNode()
        val initialTop = node.boundsInRoot.top
        val framePositions = mutableListOf<Float>()
        val root = composeRule.activity.window.decorView
        val listener = ViewTreeObserver.OnPreDrawListener {
            framePositions.add(node.boundsInRoot.top)
            true
        }
        try {
            composeRule.runOnUiThread {
                root.viewTreeObserver.addOnPreDrawListener(listener)
                composeRule.activity.window.insetsController!!.hide(WindowInsets.Type.ime())
            }
            waitForNativeAnimation()
            composeRule.waitForIdle()
        } finally {
            composeRule.runOnUiThread {
                root.viewTreeObserver.removeOnPreDrawListener(listener)
            }
        }
        val finalTop = input.fetchSemanticsNode().boundsInRoot.top
        assertTrue("Expected native keyboard animation frames", framePositions.size > 2)
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
        val preferences = composeRule.activity.getSharedPreferences("typing-screen-ui", 0)
        val previousHeight = preferences.getFloat("tray-height-dp", Float.NaN)
        preferences.edit().putFloat("tray-height-dp", 450f).commit()
        try {
            composeRule.activityRule.scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            }
            composeRule.setContent {
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
        } finally {
            preferences.edit().apply {
                if (previousHeight.isNaN()) remove("tray-height-dp")
                else putFloat("tray-height-dp", previousHeight)
            }.commit()
        }
    }

    @Test
    fun floatingKeyboardDoesNotReopenTheTypingTray() {
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
        composeRule.setContent { AppTheme { PhraseScreen() } }
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
        composeRule.runOnUiThread {
            val root = composeRule.activity.window.decorView
            val insets = WindowInsets.Builder(root.rootWindowInsets)
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, bottom))
                .setVisible(WindowInsets.Type.ime(), visible)
                .build()
            root.dispatchApplyWindowInsets(insets)
        }
        composeRule.waitForIdle()
    }

    private fun waitForNativeAnimation() {
        val deadline = SystemClock.uptimeMillis() + 800
        // Keep Compose's test clock advancing while the native keyboard uses real time.
        composeRule.waitUntil(2_000) { SystemClock.uptimeMillis() >= deadline }
    }
}
