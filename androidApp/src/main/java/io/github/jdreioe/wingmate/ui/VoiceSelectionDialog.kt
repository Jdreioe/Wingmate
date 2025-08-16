package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.domain.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

@Composable
fun VoiceSelectionDialog(show: Boolean, onDismiss: () -> Unit) {
    if (!show) return

    val koin = GlobalContext.get()
    val useCase = remember { koin.get<VoiceUseCase>() }
    val settingsUseCase = remember { runCatching { koin.get<SettingsUseCase>() }.getOrNull() }
    var loading by remember { mutableStateOf(true) }
    var voices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Voice?>(null) }
    var showVoiceSettings by remember { mutableStateOf(false) }
    var editingVoice by remember { mutableStateOf<Voice?>(null) }
    val scope = rememberCoroutineScope()


    LaunchedEffect(Unit) {
        loading = true
        try {
            val fromCloud = withContext(Dispatchers.Default) { useCase.refreshFromAzure() }
            val local = withContext(Dispatchers.Default) { useCase.list() }
            voices = (fromCloud + local).distinctBy { it.name }
            selected = useCase.selected()
        } catch (t: Throwable) {
            error = t.message
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
    title = { Text("Voice") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (loading) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                } else if (error != null) {
                    Text("Error: $error")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(voices) { v ->
                            Row(modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                                .clickable {
                                    scope.launch {
                                        try {
                                            useCase.select(v)
                                            val primary = v.selectedLanguage.ifBlank { v.selectedLanguage ?: "" }
                                            if (primary.isNotBlank() && settingsUseCase != null) {
                                                try {
                                                    val current = settingsUseCase.get()
                                                    val updated = current.copy(primaryLanguage = primary)
                                                    settingsUseCase.update(updated)
                                                } catch (_: Throwable) {}
                                            }
                                        } catch (_: Throwable) {}
                                        onDismiss()
                                    }
                                }) {
                                Column(Modifier.weight(1f)) {
                                    Text(text = v.displayName ?: v.name ?: "Unknown")
                                    Text(text = v.primaryLanguage ?: "", modifier = Modifier.padding(top = 2.dp))
                                }
                                Button(onClick = {
                                    editingVoice = v
                                    showVoiceSettings = true
                                }) {
                                    Text("Settings")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )

    if (showVoiceSettings && editingVoice != null) {
        VoiceSettingsDialog(show = true, voice = editingVoice!!, onDismiss = { showVoiceSettings = false }, onSave = { updated ->
            scope.launch {
                try {
                    useCase.select(updated)
                    val primary = updated.selectedLanguage.ifBlank { updated.primaryLanguage ?: "" }
                    if (primary.isNotBlank() && settingsUseCase != null) {
                        val current = settingsUseCase.get()
                        val updatedSettings = current.copy(primaryLanguage = primary)
                        settingsUseCase.update(updatedSettings)
                    }
                } catch (_: Throwable) {}
                showVoiceSettings = false
            }
        })
    }
}
