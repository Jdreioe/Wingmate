package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.application.FeatureUsageEvents
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.application.reportEvent
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.SettingsStateManager
import io.github.jdreioe.wingmate.domain.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import wingmatekmp.composeapp.generated.resources.Res
import wingmatekmp.composeapp.generated.resources.common_close
import wingmatekmp.composeapp.generated.resources.ui_settings_auto_updates_desc
import wingmatekmp.composeapp.generated.resources.ui_settings_auto_updates_title
import wingmatekmp.composeapp.generated.resources.ui_settings_feature_reporting_desc
import wingmatekmp.composeapp.generated.resources.ui_settings_feature_reporting_title
import wingmatekmp.composeapp.generated.resources.ui_settings_note_scaling_moved
import wingmatekmp.composeapp.generated.resources.ui_settings_partner_window_desc
import wingmatekmp.composeapp.generated.resources.ui_settings_partner_window_title
import wingmatekmp.composeapp.generated.resources.ui_settings_title
import wingmatekmp.composeapp.generated.resources.ui_settings_virtual_mic_title
import wingmatekmp.composeapp.generated.resources.ui_settings_accessibility_title
import wingmatekmp.composeapp.generated.resources.ui_settings_show_labels_title
import wingmatekmp.composeapp.generated.resources.ui_settings_show_symbols_title
import wingmatekmp.composeapp.generated.resources.ui_settings_label_at_top_title
import wingmatekmp.composeapp.generated.resources.ui_settings_hold_to_select_title
import wingmatekmp.composeapp.generated.resources.ui_settings_hold_to_select_desc
import wingmatekmp.composeapp.generated.resources.ui_settings_grid_columns_title
import wingmatekmp.composeapp.generated.resources.ui_settings_high_contrast_title

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
    val settingsUseCase = koinInject<SettingsUseCase>()
    val settingsStateManager = koinInject<SettingsStateManager>()
    val featureUsageReporter = koinInject<FeatureUsageReporter>()

    var virtualMic by remember { mutableStateOf(false) }
    var autoUpdateEnabled by remember { mutableStateOf(true) }
    var featureUsageReportingEnabled by remember { mutableStateOf(false) }
    var partnerWindowEnabled by remember { mutableStateOf(false) }
    var showLabels by remember { mutableStateOf(true) }
    var showSymbols by remember { mutableStateOf(true) }
    var labelAtTop by remember { mutableStateOf(false) }
    var holdToSelectMillis by remember { mutableStateOf(0L) }
    var gridColumns by remember { mutableStateOf(3) }
    var highContrastMode by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // Partner window device detection (desktop-only, always false on other platforms)
    val partnerDeviceConnected by PartnerWindowAvailability.deviceConnected.collectAsStateWithLifecycle()

    // Helper function to update settings with reactive updates
    fun updateSettings(update: (Settings) -> Settings) {
        scope.launch {
            // Use reactive state manager for immediate UI updates
            settingsStateManager.updateSettings(update)
        }
    }

    LaunchedEffect(Unit) {
        val s = withContext(Dispatchers.Default) { runCatching { settingsUseCase.get() }.getOrNull() ?: Settings() }
        virtualMic = s.virtualMicEnabled
        autoUpdateEnabled = s.autoUpdateEnabled
        featureUsageReportingEnabled = s.featureUsageReportingEnabled
        partnerWindowEnabled = s.partnerWindowEnabled
        showLabels = s.showLabels
        showSymbols = s.showSymbols
        labelAtTop = s.labelAtTop
        holdToSelectMillis = s.holdToSelectMillis
        gridColumns = s.gridColumns
        highContrastMode = s.highContrastMode
        featureUsageReporter.setEnabled(s.featureUsageReportingEnabled)
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(Res.string.ui_settings_title), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(Res.string.ui_settings_note_scaling_moved),
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
                        Text(stringResource(Res.string.ui_settings_virtual_mic_title))
                        Text(
                            stringResource(Res.string.ui_settings_virtual_mic_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))

                // Optional feature usage reporting (Android Firebase implementation)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = featureUsageReportingEnabled,
                        onCheckedChange = { checked ->
                            featureUsageReportingEnabled = checked
                            updateSettings { it.copy(featureUsageReportingEnabled = checked) }
                            featureUsageReporter.setEnabled(checked)
                            featureUsageReporter.reportEvent(
                                FeatureUsageEvents.ANALYTICS_CONSENT_CHANGED,
                                "enabled" to checked.toString(),
                                "source" to "ui_settings_dialog"
                            )
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(Res.string.ui_settings_feature_reporting_title), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(Res.string.ui_settings_feature_reporting_desc),
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
                        Text(stringResource(Res.string.ui_settings_auto_updates_title), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(Res.string.ui_settings_auto_updates_desc),
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
                            Text(stringResource(Res.string.ui_settings_partner_window_title), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(Res.string.ui_settings_partner_window_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                
                // Accessibility Section
                Text(
                    stringResource(Res.string.ui_settings_accessibility_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Show Labels
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = showLabels,
                        onCheckedChange = { checked ->
                            showLabels = checked
                            updateSettings { it.copy(showLabels = checked) }
                        }
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(Res.string.ui_settings_show_labels_title))
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Show Symbols
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = showSymbols,
                        onCheckedChange = { checked ->
                            showSymbols = checked
                            updateSettings { it.copy(showSymbols = checked) }
                        }
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(Res.string.ui_settings_show_symbols_title))
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Label position (only if labels shown)
                if (showLabels && showSymbols) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = labelAtTop,
                            onCheckedChange = { checked ->
                                labelAtTop = checked
                                updateSettings { it.copy(labelAtTop = checked) }
                            }
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(Res.string.ui_settings_label_at_top_title))
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Hold to Select
                Column {
                    Text(
                        stringResource(Res.string.ui_settings_hold_to_select_title) + ": $holdToSelectMillis",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        stringResource(Res.string.ui_settings_hold_to_select_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = holdToSelectMillis.toFloat(),
                        onValueChange = { 
                            holdToSelectMillis = it.toLong()
                        },
                        onValueChangeFinished = {
                            updateSettings { it.copy(holdToSelectMillis = holdToSelectMillis) }
                        },
                        valueRange = 0f..2000f,
                        steps = 19 // 0, 100, 200, ..., 2000
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // High Contrast Mode
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = highContrastMode,
                        onCheckedChange = { checked ->
                            highContrastMode = checked
                            updateSettings { it.copy(highContrastMode = checked) }
                        }
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(Res.string.ui_settings_high_contrast_title))
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Grid Columns Slider
                Column {
                    Text(
                        stringResource(Res.string.ui_settings_grid_columns_title) + ": $gridColumns",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Slider(
                        value = gridColumns.toFloat(),
                        onValueChange = { 
                            gridColumns = it.toInt()
                        },
                        onValueChangeFinished = {
                            updateSettings { it.copy(gridColumns = gridColumns) }
                        },
                        valueRange = 1f..6f,
                        steps = 4 // 1, 2, 3, 4, 5, 6
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest, enabled = !loading) {
                Text(stringResource(Res.string.common_close))
            }
        }
    )
}

