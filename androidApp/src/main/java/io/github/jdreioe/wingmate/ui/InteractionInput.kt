package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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

    fun key(key: String, down: Boolean, settings: Settings): Boolean {
        val normalized = normalizeKeyBinding(key)
        val matches = normalized == normalizeKeyBinding(settings.selectKeyBinding) ||
            normalized == normalizeKeyBinding(settings.restModeKeyBinding)
        if (!matches) return false
        if (down) handle(controller.keyDown(key, settings.selectKeyBinding, settings.restModeKeyBinding, now()))
        else controller.keyUp(key)
        refresh()
        return true
    }

    fun tick(settings: Settings) {
        handle(controller.tick(now(), settings.dwellToSelectMillis))
        refresh()
    }

    private fun update(block: AccessInputController.() -> Unit) { controller.block(); refresh() }
    private fun handle(effect: AccessInputEffect?) {
        if (effect is AccessInputEffect.Activate) actions[effect.targetId]?.invoke()
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
    val resumeLabel = stringResource(R.string.interaction_resume)
    val restLabel = stringResource(R.string.interaction_rest_mode)
    val pausedStatus = stringResource(R.string.interaction_paused_status)
    DisposableEffect(host) {
        AndroidAccessInputBus.attach(host)
        onDispose { AndroidAccessInputBus.detach(host) }
    }
    SideEffect { AndroidAccessInputBus.update(settings, enabled) }
    LaunchedEffect(settings.dwellToSelectMillis, enabled) {
        while (true) {
            if (enabled) host.tick(settings)
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
            content()
            if (enabled && (settings.dwellToSelectMillis > 0 || settings.selectKeyBinding.isNotBlank())) {
                Button(
                    onClick = host::togglePause,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .sizeIn(minWidth = 56.dp, minHeight = 56.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                ) {
                    Icon(if (host.state.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = null)
                    Text(if (host.state.isPaused) resumeLabel else restLabel, Modifier.padding(start = 8.dp))
                }
                if (host.state.isPaused) {
                    Row(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 72.dp)
                            .background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(12.dp))
                            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .semantics { liveRegion = LiveRegionMode.Assertive },
                        verticalAlignment = Alignment.CenterVertically,
                    ) { Text(pausedStatus, color = MaterialTheme.colorScheme.inverseOnSurface) }
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
