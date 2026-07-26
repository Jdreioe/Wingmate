package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.application.FeatureUsageEvents
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.application.reportEvent
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.SettingsStateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.getKoin
import wingmatekmp.composeapp.generated.resources.Res
import wingmatekmp.composeapp.generated.resources.ui_settings_feature_reporting_desc
import wingmatekmp.composeapp.generated.resources.ui_settings_feature_reporting_title
import wingmatekmp.composeapp.generated.resources.*

@Composable
fun AzureSettingsDialog(show: Boolean, onDismiss: () -> Unit, onSaved: (() -> Unit)? = null) {
    if (!show) return

    val koin = getKoin()
    // get dependencies in a safe way
    val configRepo = remember(koin) { koin.getOrNull<ConfigRepository>() }
    val settingsUseCase = remember(koin) { koin.getOrNull<SettingsUseCase>() }
    val settingsStateManager = remember(koin) { koin.getOrNull<SettingsStateManager>() }
    val featureUsageReporter = remember(koin) { koin.getOrNull<FeatureUsageReporter>() }

    // Log which ConfigRepository implementation we got (helps diagnose persistence)
    LaunchedEffect(configRepo) {
        println("ConfigRepository implementation: ${configRepo?.javaClass?.name ?: "<none>"}")
    }

    if (configRepo == null) {
        AlertDialog(
            onDismissRequest = onDismiss, 
            title = { Text(stringResource(Res.string.speech_settings_title)) },
            text = { Text(stringResource(Res.string.speech_settings_config_unavailable)) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_ok)) }
            }
        )
        return
    }

    var endpoint by remember { mutableStateOf("") }
    var subscriptionKey by remember { mutableStateOf("") }
    var ttsEngine by remember { mutableStateOf(TtsEngine.SYSTEM) }
    var virtualMic by remember { mutableStateOf(false) }
    var featureUsageReportingEnabled by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    
    // UI Scaling state variables
    var fontSizeScale by remember { mutableStateOf(1.0f) }
    var playbackIconScale by remember { mutableStateOf(1.0f) }
    var categoryChipScale by remember { mutableStateOf(1.0f) }
    var buttonScale by remember { mutableStateOf(1.0f) }
    var inputFieldScale by remember { mutableStateOf(1.0f) }
    
    // Theme state variables
    var forceDarkTheme by remember { mutableStateOf<Boolean?>(null) }
    var useCustomColors by remember { mutableStateOf(false) }
    var primaryColor by remember { mutableStateOf("") }
    
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        // load existing config
        val cfg = withContext(Dispatchers.Default) { configRepo.getSpeechConfig() }
        println("Loaded config: $cfg")
        cfg?.let {
            endpoint = it.endpoint
            subscriptionKey = it.subscriptionKey
        }
        
        // load TTS preference and UI scaling settings
        if (settingsUseCase != null) {
            val settings = withContext(Dispatchers.Default) { 
                runCatching { settingsUseCase.get() }.getOrNull() ?: Settings()
            }
            ttsEngine = settings.ttsEngine
            virtualMic = settings.virtualMicEnabled
            featureUsageReportingEnabled = settings.featureUsageReportingEnabled
            fontSizeScale = settings.fontSizeScale
            playbackIconScale = settings.playbackIconScale
            categoryChipScale = settings.categoryChipScale
            buttonScale = settings.buttonScale
            inputFieldScale = settings.inputFieldScale
            forceDarkTheme = settings.forceDarkTheme
            useCustomColors = settings.useCustomColors
            primaryColor = settings.primaryColor ?: "#7C4DFF"
            featureUsageReporter?.setEnabled(settings.featureUsageReportingEnabled)
        }
        loading = false
    }
    
    // Helper function to update settings with immediate notification
    suspend fun updateSettings(update: (Settings) -> Settings) {
        if (settingsStateManager != null) {
            // Use the state manager for reactive updates
            settingsStateManager.updateSettings(update)
        } else {
            // Fallback to direct use case updates
            settingsUseCase?.let { useCase ->
                withContext(Dispatchers.Default) {
                    val current = runCatching { useCase.get() }.getOrNull() ?: Settings()
                    val updated = update(current)
                    useCase.update(updated)
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.speech_settings_title)) },
        text = {
            if (loading) {
                CircularProgressIndicator()
            } else {
                Column {
                    // TTS Toggle
                    Text(stringResource(Res.string.ui_settings_tts_engine_group), style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = ttsEngine != TtsEngine.SYSTEM,
                            onClick = { ttsEngine = TtsEngine.AZURE_USER_RESOURCE },
                            label = { Text(stringResource(Res.string.ui_settings_azure_tts)) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = ttsEngine == TtsEngine.SYSTEM,
                            onClick = { ttsEngine = TtsEngine.SYSTEM },
                            label = { Text(stringResource(Res.string.ui_settings_system_tts)) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    
                    // Azure Configuration (only show when Azure TTS is selected)
                    if (ttsEngine != TtsEngine.SYSTEM) {
                        Spacer(modifier = Modifier.height(16.dp))
                        val showKeyboard = rememberShowKeyboardOnFocus()
                        OutlinedTextField(
                            value = endpoint,
                            onValueChange = { endpoint = it },
                            label = { Text(stringResource(Res.string.ui_settings_region_endpoint)) },
                            placeholder = { Text(stringResource(Res.string.ui_settings_region_example)) },
                            modifier = Modifier.then(showKeyboard)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = subscriptionKey,
                            onValueChange = { subscriptionKey = it },
                            label = { Text(stringResource(Res.string.ui_settings_subscription_key)) },
                            modifier = Modifier.then(showKeyboard)
                        )
                    }

                    // Desktop virtual mic toggle
                    if (isDesktop()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = virtualMic, onCheckedChange = { checked -> virtualMic = checked })
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
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(
                            checked = featureUsageReportingEnabled,
                            onCheckedChange = { checked ->
                                featureUsageReportingEnabled = checked
                                scope.launch {
                                    updateSettings { it.copy(featureUsageReportingEnabled = checked) }
                                    featureUsageReporter?.setEnabled(checked)
                                    featureUsageReporter?.reportEvent(
                                        FeatureUsageEvents.ANALYTICS_CONSENT_CHANGED,
                                        "enabled" to checked.toString(),
                                        "source" to "speech_settings_dialog"
                                    )
                                }
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(stringResource(Res.string.ui_settings_feature_reporting_title))
                            Text(
                                stringResource(Res.string.ui_settings_feature_reporting_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // UI Scaling Settings
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        stringResource(Res.string.ui_settings_scaling),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    // Font Size Scale (with stepped values)
                    Column(modifier = Modifier.padding(bottom = 12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Text(stringResource(Res.string.ui_settings_font_size), style = MaterialTheme.typography.bodyMedium)
                            Text("${(fontSizeScale * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        }
                        Slider(
                            value = fontSizeScale,
                            onValueChange = { newValue ->
                                // Snap to steps of 0.1
                                val stepped = (newValue * 10).toInt() / 10f
                                fontSizeScale = stepped
                                scope.launch {
                                    updateSettings { it.copy(fontSizeScale = stepped) }
                                }
                            },
                            valueRange = 0.5f..2.0f,
                            steps = 14, // 15 total values (0.5, 0.6, 0.7, ..., 2.0)
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    
                    // Playback Icons Scale
                    Column(modifier = Modifier.padding(bottom = 12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Text(stringResource(Res.string.ui_settings_playback_icons), style = MaterialTheme.typography.bodyMedium)
                            Text("${(playbackIconScale * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        }
                        Slider(
                            value = playbackIconScale,
                            onValueChange = { newValue ->
                                val stepped = (newValue * 10).toInt() / 10f
                                playbackIconScale = stepped
                                scope.launch {
                                    updateSettings { it.copy(playbackIconScale = stepped) }
                                }
                            },
                            valueRange = 0.5f..2.0f,
                            steps = 14,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    
                    // Category Chips Scale
                    Column(modifier = Modifier.padding(bottom = 12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Text(stringResource(Res.string.ui_settings_category_chips), style = MaterialTheme.typography.bodyMedium)
                            Text("${(categoryChipScale * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        }
                        Slider(
                            value = categoryChipScale,
                            onValueChange = { newValue ->
                                val stepped = (newValue * 10).toInt() / 10f
                                categoryChipScale = stepped
                                scope.launch {
                                    updateSettings { it.copy(categoryChipScale = stepped) }
                                }
                            },
                            valueRange = 0.5f..2.0f,
                            steps = 14,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    
                    // Buttons Scale
                    Column(modifier = Modifier.padding(bottom = 12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Text(stringResource(Res.string.ui_settings_buttons), style = MaterialTheme.typography.bodyMedium)
                            Text("${(buttonScale * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        }
                        Slider(
                            value = buttonScale,
                            onValueChange = { newValue ->
                                val stepped = (newValue * 10).toInt() / 10f
                                buttonScale = stepped
                                scope.launch {
                                    updateSettings { it.copy(buttonScale = stepped) }
                                }
                            },
                            valueRange = 0.5f..2.0f,
                            steps = 14,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    
                    // Input Fields Scale
                    Column(modifier = Modifier.padding(bottom = 16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Text(stringResource(Res.string.ui_settings_input_fields), style = MaterialTheme.typography.bodyMedium)
                            Text("${(inputFieldScale * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        }
                        Slider(
                            value = inputFieldScale,
                            onValueChange = { newValue ->
                                val stepped = (newValue * 10).toInt() / 10f
                                inputFieldScale = stepped
                                scope.launch {
                                    updateSettings { it.copy(inputFieldScale = stepped) }
                                }
                            },
                            valueRange = 0.5f..2.0f,
                            steps = 14,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                // save
                scope.launch {
                    withContext(Dispatchers.Default) {
                        // Save TTS preference and UI scaling settings
                        if (settingsUseCase != null) {
                            val current = runCatching { settingsUseCase.get() }.getOrNull() ?: Settings()
                            val updated = current.copy(
                                ttsEngine = ttsEngine, 
                                virtualMicEnabled = virtualMic,
                                featureUsageReportingEnabled = featureUsageReportingEnabled,
                                fontSizeScale = fontSizeScale,
                                playbackIconScale = playbackIconScale,
                                categoryChipScale = categoryChipScale,
                                buttonScale = buttonScale,
                                inputFieldScale = inputFieldScale
                            )
                            settingsUseCase.update(updated)
                            featureUsageReporter?.setEnabled(featureUsageReportingEnabled)
                        }

                        // Save Azure config only if Azure TTS is selected
                        if (ttsEngine != TtsEngine.SYSTEM && endpoint.isNotBlank() && subscriptionKey.isNotBlank()) {
                            println("Saving speech config: endpoint='$endpoint'")
                            try {
                                configRepo.saveSpeechConfig(SpeechServiceConfig(endpoint = endpoint, subscriptionKey = subscriptionKey))
                                println("Successfully saved speech config")
                            } catch (t: Throwable) {
                                println("Failed to save speech config: $t")
                                throw t
                            }
                        }
                    }
                    onSaved?.invoke()
                    onDismiss()
                }
            }) {
                Text(stringResource(Res.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
        }
    )
}
