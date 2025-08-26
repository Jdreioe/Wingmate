package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.application.SettingsUseCase
import org.koin.core.context.GlobalContext
import kotlinx.coroutines.launch

@Composable
fun VoiceSettingsDialog(
    show: Boolean, 
    voice: Voice, 
    onDismiss: () -> Unit, 
    onSave: ((Voice) -> Unit)? = null,
    onOpenWelcomeFlow: (() -> Unit)? = null
) {
    if (!show) return

    var selectedLanguage by remember { mutableStateOf(voice.selectedLanguage.ifEmpty { voice.primaryLanguage ?: "en-US" }) }
    var pitch by remember { mutableStateOf(voice.pitch ?: 1.0) }
    var rate by remember { mutableStateOf(voice.rate ?: 1.0) }
    var showEngineComparison by remember { mutableStateOf(false) }
    val speechService = remember { GlobalContext.get().get<SpeechService>() }
    val settingsUseCase = remember { GlobalContext.getOrNull()?.let { runCatching { it.get<SettingsUseCase>() }.getOrNull() } }
    val scope = rememberCoroutineScope()
    
    // Get current TTS engine setting
    var useSystemTts by remember { mutableStateOf(false) }
    LaunchedEffect(settingsUseCase) {
        if (settingsUseCase != null) {
            val settings = runCatching { settingsUseCase.get() }.getOrNull()
            useSystemTts = settings?.useSystemTts ?: false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Voice Settings - ${voice.displayName ?: voice.name}") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Current Engine Info
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Current Engine: ${if (useSystemTts) "System TTS" else "Azure TTS"}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            if (useSystemTts) "Using device's built-in text-to-speech" else "Using Microsoft Azure Cognitive Services",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Voice Settings
                Text("Select language: $selectedLanguage")
                Spacer(modifier = Modifier.height(8.dp))
                Text("Pitch: ${String.format("%.2f", pitch)}")
                Slider(value = pitch.toFloat(), onValueChange = { pitch = it.toDouble() }, valueRange = 0.5f..2.0f)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Rate: ${String.format("%.2f", rate)}")
                Slider(value = rate.toFloat(), onValueChange = { rate = it.toDouble() }, valueRange = 0.5f..2.0f)
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            // test voice
                            scope.launch { 
                                try { 
                                    speechService.speak("This is a test of the voice settings.", voice.copy(pitch = pitch, rate = rate)) 
                                } catch (_: Throwable) {} 
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { 
                        Text("Test Voice") 
                    }
                    
                    OutlinedButton(
                        onClick = { showEngineComparison = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.SwapHoriz,
                            contentDescription = "Change Engine",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Change Engine")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            TextButton(onClick = {
                val updated = voice.copy(selectedLanguage = selectedLanguage, pitch = pitch, rate = rate, pitchForSSML = null, rateForSSML = null)
                onSave?.invoke(updated)
                onDismiss()
            }) { Text("Save") }
        }
    )
    
    // Engine Comparison Dialog
    if (showEngineComparison) {
        AlertDialog(
            onDismissRequest = { showEngineComparison = false },
            title = { 
                Text(
                    "TTS Engine Comparison",
                    style = MaterialTheme.typography.headlineSmall
                ) 
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    // Azure TTS Section
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (!useSystemTts) 
                                MaterialTheme.colorScheme.primaryContainer 
                            else 
                                MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 8.dp)
                            ) {
                                Text(
                                    "Azure TTS",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (!useSystemTts) {
                                    Spacer(Modifier.width(8.dp))
                                    AssistChip(
                                        onClick = { },
                                        label = { Text("Current") }
                                    )
                                }
                            }
                            
                            Text(
                                "✅ Pros:",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text("• High-quality neural voices")
                            Text("• 400+ voices in 140+ languages")
                            Text("• Fine-tuned pronunciation")
                            Text("• SSML support for advanced control")
                            Text("• Consistent quality across devices")
                            
                            Spacer(Modifier.height(8.dp))
                            
                            Text(
                                "❌ Cons:",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text("• Requires internet connection")
                            Text("• Uses cloud service (privacy consideration)")
                            Text("• May have slight delay")
                        }
                    }
                    
                    Spacer(Modifier.height(12.dp))
                    
                    // System TTS Section
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (useSystemTts) 
                                MaterialTheme.colorScheme.primaryContainer 
                            else 
                                MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 8.dp)
                            ) {
                                Text(
                                    "System TTS",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (useSystemTts) {
                                    Spacer(Modifier.width(8.dp))
                                    AssistChip(
                                        onClick = { },
                                        label = { Text("Current") }
                                    )
                                }
                            }
                            
                            Text(
                                "✅ Pros:",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text("• Works offline")
                            Text("• Respects system accessibility settings")
                            Text("• No internet required")
                            Text("• Fast response time")
                            Text("• Complete privacy")
                            
                            Spacer(Modifier.height(8.dp))
                            
                            Text(
                                "❌ Cons:",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text("• Limited voice selection")
                            Text("• Quality depends on device")
                            Text("• Fewer language options")
                            Text("• Less natural sounding")
                        }
                    }
                    
                    Spacer(Modifier.height(16.dp))
                    
                    Text(
                        "💡 Recommendation:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Choose Azure TTS for best quality and language support, or System TTS for offline use and privacy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showEngineComparison = false
                        onOpenWelcomeFlow?.invoke()
                        onDismiss()
                    }
                ) {
                    Text("Change Engine")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showEngineComparison = false }
                ) {
                    Text("Keep Current")
                }
            }
        )
    }
}
