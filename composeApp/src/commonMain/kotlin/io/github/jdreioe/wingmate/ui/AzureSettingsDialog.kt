package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.SettingsStateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@Composable
fun AzureSettingsDialog(show: Boolean, onDismiss: () -> Unit, onSaved: (() -> Unit)? = null) {
    if (!show) return

    // get the ConfigRepository from Koin in a safe way
    val configRepo = remember {
        org.koin.core.context.GlobalContext.getOrNull()?.let { koin ->
            runCatching { koin.get<ConfigRepository>() }.getOrNull()
        }
    }
    val settingsUseCase = remember {
        org.koin.core.context.GlobalContext.getOrNull()?.let { koin ->
            runCatching { koin.get<SettingsUseCase>() }.getOrNull()
        }
    }
    val settingsStateManager = remember {
        org.koin.core.context.GlobalContext.getOrNull()?.let { koin ->
            runCatching { koin.get<SettingsStateManager>() }.getOrNull()
        }
    }

    // Log which ConfigRepository implementation we got (helps diagnose persistence)
    LaunchedEffect(configRepo) {
        println("ConfigRepository implementation: ${configRepo?.javaClass?.name ?: "<none>"}")
    }

    if (configRepo == null) {
        AlertDialog(
            onDismissRequest = onDismiss, 
            title = { Text("Speech Settings") }, 
            text = { Text("Config repository not available") }, 
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        )
        return
    }

    var endpoint by remember { mutableStateOf("") }
    var subscriptionKey by remember { mutableStateOf("") }
    var useSystemTts by remember { mutableStateOf(false) }
    var virtualMic by remember { mutableStateOf(false) }
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
            useSystemTts = settings.useSystemTts
            virtualMic = settings.virtualMicEnabled
            fontSizeScale = settings.fontSizeScale
            playbackIconScale = settings.playbackIconScale
            categoryChipScale = settings.categoryChipScale
            buttonScale = settings.buttonScale
            inputFieldScale = settings.inputFieldScale
            forceDarkTheme = settings.forceDarkTheme
            useCustomColors = settings.useCustomColors
            primaryColor = settings.primaryColor ?: "#7C4DFF"
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
        title = { Text("Speech Settings") },
        text = {
            if (loading) {
                CircularProgressIndicator()
            } else {
                Column {
                    // TTS Toggle
                    Text("Text-to-Speech Engine", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = !useSystemTts,
                            onClick = { useSystemTts = false },
                            label = { Text("Azure TTS") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = useSystemTts,
                            onClick = { useSystemTts = true },
                            label = { Text("System TTS") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    
                    // Azure Configuration (only show when Azure TTS is selected)
                    if (!useSystemTts) {
                        Spacer(modifier = Modifier.height(16.dp))
                        val showKeyboard = rememberShowKeyboardOnFocus()
                        OutlinedTextField(
                            value = endpoint,
                            onValueChange = { endpoint = it },
                            label = { Text("Region / Endpoint") },
                            placeholder = { Text("e.g., eastus") },
                            modifier = Modifier.then(showKeyboard)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = subscriptionKey,
                            onValueChange = { subscriptionKey = it },
                            label = { Text("Subscription Key") },
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
                                Text("Use virtual microphone for calls")
                                Text(
                                    "Routes TTS audio to a virtual device you can pick as mic in Zoom/Meet.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    
                    // UI Scaling Settings
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        "UI Scaling",
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
                            Text("Font Size", style = MaterialTheme.typography.bodyMedium)
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
                            Text("Playback Icons", style = MaterialTheme.typography.bodyMedium)
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
                            Text("Category Chips", style = MaterialTheme.typography.bodyMedium)
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
                            Text("Buttons", style = MaterialTheme.typography.bodyMedium)
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
                            Text("Input Fields", style = MaterialTheme.typography.bodyMedium)
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
                                useSystemTts = useSystemTts, 
                                virtualMicEnabled = virtualMic,
                                fontSizeScale = fontSizeScale,
                                playbackIconScale = playbackIconScale,
                                categoryChipScale = categoryChipScale,
                                buttonScale = buttonScale,
                                inputFieldScale = inputFieldScale
                            )
                            settingsUseCase.update(updated)
                        }
                        
                        // Save Azure config only if Azure TTS is selected
                        if (!useSystemTts && endpoint.isNotBlank() && subscriptionKey.isNotBlank()) {
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
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
