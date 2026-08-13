package io.github.jdreioe.wingmate.ui

import android.view.KeyEvent
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import io.github.jdreioe.wingmate.domain.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class InteractionInputRootTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun activityKeyInputSelectsCurrentTarget() {
        val host = AndroidAccessInputHost()
        var activations = 0
        host.register("target") { activations++ }
        host.focus("target")
        AndroidAccessInputBus.attach(host)
        AndroidAccessInputBus.update(
            settings = Settings(selectKeyBinding = "Space"),
            enabled = true,
        )

        try {
            assertTrue(AndroidAccessInputBus.dispatch(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE)))
            assertTrue(AndroidAccessInputBus.dispatch(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE)))
            assertEquals(1, activations)
        } finally {
            AndroidAccessInputBus.detach(host)
        }
    }

    @Test
    fun composeKeyInputSelectsFocusedTargetAndHonorsRestMode() {
        val focusRequester = FocusRequester()
        var activations = 0

        composeRule.setContent {
            InteractionInputRoot(
                settings = Settings(
                    selectKeyBinding = "Space",
                    restModeKeyBinding = "F8",
                ),
            ) {
                val host = LocalAccessInputHost.current
                RegisterAccessTarget("target") { activations++ }
                Text(
                    text = "Target",
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .accessTargetFocus("target", host)
                        .testTag("target"),
                )
            }
        }

        composeRule.runOnIdle { focusRequester.requestFocus() }

        composeRule.onNodeWithTag("target").performKeyInput {
            keyDown(Key.Spacebar)
            keyUp(Key.Spacebar)
        }
        composeRule.runOnIdle { assertEquals(1, activations) }

        composeRule.onNodeWithTag("target").performKeyInput {
            keyDown(Key.F8)
            keyUp(Key.F8)
            keyDown(Key.Spacebar)
            keyUp(Key.Spacebar)
            keyDown(Key.F8)
            keyUp(Key.F8)
            keyDown(Key.Spacebar)
            keyUp(Key.Spacebar)
        }
        composeRule.runOnIdle { assertEquals(2, activations) }
    }
}
