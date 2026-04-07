package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.SettingsStateManager
import io.github.jdreioe.wingmate.domain.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

/**
 * Bridge for desktop-only partner window detection.
 * Desktop code sets [deviceConnected] to the PartnerWindowManager's flow;
 * on other platforms this stays false so the toggle never shows.
 */
object PartnerWindowAvailability {
    private val _deviceConnected = MutableStateFlow(false)

    /** Readable from common UI. */
    var deviceConnected: StateFlow<Boolean> = _deviceConnected
        private set

    /** Called from desktop DI to wire the real device detection flow. */
    fun bind(flow: StateFlow<Boolean>) {
        deviceConnected = flow
    }
}

@Composable
fun UiSettingsDialog(onDismissRequest: () -> Unit) {
    val koin = GlobalContext.getOrNull()
    val settingsUseCase = remember { koin?.let { runCatching { it.get<SettingsUseCase>() }.getOrNull() } }
    val settingsStateManager = remember { koin?.let { runCatching { it.get<SettingsStateManager>() }.getOrNull() } }

    var virtualMic by remember { mutableStateOf(false) }
    var autoUpdateEnabled by remember { mutableStateOf(true) }
    var partnerWindowEnabled by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // Partner window device detection (desktop-only, always false on other platforms)
    val partnerDeviceConnected by PartnerWindowAvailability.deviceConnected.collectAsState()

    // Helper function to update settings with reactive updates
    fun updateSettings(update: (Settings) -> Settings) {
        scope.launch {
            if (settingsStateManager != null) {
                // Use reactive state manager for immediate UI updates
                settingsStateManager.updateSettings(update)
            } else if (settingsUseCase != null) {
                // Fallback to direct use case
                withContext(Dispatchers.Default) {
                    val current = runCatching { settingsUseCase.get() }.getOrNull() ?: Settings()
                    val updated = update(current)
                    settingsUseCase.update(updated)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (settingsUseCase != null) {
            val s = withContext(Dispatchers.Default) { runCatching { settingsUseCase.get() }.getOrNull() ?: Settings() }
            virtualMic = s.virtualMicEnabled
            autoUpdateEnabled = s.autoUpdateEnabled
            partnerWindowEnabled = s.partnerWindowEnabled
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Note: UI Scaling settings have been moved to Speech Settings dialog.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                // Virtual microphone setting
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = virtualMic,
                        onCheckedChange = { checked ->
                            virtualMic = checked
                            updateSettings { it.copy(virtualMicEnabled = checked) }
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Use virtual microphone for calls")
                        Text(
                            "Routes TTS audio to a virtual device you can pick as mic in Zoom/Meet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Auto-update setting
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = autoUpdateEnabled,
                        onCheckedChange = { checked ->
                            autoUpdateEnabled = checked
                            updateSettings { it.copy(autoUpdateEnabled = checked) }
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Automatic updates", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Automatically check for and install updates from GitHub.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Partner window display setting — only shown when FTDI device detected
                if (partnerDeviceConnected) {
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = partnerWindowEnabled,
                            onCheckedChange = { checked ->
                                partnerWindowEnabled = checked
                                updateSettings { it.copy(partnerWindowEnabled = checked) }
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Partner window display", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Mirror typed text to the connected TD-I13 partner display.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest, enabled = !loading) {
                Text("Close")
            }
        }
    )
}

