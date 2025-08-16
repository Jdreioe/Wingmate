package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
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
import org.slf4j.LoggerFactory

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
    val log = LoggerFactory.getLogger("VoiceSelectionDialog")

    LaunchedEffect(Unit) {
        loading = true
        try {
            // try to refresh from Azure (may return empty if not configured)
            val fromCloud = withContext(Dispatchers.Default) { useCase.refreshFromAzure() }
            val local = withContext(Dispatchers.Default) { useCase.list() }
            // merge local + cloud (cloud entries preferred)
            voices = (fromCloud + local).distinctBy { it.name }
            selected = useCase.selected()
        } catch (t: Throwable) {
            error = t.message
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Voice") },
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
                                            log.info("User selected voice: {} (primary={}, selected={})", v.name, v.primaryLanguage, v.selectedLanguage)
                                            useCase.select(v)
                                            // also persist UI primary language when selecting a voice
                                            val primary = v.selectedLanguage.ifBlank { v.selectedLanguage ?: "" }
                                            if (primary.isNotBlank() && settingsUseCase != null) {
                                                try {
                                                    log.info("Persisting primaryLanguage='{}' to Settings", primary)
                                                    val current = settingsUseCase.get()
                                                    val updated = current.copy(primaryLanguage = primary)
                                                    settingsUseCase.update(updated)
                                                    log.info("Persisted primaryLanguage='{}'", primary)
                                                } catch (t: Throwable) {
                                                    log.error("Failed to persist primaryLanguage", t)
                                                }
                                            }
                                        } catch (t: Throwable) {
                                            log.error("Failed to select voice {}", v.name, t)
                                        }
                                        onDismiss()
                                    }
                                }) {
                                Column(Modifier.weight(1f)) {
                                    Text(text = v.displayName ?: v.name ?: "Unknown")
                                    Text(text = v.primaryLanguage ?: "", modifier = Modifier.padding(top = 2.dp))
                                }
                                Button(onClick = {
                                    // open settings for this voice
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

    // nested voice settings dialog
    val scope2 = rememberCoroutineScope()
    if (showVoiceSettings && editingVoice != null) {
        VoiceSettingsDialog(show = true, voice = editingVoice!!, onDismiss = { showVoiceSettings = false }, onSave = { updated ->
            // persist updated voice selection
            scope2.launch {
                try {
                    log.info("Saving updated voice {} (selectedLang={}, primary={})", updated.name, updated.selectedLanguage, updated.primaryLanguage)
                    useCase.select(updated)
                    // also persist primary language from updated voice if available
                    try {
                        val primary = updated.selectedLanguage.ifBlank { updated.primaryLanguage ?: "" }
                        if (primary.isNotBlank() && settingsUseCase != null) {
                            log.info("Persisting primaryLanguage='{}' from voice settings", primary)
                            val current = settingsUseCase.get()
                            val updatedSettings = current.copy(primaryLanguage = primary)
                            settingsUseCase.update(updatedSettings)
                            log.info("Persisted primaryLanguage='{}' from voice settings", primary)
                        }
                    } catch (t: Throwable) {
                        log.error("Failed to persist primary language from voice settings", t)
                    }
                } catch (t: Throwable) {
                    log.error("Failed to save updated voice", t)
                }
                showVoiceSettings = false
                // refresh list/selection
                try {
                    voices = (useCase.refreshFromAzure() + useCase.list()).distinctBy { it.name }
                    selected = useCase.selected()
                } catch (_: Throwable) {}
            }
        })
    }
}
