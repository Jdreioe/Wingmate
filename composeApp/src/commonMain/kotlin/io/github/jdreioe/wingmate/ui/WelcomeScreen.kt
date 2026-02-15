package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun WelcomeScreen(onContinue: () -> Unit) {
    var step by remember { mutableStateOf(0) }
    var showUiSettings by remember { mutableStateOf(false) }
    
    // Import state
    var isImporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    when (step) {
    0 -> {
            // Intro
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Welcome to Wingmate", 
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "A Kotlin Multiplatform AAC app for Android, iOS, and beyond.",
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(onClick = { showUiSettings = true }) { 
                        Text("UI Settings") 
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = { step = 1 }) { Text("Next") }
                }
            }
        }
        1 -> {
            // Import Board Options
            if (isImporting) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text("Importing board...")
                        }
                    }
                }
            } else {
                ImportOptionsScreen(
                    onImportClassic = { 
                        scope.launch {
                            isImporting = true
                            try {
                                val service = org.koin.core.context.GlobalContext.get().get<io.github.jdreioe.wingmate.infrastructure.BoardImportService>()
                                val result = service.importBoards(isModern = false)
                                if (result) {
                                    // Move to next step if successful
                                    step = 2
                                } else {
                                    // Handle failure or cancellation (stay on screen)
                                    // Ideally show snackbar, but for now just stop spinner
                                }
                            } catch (e: Throwable) {
                                e.printStackTrace()
                            } finally {
                                isImporting = false
                            }
                        }
                    },
                    onImportModern = {
                         scope.launch {
                            isImporting = true
                            try {
                                val service = org.koin.core.context.GlobalContext.get().get<io.github.jdreioe.wingmate.infrastructure.BoardImportService>()
                                val result = service.importBoards(isModern = true)
                                if (result) {
                                    step = 2
                                }
                            } catch (e: Throwable) {
                                e.printStackTrace()
                            } finally {
                                isImporting = false
                            }
                        }
                    },
                    onSkip = { step = 2 }
                )
            }
        }
        2 -> {
            // Voice engine selector screen
            VoiceEngineSelectorScreen(
                onNext = { step = 4 }, // Skip to voice selection if System TTS
                onCancel = { step = 1 }, // Back to Import
                onAzureSelected = { step = 3 } // Go to Azure config if Azure selected
            )
        }
        3 -> {
            // Azure configuration screen
            AzureConfigScreen(
                onNext = { step = 4 }, // Go to voice selection after Azure config
                onBack = { step = 2 } // Back to TTS engine selection
            )
        }
        4 -> {
            // Full-screen voice selector
            VoiceSelectionFullScreen(
                onNext = { step = 5 }, // Go to test voice screen after voice selection
                onCancel = { step = 2 } // Back to Engine selector
            )
        }
        5 -> {
            // Test voice screen
            TestVoiceScreen(
                onNext = { onContinue() }, // Complete setup after testing voice
                onBack = { step = 4 } // Back to voice selection
            )
        }
    }

    if (showUiSettings) {
        UiSettingsDialog(onDismissRequest = { showUiSettings = false })
    }
}
