package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.domain.Settings

/**
 * Whether Typing uses [WideTypingLayout]: at least Medium width and tall enough for the
 * Message, a Phrase row and the speech controls together. Phone landscape (about 891x411dp)
 * stays on the phone layout. Decided by the window, so the on-screen keyboard never changes it.
 */
internal fun usesWideTyping(window: DpSize): Boolean =
    WindowWidth.of(window.width).isLarge && window.height >= 480.dp

/** What the Speak button does now. */
internal enum class WideSpeechState { Idle, Playing, Paused }

/**
 * Typing on a tablet, unfolded foldable or laptop window. Every region keeps its place:
 *
 * ```
 * ┌───────────────────────────────┬──────────────┐
 * │ Message (fixed height)        │ app actions  │
 * │ predictions (fixed, optional) │ Speak        │
 * │ Typing Screen: Pages, Phrases │ Stop | Kbd   │
 * │   rows of fixed height,       │ Action grid  │
 * │   pinned to the top           │ Held message │
 * └───────────────────────────────┴──────────────┘
 * ```
 *
 * The on-screen keyboard only takes space from the bottom (the app root pads for it), so the
 * Message, the first Phrase rows and Speak stay where they were; lower rows scroll. There is
 * no tray to show or hide, so a hardware keyboard and an on-screen one behave the same.
 */
@Composable
internal fun WideTypingLayout(
    panelWidth: Dp,
    message: @Composable () -> Unit,
    predictions: (@Composable () -> Unit)?,
    typingScreen: @Composable () -> Unit,
    appActions: @Composable RowScope.() -> Unit,
    speech: WideSpeechState,
    onSpeak: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    stopEnabled: Boolean,
    onStop: () -> Unit,
    keyboardShown: Boolean,
    onShowKeyboard: () -> Unit,
    onHideKeyboard: () -> Unit,
    heldMessage: String?,
    onSwapHeld: () -> Unit,
    actions: @Composable () -> Unit,
    settings: Settings,
    modifier: Modifier = Modifier,
) {
    Row(modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            message()
            if (predictions != null) {
                Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.CenterStart) { predictions() }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { typingScreen() }
        }
        Surface(
            modifier = Modifier.width(panelWidth).fillMaxHeight(),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            WideTypingPanel(
                appActions = appActions,
                speech = speech,
                onSpeak = onSpeak,
                onPause = onPause,
                onResume = onResume,
                stopEnabled = stopEnabled,
                onStop = onStop,
                keyboardShown = keyboardShown,
                onShowKeyboard = onShowKeyboard,
                onHideKeyboard = onHideKeyboard,
                heldMessage = heldMessage,
                onSwapHeld = onSwapHeld,
                actions = actions,
                settings = settings,
            )
        }
    }
}

@Composable
private fun WideTypingPanel(
    appActions: @Composable RowScope.() -> Unit,
    speech: WideSpeechState,
    onSpeak: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    stopEnabled: Boolean,
    onStop: () -> Unit,
    keyboardShown: Boolean,
    onShowKeyboard: () -> Unit,
    onHideKeyboard: () -> Unit,
    heldMessage: String?,
    onSwapHeld: () -> Unit,
    actions: @Composable () -> Unit,
    settings: Settings,
) {
    val controlScale = settings.playbackIconScale.coerceIn(0.75f, 1.5f)
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, content = appActions)
        // Speak turns into Pause while speech plays and Resume while paused, in the same place.
        val speakAction = when (speech) {
            WideSpeechState.Idle -> onSpeak
            WideSpeechState.Playing -> onPause
            WideSpeechState.Paused -> onResume
        }
        Button(
            onClick = speakAction,
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp * controlScale)
                .selectOnTouchDown(settings.selectOnTouchDown, speakAction),
        ) {
            Icon(
                if (speech == WideSpeechState.Playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(
                    when (speech) {
                        WideSpeechState.Idle -> R.string.playback_play
                        WideSpeechState.Playing -> R.string.playback_pause
                        WideSpeechState.Paused -> R.string.playback_resume
                    }
                ),
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Always shown, disabled while nothing plays, so Keyboard never moves.
            OutlinedButton(
                onClick = onStop,
                enabled = stopEnabled,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 72.dp * controlScale)
                    .selectOnTouchDown(stopEnabled && settings.selectOnTouchDown, onStop),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                StackedLabel(Icons.Filled.Stop, stringResource(R.string.playback_stop))
            }
            FilledTonalButton(
                onClick = if (keyboardShown) onHideKeyboard else onShowKeyboard,
                modifier = Modifier.weight(1f).heightIn(min = 72.dp * controlScale),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                StackedLabel(
                    if (keyboardShown) Icons.Filled.KeyboardHide else Icons.Filled.Keyboard,
                    stringResource(if (keyboardShown) R.string.typing_hide_keyboard else R.string.typing_show_keyboard),
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) { actions() }
        // Below the Action grid, so holding a Message shortens the grid instead of moving it.
        if (heldMessage != null) HeldMessageCard(heldMessage, onSwapHeld, settings)
    }
}

/** An icon above its label, so two side-by-side buttons fit their labels in a narrow panel. */
@Composable
private fun StackedLabel(icon: ImageVector, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null)
        Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ColumnScope.HeldMessageCard(text: String, onSwap: () -> Unit, settings: Settings) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.large)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.typing_held_message, text),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale,
                ),
            )
        }
        TextButton(onClick = onSwap, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
            Icon(Icons.Filled.SwapHoriz, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.typing_held_message_swap))
        }
    }
}
