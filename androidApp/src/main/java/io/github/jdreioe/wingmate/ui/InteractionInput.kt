package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.application.AccessInputController
import io.github.jdreioe.wingmate.application.AccessInputEffect
import io.github.jdreioe.wingmate.application.AccessInputState
import io.github.jdreioe.wingmate.application.normalizeKeyBinding
import io.github.jdreioe.wingmate.domain.Settings
import android.view.KeyEvent as AndroidKeyEvent
import kotlinx.coroutines.delay
import kotlin.time.Clock

class AndroidAccessInputHost {
    private val controller = AccessInputController()
    private val actions = mutableMapOf<String, () -> Unit>()
    var state by mutableStateOf(AccessInputState())
        private set

    /** One-shot observer for access events (used for haptic/auditory feedback). */
    var onAccessEvent: ((AccessInputEffect) -> Unit)? = null

    fun register(targetId: String, action: () -> Unit) { actions[targetId] = action }
    fun unregister(targetId: String) {
        actions.remove(targetId)
        controller.targetExited(targetId, now())
        controller.targetBlurred(targetId, now())
        refresh()
    }
    fun enter(targetId: String) = update { targetEntered(targetId, now()) }
    fun exit(targetId: String) = update { targetExited(targetId, now()) }
    fun focus(targetId: String) = update { targetFocused(targetId, now()) }
    fun blur(targetId: String) = update { targetBlurred(targetId, now()) }
    fun togglePause() { handle(controller.togglePaused(now())); refresh() }
    fun restartScan() {
        controller.clearTransientInput(now())
        refresh()
    }

    fun key(key: String, down: Boolean, settings: Settings): Boolean {
        val normalized = normalizeKeyBinding(key)
        val matches = normalized == normalizeKeyBinding(settings.selectKeyBinding) ||
            normalized == normalizeKeyBinding(settings.restModeKeyBinding)
        if (!matches) return false
        if (down) handle(controller.keyDown(key, settings.selectKeyBinding, settings.restModeKeyBinding, now()))
        else handle(controller.keyUp(key, now()))
        refresh()
        return true
    }

    fun tick(settings: Settings) {
        controller.rearmDelayMillis = settings.dwellRearmDelayMillis
        handle(controller.tick(now(), settings.dwellToSelectMillis))
        refresh()
    }

    private fun update(block: AccessInputController.() -> Unit) { controller.block(); refresh() }
    private fun handle(effect: AccessInputEffect?) {
        if (effect == null) return
        if (effect is AccessInputEffect.Activate) actions[effect.targetId]?.invoke()
        onAccessEvent?.invoke(effect)
    }
    private fun refresh() { state = controller.state }
    private fun now(): Long = Clock.System.now().toEpochMilliseconds()
}

object AndroidAccessInputBus {
    @Volatile private var host: AndroidAccessInputHost? = null
    @Volatile private var settings: Settings = Settings()
    @Volatile private var enabled: Boolean = false

    fun attach(value: AndroidAccessInputHost) { host = value }
    fun detach(value: AndroidAccessInputHost) { if (host === value) host = null }
    fun update(settings: Settings, enabled: Boolean) { this.settings = settings; this.enabled = enabled }
    fun restartScan() { host?.restartScan() }

    fun dispatch(event: AndroidKeyEvent): Boolean {
        if (!enabled) return false
        val token = when (event.keyCode) {
            AndroidKeyEvent.KEYCODE_SPACE -> "Space"
            AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> "Enter"
            in AndroidKeyEvent.KEYCODE_F1..AndroidKeyEvent.KEYCODE_F12 -> "F${event.keyCode - AndroidKeyEvent.KEYCODE_F1 + 1}"
            else -> return false
        }
        return when (event.action) {
            AndroidKeyEvent.ACTION_DOWN -> host?.key(token, true, settings) == true
            AndroidKeyEvent.ACTION_UP -> host?.key(token, false, settings) == true
            else -> false
        }
    }
}

val LocalAccessInputHost = compositionLocalOf<AndroidAccessInputHost?> { null }

@Composable
fun RegisterAccessTarget(targetId: String, action: () -> Unit) {
    val host = LocalAccessInputHost.current ?: return
    DisposableEffect(host, targetId, action) {
        host.register(targetId, action)
        onDispose { host.unregister(targetId) }
    }
}

fun Modifier.accessTargetFocus(targetId: String, host: AndroidAccessInputHost?): Modifier =
    if (host == null) this else onFocusChanged { if (it.isFocused) host.focus(targetId) else host.blur(targetId) }.focusable()

@Composable
fun InteractionInputRoot(settings: Settings, enabled: Boolean = true, content: @Composable () -> Unit) {
    val host = remember { AndroidAccessInputHost() }
    val view = LocalView.current
    val resumeLabel = stringResource(R.string.interaction_resume)
    val restLabel = stringResource(R.string.interaction_rest_mode)
    val pausedStatus = stringResource(R.string.interaction_paused_status)
    DisposableEffect(host) {
        AndroidAccessInputBus.attach(host)
        onDispose { AndroidAccessInputBus.detach(host) }
    }
    SideEffect {
        AndroidAccessInputBus.update(settings, enabled)
        host.onAccessEvent = { effect ->
            when (effect) {
                is AccessInputEffect.Activate -> view.performAccessHaptic(AccessHaptic.CONFIRM)
                is AccessInputEffect.PauseChanged -> view.performAccessHaptic(AccessHaptic.TICK)
            }
        }
    }
    LaunchedEffect(settings.dwellToSelectMillis, enabled) {
        if (!enabled || settings.dwellToSelectMillis <= 0) return@LaunchedEffect
        while (true) {
            host.tick(settings)
            delay(16)
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalAccessInputHost provides host) {
        Box(
            Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (!enabled) return@onPreviewKeyEvent false
                    val token = event.key.accessToken() ?: return@onPreviewKeyEvent false
                    when (event.type) {
                        KeyEventType.KeyDown -> host.key(token, true, settings)
                        KeyEventType.KeyUp -> host.key(token, false, settings)
                        else -> false
                    }
                }
        ) {
            var showRestNotice by remember { mutableStateOf(false) }
            val inputPaused = host.state.isPaused
            LaunchedEffect(inputPaused) {
                if (inputPaused) {
                    showRestNotice = true
                    delay(10_000)
                    showRestNotice = false
                } else {
                    showRestNotice = false
                }
            }
            content()
            if (enabled && (settings.dwellToSelectMillis > 0 || settings.selectKeyBinding.isNotBlank())) {
                // Rest toggle participates in dwell/focus selection so switch- and
                // gaze-only users can always pause/resume without the touchscreen.
                val restToggleTargetId = "access.restToggle"
                FloatingActionButton(
                    onClick = host::togglePause,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .accessTargetFocus(restToggleTargetId, host)
                        .pointerInput(host) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    when (event.type) {
                                        PointerEventType.Enter -> host.enter(restToggleTargetId)
                                        PointerEventType.Exit -> host.exit(restToggleTargetId)
                                    }
                                }
                            }
                        }
                        .semantics { liveRegion = LiveRegionMode.Polite }
                ) {
                    Icon(
                        if (inputPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (inputPaused) resumeLabel else restLabel
                    )
                }
                if (inputPaused && showRestNotice) {
                    val holdResumeLabel = stringResource(R.string.interaction_hold_select_resume)
                    Row(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 76.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(14.dp))
                            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .clickable { host.togglePause() }
                            .semantics { liveRegion = LiveRegionMode.Assertive },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(pausedStatus, color = MaterialTheme.colorScheme.inverseOnSurface)
                            if (settings.selectKeyBinding.isNotBlank()) {
                                Text(
                                    holdResumeLabel,
                                    color = MaterialTheme.colorScheme.inverseOnSurface,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun Key.accessToken(): String? = when (this) {
    Key.Spacebar -> "Space"
    Key.Enter, Key.NumPadEnter -> "Enter"
    Key.F1 -> "F1"; Key.F2 -> "F2"; Key.F3 -> "F3"; Key.F4 -> "F4"
    Key.F5 -> "F5"; Key.F6 -> "F6"; Key.F7 -> "F7"; Key.F8 -> "F8"
    Key.F9 -> "F9"; Key.F10 -> "F10"; Key.F11 -> "F11"; Key.F12 -> "F12"
    else -> null
}
