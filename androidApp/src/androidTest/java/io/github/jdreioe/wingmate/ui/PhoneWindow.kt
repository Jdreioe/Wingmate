package io.github.jdreioe.wingmate.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.dp

/**
 * Shows [content] as in a portrait phone's window (411x891dp), so tests of the phone layouts behave the
 * same on a tablet or foldable test device; layouts choose their shape from the window size.
 */
@Composable
fun PhoneWindow(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val focused = LocalWindowInfo.current.isWindowFocused
    val phone = remember(density, focused) {
        object : WindowInfo {
            override val isWindowFocused = focused
            override val containerSize = with(density) { IntSize(411.dp.roundToPx(), 891.dp.roundToPx()) }
        }
    }
    // The phone layout also reads the orientation, so report a portrait phone there too.
    val configuration = LocalConfiguration.current
    val portrait = remember(configuration) {
        Configuration(configuration).apply {
            orientation = Configuration.ORIENTATION_PORTRAIT
            screenWidthDp = 411
            screenHeightDp = 891
        }
    }
    CompositionLocalProvider(LocalWindowInfo provides phone, LocalConfiguration provides portrait, content = content)
}

/** [ComposeContentTestRule.setContent] in a phone-sized window; see [PhoneWindow]. */
fun ComposeContentTestRule.setPhoneContent(content: @Composable () -> Unit) = setContent { PhoneWindow(content) }
