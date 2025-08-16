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

    when (step) {
        0 -> {
            // Intro
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Welcome", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("A Kotlin Multiplatform AAC app for Android, iOS, and beyond.", color = MaterialTheme.colorScheme.onBackground)
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = { step = 1 }) { Text("Next") }
                }
            }
        }
        1 -> {
            // Full-screen Azure settings
            AzureSettingsFullScreen(onNext = { step = 2 }, onCancel = { step = 0 })
        }
        2 -> {
            // Full-screen voice selector
            VoiceSelectionFullScreen(onNext = { onContinue() }, onCancel = { step = 1 })
        }
    }
}
