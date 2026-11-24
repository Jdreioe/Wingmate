package io.github.jdreioe.wingmate.ui

import android.graphics.Insets
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
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
    private var previousAnimationScales = emptyMap<String, Float>()

    @Before
    fun rememberKeyboardHeight() {
        composeRule.activityRule.scenario.onActivity { it.configureEdgeToEdgeWindow() }
        previousKeyboardHeight = composeRule.activity.getSharedPreferences("typing-screen-ui", 0)
            .getFloat("keyboard-height-dp", Float.NaN)
    }

    @After
    fun restoreKeyboardHeight() {
        if (previousAnimationScales.isNotEmpty()) setAnimationScales(previousAnimationScales)
        composeRule.activity.getSharedPreferences("typing-screen-ui", 0).edit().apply {
            if (previousKeyboardHeight.isNaN()) remove("keyboard-height-dp")
            else putFloat("keyboard-height-dp", previousKeyboardHeight)
        }.commit()
    }

    @Test
    fun keyboardRetractionDoesNotDropTheMessageBeforeTheTrayReturns() {
        // This test observes native animation frames even when the runner disables
        // animations. Restore the runner's settings in teardown for the other tests.
        previousAnimationScales = listOf(
            Settings.Global.ANIMATOR_DURATION_SCALE,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            Settings.Global.WINDOW_ANIMATION_SCALE,
        ).associateWith { Settings.Global.getFloat(composeRule.activity.contentResolver, it, 1f) }
        setAnimationScales(previousAnimationScales.mapValues { 1f })
        composeRule.setPhoneContent {
            AppTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) { PhraseScreen() }
            }
        }
        val input = composeRule.onNode(hasSetTextAction())
        // insetsController.show() fails immediately, and is not retried, when the
        // Compose view is not the served editor yet. The hosted emulator often
        // hits that race (SurfaceFlinger sync timeout, then
        // PHASE_CLIENT_REQUEST_IME_SHOW). Keep focusing the field and ask
        // InputMethodManager directly until the keyboard is actually visible.
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline && !imeVisible()) {
            val focused = input.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true
            if (!focused) input.performClick()
            composeRule.runOnUiThread { requestShowIme() }
            composeRule.waitForIdle()
            if (imeVisible()) break
            SystemClock.sleep(250)
        }
        var windowFocused = false
        composeRule.runOnUiThread {
            windowFocused = composeRule.activity.window.decorView.hasWindowFocus()
        }
        assertTrue("System keyboard did not become visible (windowFocused=$windowFocused)", imeVisible())
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

    private fun setAnimationScales(values: Map<String, Float>) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            values.forEach { (name, value) ->
                check(Settings.Global.putFloat(composeRule.activity.contentResolver, name, value))
            }
        } finally {
            automation.dropShellPermissionIdentity()
        }
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

    private fun imeVisible(): Boolean {
        val insets = composeRule.activity.window.decorView.rootWindowInsets ?: return false
        return insets.isVisible(WindowInsets.Type.ime())
    }

    private fun requestShowIme() {
        val activity = composeRule.activity
        val window = activity.window
        window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        val decor = window.decorView
        if (!decor.hasWindowFocus()) decor.requestFocus()
        val target = window.currentFocus ?: decor.findAndroidComposeView()
        if (target != null) {
            activity.getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(target, 0)
        }
        window.insetsController?.show(WindowInsets.Type.ime())
    }

    private fun View.findAndroidComposeView(): View? {
        if (javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return this
        if (this is ViewGroup) {
            for (index in 0 until childCount) {
                getChildAt(index).findAndroidComposeView()?.let { return it }
            }
        }
        return null
    }
}
