package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
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
fun AzureSettingsFullScreen(
    onNext: () -> Unit, 
    onCancel: () -> Unit,
    onAzureSelected: () -> Unit = {}
) {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(24.dp)
    ) {
        Text("Speech Settings", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(12.dp))
        
        if (loading) {
            CircularProgressIndicator()
        } else {
            
            // Pros and Cons Comparison
            Spacer(modifier = Modifier.height(16.dp))
            
            // Azure TTS Card
            Card(
                onClick = { 
                    useSystemTts = false
                    scope.launch {
                        settingsUseCase?.let { useCase ->
                            val currentSettings: Settings = withContext(Dispatchers.Default) {
                                runCatching { useCase.get() }.getOrNull() ?: Settings()
                            }
                            runCatching {
                                useCase.update(currentSettings.copy(useSystemTts = false))
                            }
                        }
                        // Navigate to Azure config screen
                        onAzureSelected()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (!useSystemTts) 
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                    else 
                        MaterialTheme.colorScheme.surfaceContainer
                ),
                border = if (!useSystemTts) 
                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                else null
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Text(
                            "🎙️ Azure TTS",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        if (!useSystemTts) {
                            Spacer(Modifier.width(8.dp))
                            AssistChip(
                                onClick = { },
                                label = { Text("Selected", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    
                    Text(
                        "✅ Pros:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text("• 400+ high-quality neural voices", style = MaterialTheme.typography.bodySmall)
                    Text("• 140+ languages and variants", style = MaterialTheme.typography.bodySmall)
                    Text("• Natural pronunciation and intonation", style = MaterialTheme.typography.bodySmall)
                    Text("• Consistent quality across devices", style = MaterialTheme.typography.bodySmall)
                    Text("• Advanced voice controls (SSML)", style = MaterialTheme.typography.bodySmall)
                    
                    Spacer(Modifier.height(8.dp))
                    
                    Text(
                        "❌ Cons:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text("• Requires internet connection", style = MaterialTheme.typography.bodySmall)
                    Text("• API key setup needed", style = MaterialTheme.typography.bodySmall)
                    Text("• Cloud service (privacy consideration)", style = MaterialTheme.typography.bodySmall)
                    Text("• May have slight network delay", style = MaterialTheme.typography.bodySmall)
                }
            }
            
            Spacer(Modifier.height(12.dp))
            
            // System TTS Card  
            Card(
                onClick = { 
                    useSystemTts = true
                    scope.launch {
                        settingsUseCase?.let { useCase ->
                            val currentSettings: Settings = withContext(Dispatchers.Default) {
                                runCatching { useCase.get() }.getOrNull() ?: Settings()
                            }
                            runCatching {
                                useCase.update(currentSettings.copy(useSystemTts = true))
                            }
                        }
                        // Go directly to voice selection since no config needed
                        onNext()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (useSystemTts) 
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                    else 
                        MaterialTheme.colorScheme.surfaceContainer
                ),
                border = if (useSystemTts) 
                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                else null
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Text(
                            "📱 System TTS",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        if (useSystemTts) {
                            Spacer(Modifier.width(8.dp))
                            AssistChip(
                                onClick = { },
                                label = { Text("Selected", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    
                    Text(
                        "✅ Pros:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text("• Works completely offline", style = MaterialTheme.typography.bodySmall)
                    Text("• No setup or API keys required", style = MaterialTheme.typography.bodySmall)
                    Text("• Respects system accessibility settings", style = MaterialTheme.typography.bodySmall)
                    Text("• Fast response time", style = MaterialTheme.typography.bodySmall)
                    Text("• Complete privacy (no cloud)", style = MaterialTheme.typography.bodySmall)
                    
                    Spacer(Modifier.height(8.dp))
                    
                    Text(
                        "❌ Cons:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text("• Limited voice selection", style = MaterialTheme.typography.bodySmall)
                    Text("• Quality varies by device", style = MaterialTheme.typography.bodySmall)
                    Text("• Fewer language options", style = MaterialTheme.typography.bodySmall)
                    Text("• Less natural sounding", style = MaterialTheme.typography.bodySmall)
                }
            }
            
            Spacer(Modifier.height(16.dp))
            
            // Recommendation Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "💡 Our Recommendation:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Choose Azure TTS for best quality and language variety. Choose System TTS for privacy and offline use.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
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
