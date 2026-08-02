package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.time.Clock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class HoldToSelectLongPressTest {

    @Composable
    private fun Host(
        holdToSelectMillis: Long,
        onLong: () -> Unit
    ) {
        MaterialTheme {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .testTag("target")
                    .pointerInput(holdToSelectMillis) {
                        detectTapGestures(
                            onPress = {
                                val startTime = Clock.System.now().toEpochMilliseconds()
                                val releasedBeforeLongPress = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    tryAwaitRelease()
                                    true
                                } ?: false
                                if (releasedBeforeLongPress) {
                                    val heldMillis = Clock.System.now().toEpochMilliseconds() - startTime
                                    if (heldMillis >= holdToSelectMillis) {
                                        // activation
                                    }
                                }
                            },
                            onLongPress = { onLong() }
                        )
                    }
            )
        }
    }

    @Test
    fun longPress_stillFiresMenuCallback() = runComposeUiTest {
        var longs by mutableIntStateOf(0)
        setContent { Host(holdToSelectMillis = 300L, onLong = { longs += 1 }) }
        onNodeWithTag("target").performTouchInput { longClick() }
        waitForIdle()
        assertEquals(1, longs, "long-press callback should fire")
    }
}
