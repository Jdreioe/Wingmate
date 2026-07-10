package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.application.VoiceUseCase
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import wingmatekmp.composeapp.generated.resources.Res
import wingmatekmp.composeapp.generated.resources.common_back
import wingmatekmp.composeapp.generated.resources.common_continue
import wingmatekmp.composeapp.generated.resources.test_voice_default_text
import wingmatekmp.composeapp.generated.resources.test_voice_info
import wingmatekmp.composeapp.generated.resources.test_voice_message_label
import wingmatekmp.composeapp.generated.resources.test_voice_play
import wingmatekmp.composeapp.generated.resources.test_voice_selected_voice
import wingmatekmp.composeapp.generated.resources.test_voice_stop
import wingmatekmp.composeapp.generated.resources.test_voice_test_button
import wingmatekmp.composeapp.generated.resources.test_voice_title
import wingmatekmp.composeapp.generated.resources.test_voice_unknown_language
import wingmatekmp.composeapp.generated.resources.test_voice_unknown_voice

@Composable
fun TestVoiceScreen(onNext: () -> Unit, onBack: () -> Unit) {
    val speechService = koinInject<SpeechService>()
    val voiceUseCase = koinInject<VoiceUseCase>()
    val defaultTestText = stringResource(Res.string.test_voice_default_text)
    
    var selectedVoice by remember { mutableStateOf<Voice?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var testText by remember(defaultTestText) { mutableStateOf(defaultTestText) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // Load selected voice
    LaunchedEffect(voiceUseCase) {
        try {
            selectedVoice = withContext(Dispatchers.IO) {
                voiceUseCase.selected()
            }
        } catch (e: Exception) {
            println("Failed to load selected voice: $e")
        } finally {
            loading = false
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(Res.string.test_voice_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        
        if (loading) {
            CircularProgressIndicator()
        } else {
            // Voice info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(Res.string.test_voice_selected_voice),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        selectedVoice?.displayName ?: selectedVoice?.name ?: stringResource(Res.string.test_voice_unknown_voice),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        selectedVoice?.primaryLanguage ?: stringResource(Res.string.test_voice_unknown_language),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Test text input
            val showKeyboard = rememberShowKeyboardOnFocus()
            OutlinedTextField(
                value = testText,
                onValueChange = { testText = it },
                label = { Text(stringResource(Res.string.test_voice_message_label)) },
                modifier = Modifier.fillMaxWidth().then(showKeyboard),
                minLines = 2,
                maxLines = 4
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Play button
            Button(
                onClick = {
                    if (isPlaying) {
                        // Stop current playback
                        scope.launch {
                            try {
                                    speechService.stop()
                            } catch (e: Exception) {
                                println("Failed to stop speech: $e")
                            } finally {
                                isPlaying = false
                            }
                        }
                    } else {
                        // Start playback
                        scope.launch {
                            try {
                                isPlaying = true
                                withContext(Dispatchers.IO) {
                                        speechService.speak(
                                        text = testText,
                                        voice = selectedVoice,
                                        pitch = selectedVoice?.pitch,
                                        rate = selectedVoice?.rate
                                    )
                                }
                            } catch (e: Exception) {
                                println("Failed to speak: $e")
                            } finally {
                                isPlaying = false
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                    enabled = testText.isNotBlank()
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) {
                        stringResource(Res.string.test_voice_stop)
                    } else {
                        stringResource(Res.string.test_voice_play)
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (isPlaying) {
                        stringResource(Res.string.test_voice_stop)
                    } else {
                        stringResource(Res.string.test_voice_test_button)
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Info text
            Text(
                stringResource(Res.string.test_voice_info),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Navigation buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        // Stop any ongoing speech before going back
                        scope.launch {
                            try {
                                    speechService.stop()
                            } catch (e: Exception) {
                                println("Failed to stop speech: $e")
                            }
                            onBack()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(Res.string.common_back))
                }
                
                Button(
                    onClick = {
                        // Stop any ongoing speech before proceeding
                        scope.launch {
                            try {
                                    speechService.stop()
                            } catch (e: Exception) {
                                println("Failed to stop speech: $e")
                            }
                            onNext()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(Res.string.common_continue))
                }
            }
        }
        }
    }
}
