package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIos
import androidx.compose.material.icons.filled.ArrowForwardIos
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AssistChip
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
import io.github.jdreioe.wingmate.ui.VoiceSettingsDialog

@Composable
fun VoiceSelectionFullScreen(onNext: () -> Unit, onCancel: () -> Unit) {
    val koin = GlobalContext.get()
    val useCase = remember { koin.get<VoiceUseCase>() }
    val settingsUseCase = remember { runCatching { koin.get<SettingsUseCase>() }.getOrNull() }
    var loading by remember { mutableStateOf(true) }
    var voices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Voice?>(null) }
    var selectedLanguageFilter by remember { mutableStateOf<String?>(null) }
    var supportedLanguages by remember { mutableStateOf<List<String>>(emptyList()) }
    var showVoiceSettings by remember { mutableStateOf(false) }
    var editingVoice by remember { mutableStateOf<Voice?>(null) }
    val scope = rememberCoroutineScope()
    val log = LoggerFactory.getLogger("VoiceSelectionFullScreen")

    LaunchedEffect(Unit) {
        loading = true
        try {
            val fromCloud = withContext(Dispatchers.Default) { useCase.refreshFromAzure() }
            val local = withContext(Dispatchers.Default) { useCase.list() }
            voices = (fromCloud + local).distinctBy { it.name }
            // aggregate supported languages from Azure catalog (primary + supportedLanguages)
            val langs = voices.flatMap { listOfNotNull(it.primaryLanguage) + (it.supportedLanguages ?: emptyList()) }.distinct().sorted()
            supportedLanguages = langs
            selected = useCase.selected()
        } catch (t: Throwable) {
            error = t.message
        }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Select Voice", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(12.dp))
        if (loading) {
            CircularProgressIndicator()
        } else if (error != null) {
            Text("Error: $error")
        } else {
            // Show supported languages selector before the voice list
            if (supportedLanguages.isNotEmpty()) {
                val scrollState = rememberScrollState()
                Row(modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = {
                        scope.launch {
                            val target = (scrollState.value - 200).coerceAtLeast(0)
                            scrollState.animateScrollTo(target)
                        }
                    }) { Icon(imageVector = Icons.Filled.ArrowBackIos, contentDescription = "Scroll left") }

                    Row(modifier = Modifier
                        .weight(1f)
                        .height(64.dp)
                        .horizontalScroll(scrollState),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        supportedLanguages.forEach { lang ->
                            val isSelected = lang == selectedLanguageFilter
                            AssistChip(onClick = {
                                val newSel = if (isSelected) null else lang
                                selectedLanguageFilter = newSel
                                // persist selected language into selected voice JSON (or create a minimal selected voice)
                                scope.launch {
                                    try {
                                        val currentSelected = runCatching { useCase.selected() }.getOrNull()
                                        if (newSel != null) {
                                            if (currentSelected != null) {
                                                useCase.select(currentSelected.copy(selectedLanguage = newSel))
                                            } else {
                                                // create a minimal selected voice entry to store the selected language
                                                val placeholder = io.github.jdreioe.wingmate.domain.Voice(name = "", displayName = "", primaryLanguage = newSel, selectedLanguage = newSel)
                                                useCase.select(placeholder)
                                            }
                                        } else {
                                            // clearing selection: if currentSelected exists, clear its selectedLanguage
                                            if (currentSelected != null) {
                                                useCase.select(currentSelected.copy(selectedLanguage = ""))
                                            }
                                        }
                                    } catch (t: Throwable) {
                                        log.error("Failed to persist selected language {}", newSel, t)
                                    }
                                }
                            }, label = { Text(lang) }, enabled = true)
                        }
                    }

                    IconButton(onClick = {
                        scope.launch {
                            val target = (scrollState.value + 200).coerceAtMost(scrollState.maxValue)
                            scrollState.animateScrollTo(target)
                        }
                    }) { Icon(imageVector = Icons.Filled.ArrowForwardIos, contentDescription = "Scroll right") }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                val filtered = selectedLanguageFilter?.let { f -> voices.filter { v -> (v.primaryLanguage == f) || (v.supportedLanguages?.contains(f) == true) } } ?: voices
                items(filtered) { v ->
                    Row(modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .clickable {
                            scope.launch {
                                try {
                                    // preserve any language selected by the chips (selectedLanguageFilter)
                                    val currentSelected = runCatching { useCase.selected() }.getOrNull()
                                    val effectiveLang = selectedLanguageFilter ?: currentSelected?.selectedLanguage ?: v.selectedLanguage.ifBlank { v.primaryLanguage ?: "" }
                                    val toSelect = v.copy(selectedLanguage = effectiveLang)
                                    log.info("User selected voice (tap): {} (effectiveLang={})", v.name, effectiveLang)
                                    useCase.select(toSelect)
                                    // do NOT overwrite Settings.primaryLanguage here; user explicitly chose voice only
                                } catch (t: Throwable) {
                                    log.error("Failed to select voice {}", v.name, t)
                                }
                                selected = useCase.selected()
                                // show phrases
                                onNext()
                            }
                        }) {
                        Column(Modifier.weight(1f)) {
                            Text(text = v.displayName ?: v.name ?: "Unknown")
                            Text(text = v.primaryLanguage ?: "", modifier = Modifier.padding(top = 2.dp))
                        }
                        Button(onClick = {
                            // open voice settings - reuse existing dialog
                            editingVoice = v
                            showVoiceSettings = true

                        }) {
                            Text("Settings")
                        }
                    }
                }
            }
            // nested voice settings dialog
            if (showVoiceSettings && editingVoice != null) {
                VoiceSettingsDialog(show = true, voice = editingVoice!!, onDismiss = { showVoiceSettings = false }, onSave = { updated ->
                    scope.launch {
                        try {
                            log.info("Saving updated voice {} (selectedLang={}, primary={})", updated.name, updated.selectedLanguage, updated.primaryLanguage)
                            useCase.select(updated)
                            // also persist primary language from updated voice if available
                            try {
                                val primary = updated.selectedLanguage.ifBlank { updated.primaryLanguage ?: "" }
                                if (primary.isNotBlank() && settingsUseCase != null) {
                                    val current = settingsUseCase.get()
                                    val updatedSettings = current.copy(primaryLanguage = primary)
                                    settingsUseCase.update(updatedSettings)
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

        Spacer(modifier = Modifier.height(16.dp))

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onNext) { Text("Next") }
        }
    }
}
