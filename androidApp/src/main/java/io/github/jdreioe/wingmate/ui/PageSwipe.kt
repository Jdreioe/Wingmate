package io.github.jdreioe.wingmate.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Fraction of the Page width a swipe must cross to move to the next Page. */
private const val SWIPE_THRESHOLD = 0.25f

/**
 * Opt-in horizontal swipe between neighbouring Pages (#121). The Page follows the finger;
 * releasing past a quarter of its width calls [onSwipe] with +1 (next) or -1 (previous),
 * and anything shorter springs back. Taps and vertical scrolling stay with the content,
 * and a swipe cancels the Button it started on.
 */
@Composable
internal fun PageSwipeBox(
    enabled: Boolean,
    onSwipe: (step: Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    val gesture = if (enabled) {
        Modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    val threshold = size.width * SWIPE_THRESHOLD
                    val step = when {
                        offset.value <= -threshold -> 1
                        offset.value >= threshold -> -1
                        else -> 0
                    }
                    scope.launch {
                        if (step != 0) {
                            currentOnSwipe(step)
                            offset.snapTo(0f)
                        } else {
                            offset.animateTo(0f)
                        }
                    }
                },
                onDragCancel = { scope.launch { offset.animateTo(0f) } },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    scope.launch { offset.snapTo(offset.value + amount) }
                },
            )
        }
    } else {
        Modifier
    }
    Box(modifier.then(gesture)) {
        Box(Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }) { content() }
    }
}
