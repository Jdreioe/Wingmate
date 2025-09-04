package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun WelcomeScreen(onContinue: () -> Unit) {
    var step by remember { mutableStateOf(0) }
    var showUiSettings by remember { mutableStateOf(false) }

    when (step) {
    0 -> {
            // Intro
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Welcome to Wingmate", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(12.dp))
                Text("A Kotlin Multiplatform AAC app for Android, iOS, and beyond.")
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = { showUiSettings = true }) { Text("UI Settings") }
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = { step = 1 }) { Text("Next") }
            }
        }
        1 -> {
            // Voice engine selector screen
            VoiceEngineSelectorScreen(
                onNext = { step = 3 }, // Skip to voice selection if System TTS
                onCancel = { step = 0 },
                onAzureSelected = { step = 2 } // Go to Azure config if Azure selected
            )
        }
        2 -> {
            // Azure configuration screen
            AzureConfigScreen(
                onNext = { step = 3 }, // Go to voice selection after Azure config
                onBack = { step = 1 } // Back to TTS engine selection
            )
        }
        3 -> {
            // Full-screen voice selector
            VoiceSelectionFullScreen(
                onNext = { step = 4 }, // Go to test voice screen after voice selection
                onCancel = { step = 1 }
            )
        }
        4 -> {
            // Test voice screen
            TestVoiceScreen(
                onNext = { onContinue() }, // Complete setup after testing voice
                onBack = { step = 3 } // Back to voice selection
            )
        }
    }

    if (showUiSettings) {
        UiSettingsDialog(onDismissRequest = { showUiSettings = false })
    }
}
