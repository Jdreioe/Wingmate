package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.application.SettingsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

@Composable
fun VoiceEngineSelectorScreen(
    onNext: () -> Unit, 
    onCancel: () -> Unit,
    onAzureSelected: () -> Unit = {}
) {
    val settingsUseCase = remember {
        GlobalContext.getOrNull()?.let { koin -> runCatching { koin.get<SettingsUseCase>() }.getOrNull() }
    }
    
    var useSystemTts by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(settingsUseCase) {        
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
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text("Choose Voice Engine", style = MaterialTheme.typography.headlineSmall)
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
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            ) {
                Text("Cancel")
            }
            
            Button(
                onClick = {
                    scope.launch {
                        // Save the selected TTS engine setting
                        settingsUseCase?.let { useCase ->
                            val currentSettings: Settings = withContext(Dispatchers.Default) {
                                runCatching { useCase.get() }.getOrNull() ?: Settings()
                            }
                            runCatching {
                                useCase.update(currentSettings.copy(useSystemTts = useSystemTts))
                            }
                        }
                        
                        // Navigate to appropriate next screen based on selection
                        if (useSystemTts) {
                            onNext() // Go directly to voice selection
                        } else {
                            onAzureSelected() // Go to Azure configuration
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Next")
            }
        }
    }
}
