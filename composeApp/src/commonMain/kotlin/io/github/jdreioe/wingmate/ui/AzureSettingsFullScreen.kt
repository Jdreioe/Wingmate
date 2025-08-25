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
import org.koin.core.context.GlobalContext

@Composable
fun AzureSettingsFullScreen(onNext: () -> Unit, onCancel: () -> Unit) {
    val configRepo = remember {
        GlobalContext.getOrNull()?.let { koin -> runCatching { koin.get<ConfigRepository>() }.getOrNull() }
    }
    val settingsUseCase = remember {
        GlobalContext.getOrNull()?.let { koin -> runCatching { koin.get<SettingsUseCase>() }.getOrNull() }
    }
    
    var endpoint by remember { mutableStateOf("") }
    var subscriptionKey by remember { mutableStateOf("") }
    var useSystemTts by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(configRepo, settingsUseCase) {
        if (configRepo != null) {
            val cfg = withContext(Dispatchers.Default) { configRepo.getSpeechConfig() }
            println("Loaded config: $cfg")
            cfg?.let { endpoint = it.endpoint; subscriptionKey = it.subscriptionKey }
        }
        
        if (settingsUseCase != null) {
            val settings = withContext(Dispatchers.Default) { 
                runCatching { settingsUseCase.get() }.getOrNull() ?: Settings()
            }
            useSystemTts = settings.useSystemTts
        }
        
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Speech Settings", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(12.dp))
        
        if (loading) {
            CircularProgressIndicator()
        } else {
            // TTS Toggle
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Text-to-Speech Engine",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
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
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (useSystemTts) 
                            "Using your device's built-in text-to-speech engine. Voice selection will be limited to system voices."
                        else 
                            "Using Azure Cognitive Services for high-quality neural voices. Requires internet connection and API configuration.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // Azure Configuration (only show when Azure TTS is selected)
            if (!useSystemTts) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Azure Configuration", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                
                if (configRepo == null) {
                    Text("Config repository not available")
                } else {
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        label = { Text("Region / Endpoint") },
                        placeholder = { Text("e.g., eastus") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = subscriptionKey,
                        onValueChange = { subscriptionKey = it },
                        label = { Text("Subscription Key") },
                        placeholder = { Text("Your Azure Speech service key") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                scope.launch {
                    // Save TTS preference
                    if (settingsUseCase != null) {
                        withContext(Dispatchers.Default) {
                            val current = runCatching { settingsUseCase.get() }.getOrNull() ?: Settings()
                            val updated = current.copy(useSystemTts = useSystemTts)
                            settingsUseCase.update(updated)
                        }
                    }
                    
                    // Save Azure config only if Azure TTS is selected and fields are filled
                    if (!useSystemTts && configRepo != null && endpoint.isNotBlank() && subscriptionKey.isNotBlank()) {
                        withContext(Dispatchers.Default) {
                            configRepo.saveSpeechConfig(SpeechServiceConfig(endpoint = endpoint, subscriptionKey = subscriptionKey))
                        }
                    }
                    onNext()
                }
            }) { Text("Next") }
        }
    }
}
