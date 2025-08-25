package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
    var useSystemTts by remember { mutableStateOf(false) }
    var systemVoices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var selectedLanguage by remember { mutableStateOf<String?>(null) }
    var availableLanguages by remember { mutableStateOf<List<String>>(emptyList()) }
    var showLanguageFilter by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Get system voice provider from Koin
    val systemVoiceProvider = remember {
        try {
            val koin = GlobalContext.getOrNull()
            koin?.let { runCatching { it.get<io.github.jdreioe.wingmate.infrastructure.SystemVoiceProvider>() }.getOrNull() }
        } catch (e: Exception) {
            null
        }
    }

    LaunchedEffect(Unit) {
        // Check TTS preference first
        if (settingsUseCase != null) {
            val settings = withContext(Dispatchers.Default) { 
                runCatching { settingsUseCase.get() }.getOrNull() 
            }
            useSystemTts = settings?.useSystemTts ?: false
        }
        
        loading = true
        try {
            if (useSystemTts) {
                // Load system voices
                val allSystemVoices = systemVoiceProvider?.getSystemVoices() ?: listOf(
                    Voice(
                        name = "system-default",
                        displayName = "System Default",
                        primaryLanguage = "en-US",
                        gender = "Unknown"
                    )
                )
                systemVoices = allSystemVoices
                
                // Extract available languages from system voices
                availableLanguages = allSystemVoices
                    .mapNotNull { it.primaryLanguage }
                    .distinct()
                    .sorted()
                
                // Get currently selected voice if any
                selected = try { useCase.selected() } catch (e: Exception) { null }
            } else {
                // Load Azure voices
                val fromCloud = withContext(Dispatchers.Default) { useCase.refreshFromAzure() }
                val local = withContext(Dispatchers.Default) { useCase.list() }
                val allVoices = (fromCloud + local).distinctBy { it.name }
                voices = allVoices
                
                // Extract available languages from Azure voices
                availableLanguages = allVoices
                    .flatMap { voice -> 
                        listOfNotNull(voice.primaryLanguage) + (voice.supportedLanguages ?: emptyList())
                    }
                    .distinct()
                    .sorted()
                
                selected = useCase.selected()
            }
        } catch (t: Throwable) {
            error = t.message
        }
        loading = false
    }

    // Filter voices based on selected language
    val filteredSystemVoices = if (selectedLanguage != null) {
        systemVoices.filter { it.primaryLanguage == selectedLanguage }
    } else {
        systemVoices
    }
    
    val filteredAzureVoices = if (selectedLanguage != null) {
        voices.filter { voice ->
            voice.primaryLanguage == selectedLanguage || 
            voice.supportedLanguages?.contains(selectedLanguage) == true
        }
    } else {
        voices
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Voice") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                // Language filter section
                if (!loading && error == null && availableLanguages.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Filter by Language:",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        
                        Box {
                            OutlinedButton(
                                onClick = { showLanguageFilter = !showLanguageFilter },
                                modifier = Modifier.height(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.FilterList,
                                    contentDescription = "Filter languages",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    selectedLanguage ?: "All Languages",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1
                                )
                            }
                            
                            DropdownMenu(
                                expanded = showLanguageFilter,
                                onDismissRequest = { showLanguageFilter = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("All Languages") },
                                    onClick = {
                                        selectedLanguage = null
                                        showLanguageFilter = false
                                    }
                                )
                                availableLanguages.forEach { language ->
                                    DropdownMenuItem(
                                        text = { Text(language) },
                                        onClick = {
                                            selectedLanguage = language
                                            showLanguageFilter = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
                
                if (loading) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                } else if (error != null) {
                    Text("Error: $error")
                } else if (useSystemTts) {
                    // Show system voices
                    Text(
                        "System TTS Voices" + if (selectedLanguage != null) " (${selectedLanguage})" else "",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    if (filteredSystemVoices.isEmpty() && selectedLanguage != null) {
                        Text(
                            "No system voices found for $selectedLanguage",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                            items(filteredSystemVoices) { v ->
                                Row(modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp)
                                    .clickable {
                                        scope.launch {
                                            try {
                                                println("User selected system voice: ${v.name}")
                                                useCase.select(v)
                                                // Update settings if needed
                                                val primary = v.primaryLanguage ?: ""
                                                if (primary.isNotBlank() && settingsUseCase != null) {
                                                    try {
                                                        val current = settingsUseCase.get()
                                                        val updated = current.copy(primaryLanguage = primary)
                                                        settingsUseCase.update(updated)
                                                    } catch (t: Throwable) {
                                                        println("Failed to persist primaryLanguage: $t")
                                                    }
                                                }
                                            } catch (t: Throwable) {
                                                println("Failed to select system voice ${v.name}: $t")
                                            }
                                            onDismiss()
                                        }
                                    }) {
                                    Column(Modifier.weight(1f)) {
                                        Text(text = v.displayName ?: v.name ?: "Unknown")
                                        Text(text = v.primaryLanguage ?: "", modifier = Modifier.padding(top = 2.dp))
                                        if (selected?.name == v.name) {
                                            Text(
                                                text = "✓ Selected", 
                                                color = MaterialTheme.colorScheme.primary,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Show Azure voices
                    Text(
                        "Azure TTS Voices" + if (selectedLanguage != null) " (${selectedLanguage})" else "",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    if (filteredAzureVoices.isEmpty() && selectedLanguage != null) {
                        Text(
                            "No Azure voices found for $selectedLanguage",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                            items(filteredAzureVoices) { v ->
                                Row(modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp)
                                    .clickable {
                                        scope.launch {
                                            try {
                                                println("User selected voice: ${v.name} (primary=${v.primaryLanguage}, selected=${v.selectedLanguage})")
                                                useCase.select(v)
                                                // also persist UI primary language when selecting a voice
                                                val primary = v.selectedLanguage.ifBlank { v.selectedLanguage ?: "" }
                                                if (primary.isNotBlank() && settingsUseCase != null) {
                                                    try {
                                                        println("Persisting primaryLanguage='$primary' to Settings")
                                                        val current = settingsUseCase.get()
                                                        val updated = current.copy(primaryLanguage = primary)
                                                        settingsUseCase.update(updated)
                                                        println("Persisted primaryLanguage='$primary'")
                                                    } catch (t: Throwable) {
                                                        println("Failed to persist primaryLanguage: $t")
                                                    }
                                                }
                                            } catch (t: Throwable) {
                                                println("Failed to select voice ${v.name}: $t")
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
                    println("Saving updated voice ${updated.name} (selectedLang=${updated.selectedLanguage}, primary=${updated.primaryLanguage})")
                    useCase.select(updated)
                    // also persist primary language from updated voice if available
                    try {
                        val primary = updated.selectedLanguage.ifBlank { updated.primaryLanguage ?: "" }
                        if (primary.isNotBlank() && settingsUseCase != null) {
                            println("Persisting primaryLanguage='$primary' from voice settings")
                            val current = settingsUseCase.get()
                            val updatedSettings = current.copy(primaryLanguage = primary)
                            settingsUseCase.update(updatedSettings)
                            println("Persisted primaryLanguage='$primary' from voice settings")
                        }
                    } catch (t: Throwable) {
                        println("Failed to persist primary language from voice settings: $t")
                    }
                } catch (t: Throwable) {
                    println("Failed to save updated voice: $t")
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
