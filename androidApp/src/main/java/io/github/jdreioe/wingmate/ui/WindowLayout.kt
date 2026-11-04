package io.github.jdreioe.wingmate.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.unit.dp

/**
 * The Material window width classes. Layouts pick a shape from the width of the window,
 * not the device, so a resized Googlebook window and an unfolded foldable both get the
 * layout that fits. Board grids never change shape with it: only chrome around them does.
 */
enum class WindowWidth {
    /** Under 600dp: phones, and narrow windows. */
    Compact,

    /** 600–839dp: portrait tablets, half-screen windows. */
    Medium,

    /** 840dp and wider: landscape tablets, unfolded foldables, laptop windows. */
    Expanded;

    val isLarge: Boolean get() = this != Compact

    companion object {
        fun of(width: Dp): WindowWidth = when {
            width >= 840.dp -> Expanded
            width >= 600.dp -> Medium
            else -> Compact
        }
    }
}

/** The size of the window this composition is shown in; follows resizing and folding. */
@Composable
fun currentWindowSize(): DpSize = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.toSize().toDpSize() }

/** The width class of the window this composition is shown in. */
@Composable
fun currentWindowWidth(): WindowWidth = WindowWidth.of(currentWindowSize().width)
