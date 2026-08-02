package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class, ExperimentalFoundationApi::class)
class CombinedClickableClassificationTest {

    @Composable
    private fun Host(
        onTap: () -> Unit,
        onLong: () -> Unit
    ) {
        MaterialTheme {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .testTag("target")
                    .combinedClickable(
                        onClick = onTap,
                        onLongClick = onLong
                    )
            )
        }
    }

    @Test
    fun plainClick_doesNotFireLongClick() = runComposeUiTest {
        var taps by mutableIntStateOf(0)
        var longs by mutableIntStateOf(0)
        setContent {
            Host(
                onTap = { taps += 1 },
                onLong = { longs += 1 }
            )
        }
        onNodeWithTag("target").performClick()
        onNodeWithTag("target").performClick()
        waitForIdle()
        assertEquals(2, taps, "tap count")
        assertEquals(0, longs, "long-press count")
    }

    @Test
    fun longPress_firesLongClickNotClick() = runComposeUiTest {
        var taps by mutableIntStateOf(0)
        var longs by mutableIntStateOf(0)
        setContent {
            Host(
                onTap = { taps += 1 },
                onLong = { longs += 1 }
            )
        }
        onNodeWithTag("target").performTouchInput { longClick() }
        waitForIdle()
        assertEquals(0, taps, "tap count")
        assertEquals(1, longs, "long-press count")
    }
}
