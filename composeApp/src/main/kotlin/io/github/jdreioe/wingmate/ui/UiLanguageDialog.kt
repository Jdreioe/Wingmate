package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.Voice
import org.koin.core.context.GlobalContext
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

@Composable
fun UiLanguageDialog(show: Boolean, onDismiss: () -> Unit) {
    if (!show) return
    val voiceUseCase = remember { GlobalContext.get().get<VoiceUseCase>() }
    val settingsUseCase = remember { GlobalContext.get().get<SettingsUseCase>() }
    val scope = rememberCoroutineScope()

    var available by remember { mutableStateOf<List<String>>(emptyList()) }
    var filter by remember { mutableStateOf("") }
    var primary by remember { mutableStateOf("en-US") }
    var secondary by remember { mutableStateOf("en-US") }
    val log = LoggerFactory.getLogger("UiLanguageDialog")

    LaunchedEffect(Unit) {
        // load current settings and selected voice
        val settings = runCatching { settingsUseCase.get() }.getOrNull() ?: Settings()
        primary = settings.primaryLanguage
        secondary = settings.secondaryLanguage
        val sel = runCatching { voiceUseCase.selected() }.getOrNull()
        available = sel?.supportedLanguages ?: listOf("en-US")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Voice Language") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("Filter languages")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = filter, onValueChange = { filter = it }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))

                Text("Primary language")
                Spacer(Modifier.height(8.dp))
                LanguageMenu(available = available, filter = filter, selected = primary, onSelect = { sel ->
                    primary = sel
                    // persist immediately
                    scope.launch {
                        try {
                            val current = runCatching { settingsUseCase.get() }.getOrNull() ?: Settings()
                            val updated = current.copy(primaryLanguage = sel)
                            settingsUseCase.update(updated)
                            log.info("Saved primaryLanguage='{}'", sel)
                            // Also update selected voice's selectedLanguage to match immediately
                            try {
                                val vuse = runCatching { voiceUseCase.selected() }.getOrNull()
                                if (vuse != null) {
                                    val updatedVoice = vuse.copy(selectedLanguage = sel)
                                    runCatching { voiceUseCase.select(updatedVoice) }.onSuccess {
                                        log.info("Updated selected voice '{}' selectedLanguage='{}'", vuse.name, sel)
                                    }.onFailure { t ->
                                        log.error("Failed to persist selected voice language", t)
                                    }
                                }
                            } catch (t: Throwable) {
                                log.error("Error while updating selected voice language", t)
                            }
                        } catch (t: Throwable) {
                            log.error("Failed saving primary language", t)
                        }
                    }
                })
                Spacer(Modifier.height(12.dp))

                Text("Secondary language")
                Spacer(Modifier.height(8.dp))
                LanguageMenu(available = available, filter = filter, selected = secondary, onSelect = { sel ->
                    secondary = sel
                    // persist immediately
                    scope.launch {
                        try {
                            val current = runCatching { settingsUseCase.get() }.getOrNull() ?: Settings()
                            val updated = current.copy(secondaryLanguage = sel)
                            settingsUseCase.update(updated)
                            log.info("Saved secondaryLanguage='{}'", sel)
                        } catch (t: Throwable) {
                            log.error("Failed saving secondary language", t)
                        }
                    }
                })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    try {
                        // fetch existing settings and copy to preserve unrelated fields
                        val current = runCatching { settingsUseCase.get() }.getOrNull() ?: Settings()
                        val updated = current.copy(primaryLanguage = primary, secondaryLanguage = secondary)
                        settingsUseCase.update(updated)

                        // Also update the selected voice's selectedLanguage to match the chosen primary language
                        try {
                            val vuse = runCatching { voiceUseCase.selected() }.getOrNull()
                            if (vuse != null) {
                                val updatedVoice = vuse.copy(selectedLanguage = primary)
                                runCatching { voiceUseCase.select(updatedVoice) }.onSuccess {
                                    org.slf4j.LoggerFactory.getLogger("UiLanguageDialog").info("Updated selected voice '{}' selectedLanguage='{}'", vuse.name, primary)
                                }.onFailure { t ->
                                    org.slf4j.LoggerFactory.getLogger("UiLanguageDialog").error("Failed to update selected voice language", t)
                                }
                            }
                        } catch (t: Throwable) {
                            org.slf4j.LoggerFactory.getLogger("UiLanguageDialog").error("Error while updating selected voice language", t)
                        }
                    } catch (_: Throwable) {
                        // ignore
                    }
                    onDismiss()
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun LanguageMenu(available: List<String>, filter: String, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = available.filter { it.contains(filter, ignoreCase = true) }

    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.fillMaxWidth()) {
            options.forEach { lang ->
                DropdownMenuItem(text = { Text(lang) }, onClick = { onSelect(lang); expanded = false })
            }
        }
    }
}
