package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.domain.Settings
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

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
 * │   rows of fixed height,       │ Held message │
 * │   pinned to the top           │ Action grid ↕│
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
    /** The Action grid; give each row the modifier, so Earlier and More stop at whole rows. */
    actions: @Composable (rowModifier: (Int) -> Modifier) -> Unit,
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
    /** The Action grid; give each row the modifier, so Earlier and More stop at whole rows. */
    actions: @Composable (rowModifier: (Int) -> Modifier) -> Unit,
    settings: Settings,
) {
    val controlScale = settings.playbackIconScale.coerceIn(0.75f, 1.5f)
    val actionRow = 56.dp * settings.inputFieldScale.coerceIn(0.75f, 2f)
    BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
        // A short panel (a foldable or laptop window with the on-screen keyboard up, or large
        // scaling) drops Speak, Stop and Keyboard to a still-large 56dp and moves the app actions
        // (Screens, fullscreen, Settings) to the top of the scroll area, so at least one row of it
        // and its Earlier/More buttons stay on screen and every action stays reachable.
        val roomy = maxHeight >= 48.dp + 88.dp * controlScale + 72.dp * controlScale + actionRow + 56.dp + 8.dp * 6
        val speakHeight = if (roomy) 88.dp * controlScale else 56.dp
        val controlHeight = if (roomy) 72.dp * controlScale else 56.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (roomy) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, content = appActions)
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
                    .height(speakHeight)
                    // Switch, dwell and selection keys reach it like any Typing Screen Button.
                    .typingAccessTarget("typing:speak", enabled = true, shape = ButtonDefaults.shape, action = speakAction)
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
                        .heightIn(min = controlHeight)
                        .typingAccessTarget("typing:stop", enabled = stopEnabled, shape = ButtonDefaults.outlinedShape, action = onStop)
                        .selectOnTouchDown(stopEnabled && settings.selectOnTouchDown, onStop),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    StackedLabel(Icons.Filled.Stop, stringResource(R.string.playback_stop))
                }
                val toggleKeyboard = if (keyboardShown) onHideKeyboard else onShowKeyboard
                FilledTonalButton(
                    onClick = toggleKeyboard,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = controlHeight)
                        .typingAccessTarget("typing:keyboard", enabled = true, shape = ButtonDefaults.filledTonalShape, action = toggleKeyboard)
                        .selectOnTouchDown(settings.selectOnTouchDown, toggleKeyboard),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    StackedLabel(
                        if (keyboardShown) Icons.Filled.KeyboardHide else Icons.Filled.Keyboard,
                        stringResource(if (keyboardShown) R.string.typing_hide_keyboard else R.string.typing_show_keyboard),
                    )
                }
            }
            // Everything below Speak, Stop and Keyboard scrolls as one, so a held Message, large
            // scaling or the on-screen keyboard shortens this area instead of clipping it.
            // Touch-down selection fires on contact, so a drag cannot scroll there; the buttons
            // below page through it instead, for touch, dwell and switch alike.
            val scroll = rememberScrollState()
            val items = remember { PanelItems() }
            // The app actions row (short panels) and the held card come and go above the rest,
            // which would leave the top edge between items. Once the new layout is placed, move
            // back onto the whole item at or above the current top, keeping the user's place.
            // Keyed on the held text (a longer one wraps and moves the rows too) and on the viewport
            // (a taller one clamps the end of the scroll mid-item, e.g. when the keyboard hides).
            LaunchedEffect(roomy, heldMessage, scroll.viewportSize) {
                withFrameNanos { }
                val top = scroll.value.toFloat()
                val tops = items.tops()
                if (tops.none { (it - top).absoluteValue < 1f }) {
                    scroll.scrollTo((tops.lastOrNull { it <= top } ?: 0f).toInt())
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // Outside the scroll, so item positions start at 0 and paging lands on them.
                    .padding(top = 8.dp)
                    .verticalScroll(scroll, enabled = !settings.selectOnTouchDown)
                    .onPlaced {
                        items.content = it
                        items.alignEnd(scroll.viewportSize)
                    },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!roomy) {
                    Row(items.item("app").fillMaxWidth(), horizontalArrangement = Arrangement.End, content = appActions)
                }
                if (heldMessage != null) {
                    // Swap is its own stop, so a long held Message never leaves it half shown.
                    HeldMessageCard(heldMessage, onSwapHeld, settings, items.item("held"), swapModifier = items.item("swap"))
                }
                actions { row -> items.item(row) }
                // Room after the last row, so the last page also starts at a whole item.
                Spacer(Modifier.height(with(LocalDensity.current) { items.endPadding.toDp() }))
            }
            if (scroll.maxValue > 0) PanelPager(scroll, items, settings)
        }
    }
}

/**
 * Where the panel's scroll area's items start, so Earlier and More stop at whole items: the
 * first visible item always starts at the top edge, never as a clipped sliver under Stop and
 * Keyboard that a near miss would fire on touch-down.
 */
private class PanelItems {
    var content: LayoutCoordinates? = null
    private val placed = mutableMapOf<Any, LayoutCoordinates>()

    /** Space after the last item that lets the scroll end exactly at an item's top. */
    var endPadding by mutableIntStateOf(0)
        private set

    fun item(key: Any): Modifier = Modifier.onPlaced { placed[key] = it }

    private fun attached(): List<LayoutCoordinates> = placed.values.filter { it.isAttached }

    /** Item tops in scroll-content pixels, top to bottom. */
    fun tops(): List<Float> {
        val parent = content?.takeIf { it.isAttached } ?: return emptyList()
        return attached().map { parent.localPositionOf(it, Offset.Zero).y }.sorted()
    }

    /** Recomputes [endPadding] for a viewport this tall; called as the content is placed. */
    fun alignEnd(viewport: Int) {
        val parent = content?.takeIf { it.isAttached } ?: return
        // Where the scroll would end without the padding.
        val end = (parent.size.height - endPadding - viewport).toFloat()
        endPadding = if (viewport <= 0 || end <= 0f) 0 else {
            ((tops().firstOrNull { it >= end } ?: end) - end).toInt()
        }
    }
}

/** Earlier and More: page the panel's scroll area by whole items. Shown while it overflows. */
@Composable
private fun PanelPager(scroll: ScrollState, items: PanelItems, settings: Settings) {
    val scope = rememberCoroutineScope()
    val requesters = remember { listOf(FocusRequester(), FocusRequester()) }
    val page = { forward: Boolean ->
        scope.launch {
            val top = scroll.value.toFloat()
            val bottom = top + scroll.viewportSize
            val tops = items.tops()
            val target = if (forward) {
                // The item cut off at the bottom (or the next one) becomes the first visible one.
                tops.lastOrNull { it > top + 1 && it < bottom } ?: (top + scroll.viewportSize * 0.8f)
            } else {
                // The earliest item that still lets everything down to the current top fit.
                tops.firstOrNull { it >= top - scroll.viewportSize && it < top - 1 }
                    ?: tops.lastOrNull { it < top - 1 } ?: 0f
            }
            scroll.animateScrollTo(target.toInt())
            // At either end the pressed button disables; keep keyboard and switch users' place
            // by moving focus to the other one.
            val atEnd = if (forward) !scroll.canScrollForward else !scroll.canScrollBackward
            if (atEnd) requesters[if (forward) 0 else 1].requestFocus()
        }
        Unit
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(false, true).forEach { forward ->
            val enabled = if (forward) scroll.canScrollForward else scroll.canScrollBackward
            val action = { page(forward) }
            OutlinedButton(
                onClick = action,
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .focusRequester(requesters[if (forward) 1 else 0])
                    .typingAccessTarget(
                        if (forward) "typing:panel-more" else "typing:panel-earlier",
                        enabled = enabled,
                        shape = ButtonDefaults.outlinedShape,
                        action = action,
                    )
                    .selectOnTouchDown(enabled && settings.selectOnTouchDown, action),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                StackedLabel(
                    if (forward) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                    stringResource(if (forward) R.string.typing_panel_more else R.string.typing_panel_earlier),
                )
            }
        }
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
private fun ColumnScope.HeldMessageCard(
    text: String,
    onSwap: () -> Unit,
    settings: Settings,
    modifier: Modifier = Modifier,
    swapModifier: Modifier = Modifier,
) {
    Column(
        modifier
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
        TextButton(
            onClick = onSwap,
            modifier = swapModifier
                .align(Alignment.End)
                .heightIn(min = 56.dp)
                .typingAccessTarget("typing:swap-held", enabled = true, shape = ButtonDefaults.textShape, action = onSwap)
                .selectOnTouchDown(settings.selectOnTouchDown, onSwap),
        ) {
            Icon(Icons.Filled.SwapHoriz, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.typing_held_message_swap))
        }
    }
}
