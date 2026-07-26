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
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.application.SettingsUseCase
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.jetbrains.compose.resources.stringResource
import wingmatekmp.composeapp.generated.resources.*

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
    val speechService = koinInject<SpeechService>()
    val settingsUseCase = koinInject<SettingsUseCase>()
    val scope = rememberCoroutineScope()
    val testPhrase = stringResource(Res.string.voice_settings_test_phrase)
    
    // Get current TTS engine setting
    var ttsEngine by remember { mutableStateOf(TtsEngine.SYSTEM) }
    LaunchedEffect(settingsUseCase) {
        val settings = runCatching { settingsUseCase.get() }.getOrNull()
        ttsEngine = settings?.ttsEngine ?: TtsEngine.SYSTEM
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.voice_settings_title, voice.displayName ?: voice.name ?: stringResource(Res.string.common_unknown))) },
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
                            stringResource(
                                Res.string.voice_settings_current_engine,
                                stringResource(if (ttsEngine == TtsEngine.SYSTEM) Res.string.ui_settings_system_tts else Res.string.ui_settings_azure_tts)
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            stringResource(if (ttsEngine == TtsEngine.SYSTEM) Res.string.voice_settings_system_description else Res.string.voice_settings_azure_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Voice Settings
                Text(stringResource(Res.string.voice_settings_language, selectedLanguage))
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(Res.string.voice_settings_pitch, String.format("%.2f", pitch)))
                Slider(value = pitch.toFloat(), onValueChange = { pitch = it.toDouble() }, valueRange = 0.5f..2.0f)
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(Res.string.voice_settings_rate, String.format("%.2f", rate)))
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
                                    speechService.speak(testPhrase, voice.copy(pitch = pitch, rate = rate))
                                } catch (_: Throwable) {} 
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { 
                        Text(stringResource(Res.string.test_voice_test_button))
                    }
                    
                    OutlinedButton(
                        onClick = { showEngineComparison = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.SwapHoriz,
                            contentDescription = stringResource(Res.string.voice_settings_change_engine),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(Res.string.voice_settings_change_engine))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_close)) }
        },
        dismissButton = {
            TextButton(onClick = {
                val updated = voice.copy(selectedLanguage = selectedLanguage, pitch = pitch, rate = rate, pitchForSSML = null, rateForSSML = null)
                onSave?.invoke(updated)
                onDismiss()
            }) { Text(stringResource(Res.string.common_save)) }
        }
    )
    
    // Engine Comparison Dialog
    if (showEngineComparison) {
        AlertDialog(
            onDismissRequest = { showEngineComparison = false },
            title = { 
                Text(
                    stringResource(Res.string.voice_settings_comparison),
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
                            containerColor = if (ttsEngine != TtsEngine.SYSTEM) 
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
                                    stringResource(Res.string.ui_settings_azure_tts),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (ttsEngine != TtsEngine.SYSTEM) {
                                    Spacer(Modifier.width(8.dp))
                                    AssistChip(
                                        onClick = { },
                                        label = { Text(stringResource(Res.string.common_current)) }
                                    )
                                }
                            }
                            
                            Text(
                                stringResource(Res.string.voice_engine_pros),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(stringResource(Res.string.voice_settings_azure_comparison_pros))
                            
                            Spacer(Modifier.height(8.dp))
                            
                            Text(
                                stringResource(Res.string.voice_engine_cons),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(stringResource(Res.string.voice_settings_azure_comparison_cons))
                        }
                    }
                    
                    Spacer(Modifier.height(12.dp))
                    
                    // System TTS Section
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (ttsEngine == TtsEngine.SYSTEM) 
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
                                    stringResource(Res.string.ui_settings_system_tts),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (ttsEngine == TtsEngine.SYSTEM) {
                                    Spacer(Modifier.width(8.dp))
                                    AssistChip(
                                        onClick = { },
                                        label = { Text(stringResource(Res.string.common_current)) }
                                    )
                                }
                            }
                            
                            Text(
                                stringResource(Res.string.voice_engine_pros),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(stringResource(Res.string.voice_settings_system_comparison_pros))
                            
                            Spacer(Modifier.height(8.dp))
                            
                            Text(
                                stringResource(Res.string.voice_engine_cons),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(stringResource(Res.string.voice_settings_system_comparison_cons))
                        }
                    }
                    
                    Spacer(Modifier.height(16.dp))
                    
                    Text(
                        stringResource(Res.string.voice_settings_recommendation_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        stringResource(Res.string.voice_settings_recommendation),
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
                    Text(stringResource(Res.string.voice_settings_change_engine))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showEngineComparison = false }
                ) {
                    Text(stringResource(Res.string.voice_settings_keep_current))
                }
            }
        )
    }
}
