package io.github.jdreioe.wingmate.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import io.github.jdreioe.wingmate.application.SelectionHighlight
import kotlinx.coroutines.delay
import kotlin.time.Clock

/**
 * The Button that was just selected, shown for [durationMillis] (#120). Selecting
 * again moves the highlight and restarts its time; 0 turns highlighting off.
 */
@Stable
internal class SelectionHighlightState(private val durationMillis: () -> Long) {
    private val highlight = SelectionHighlight()
    var buttonId by mutableStateOf<String?>(null)
        private set
    internal var generation by mutableLongStateOf(0L)
        private set

    fun select(buttonId: String) {
        highlight.activate(buttonId, Clock.System.now().toEpochMilliseconds())
        if (durationMillis() > 0) {
            this.buttonId = buttonId
            generation = highlight.generation
        }
    }

    internal fun expire() {
        if (highlight.highlightedTarget(Clock.System.now().toEpochMilliseconds(), durationMillis()) != buttonId) buttonId = null
    }
}

@Composable
internal fun rememberSelectionHighlightState(durationMillis: Long): SelectionHighlightState {
    val currentDuration by rememberUpdatedState(durationMillis)
    val state = remember { SelectionHighlightState { currentDuration } }
    LaunchedEffect(state.generation) {
        if (state.buttonId != null) {
            delay(currentDuration)
            state.expire()
        }
    }
    return state
}
