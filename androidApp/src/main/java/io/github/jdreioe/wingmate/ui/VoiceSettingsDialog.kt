package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.SpeechService
import org.koin.core.context.GlobalContext
import kotlinx.coroutines.launch

@Composable
fun VoiceSettingsDialog(show: Boolean, voice: Voice, onDismiss: () -> Unit, onSave: ((Voice) -> Unit)? = null) {
    if (!show) return

    var selectedLanguage by remember { mutableStateOf(voice.selectedLanguage.ifEmpty { voice.primaryLanguage ?: "en-US" }) }
    var pitch by remember { mutableStateOf(voice.pitch ?: 1.0) }
    var rate by remember { mutableStateOf(voice.rate ?: 1.0) }
    val speechService = remember { GlobalContext.get().get<SpeechService>() }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Voice Settings - ${voice.displayName ?: voice.name}") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("Select language: $selectedLanguage")
                Spacer(modifier = Modifier.height(8.dp))
                Text("Pitch: ${String.format("%.2f", pitch)}")
                Slider(value = pitch.toFloat(), onValueChange = { pitch = it.toDouble() }, valueRange = 0.5f..2.0f)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Rate: ${String.format("%.2f", rate)}")
                Slider(value = rate.toFloat(), onValueChange = { rate = it.toDouble() }, valueRange = 0.5f..2.0f)
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    // test voice
                    scope.launch { try { speechService.speak("This is a test of the voice settings.", voice.copy(pitch = pitch, rate = rate)) } catch (_: Throwable) {} }
                }) { Text("Test Voice") }
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
}
