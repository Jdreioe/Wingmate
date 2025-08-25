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
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        // load existing config
        val cfg = withContext(Dispatchers.Default) { configRepo.getSpeechConfig() }
        println("Loaded config: $cfg")
        cfg?.let {
            endpoint = it.endpoint
            subscriptionKey = it.subscriptionKey
        }
        
        // load TTS preference
        if (settingsUseCase != null) {
            val settings = withContext(Dispatchers.Default) { 
                runCatching { settingsUseCase.get() }.getOrNull() ?: Settings()
            }
            useSystemTts = settings.useSystemTts
        }
        loading = false
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
                        OutlinedTextField(
                            value = endpoint,
                            onValueChange = { endpoint = it },
                            label = { Text("Region / Endpoint") },
                            placeholder = { Text("e.g., eastus") }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = subscriptionKey,
                            onValueChange = { subscriptionKey = it },
                            label = { Text("Subscription Key") }
                        )
                    }
                }
            }
        },
        confirmButton = {
            val scope = rememberCoroutineScope()
            Button(onClick = {
                // save
                scope.launch {
                    withContext(Dispatchers.Default) {
                        // Save TTS preference
                        if (settingsUseCase != null) {
                            val current = runCatching { settingsUseCase.get() }.getOrNull() ?: Settings()
                            val updated = current.copy(useSystemTts = useSystemTts)
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
