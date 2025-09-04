package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.domain.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

@Composable
fun UiSettingsDialog(onDismissRequest: () -> Unit) {
    val koin = GlobalContext.getOrNull()
    val settingsUseCase = remember { koin?.let { runCatching { it.get<SettingsUseCase>() }.getOrNull() } }

    var virtualMic by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(settingsUseCase) {
        if (settingsUseCase != null) {
            val s = withContext(Dispatchers.Default) { runCatching { settingsUseCase.get() }.getOrNull() ?: Settings() }
            virtualMic = s.virtualMicEnabled
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (isDesktop()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = virtualMic,
                            onCheckedChange = { checked ->
                                virtualMic = checked
                                // persist immediately
                                if (settingsUseCase != null) {
                                    scope.launch {
                                        withContext(Dispatchers.Default) {
                                            val current = runCatching { settingsUseCase.get() }.getOrNull() ?: Settings()
                                            settingsUseCase.update(current.copy(virtualMicEnabled = checked))
                                        }
                                    }
                                }
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Use virtual microphone for calls", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Routes TTS audio to a virtual device you can pick as mic in Zoom/Meet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Text(
                        "No desktop-specific settings available on this platform.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
