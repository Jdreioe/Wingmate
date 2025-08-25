package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
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
fun VoiceSelectionFullScreen(onNext: () -> Unit, onCancel: () -> Unit) {
    // Get dependencies once and cache them to avoid repeated GlobalContext access
    val koinContext = remember { GlobalContext.getOrNull() }
    val useCase = remember { koinContext?.get<VoiceUseCase>() }
    val settingsUseCase = remember { koinContext?.let { runCatching { it.get<SettingsUseCase>() }.getOrNull() } }
    
    var loading by remember { mutableStateOf(true) }
    var voices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Voice?>(null) }
    var selectedLanguageFilter by remember { mutableStateOf<String?>(null) }
    var supportedLanguages by remember { mutableStateOf<List<String>>(emptyList()) }
    var useSystemTts by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(useCase, settingsUseCase) {
        // Check TTS preference first
        if (settingsUseCase != null) {
            val settings = withContext(Dispatchers.IO) { 
                runCatching { settingsUseCase.get() }.getOrNull() 
            }
            useSystemTts = settings?.useSystemTts ?: false
            
            // If using system TTS, skip voice loading and go straight to next
            if (useSystemTts) {
                loading = false
                return@LaunchedEffect
            }
        }
        
        if (useCase == null) {
            error = "Voice service not available"
            loading = false
            return@LaunchedEffect
        }
        
        loading = true
        try {
            // Sequential operations to avoid database concurrency issues
            val fromCloud = try {
                withContext(Dispatchers.IO) { useCase.refreshFromAzure() }
            } catch (e: Exception) {
                println("Failed to refresh from Azure: $e")
                emptyList()
            }
            
            val local = try {
                withContext(Dispatchers.IO) { useCase.list() }
            } catch (e: Exception) {
                println("Failed to load local voices: $e")
                emptyList()
            }
            
            voices = (fromCloud + local).distinctBy { it.name }
            // aggregate supported languages from Azure catalog (primary + supportedLanguages)
            supportedLanguages = voices.flatMap { 
                val primary = it.primaryLanguage?.let { lang -> listOf(lang) } ?: emptyList()
                val supported = it.supportedLanguages ?: emptyList()
                primary + supported
            }.filterNotNull().distinct().sorted()
            
            val alreadySelected = try {
                withContext(Dispatchers.IO) { useCase.selected() }
            } catch (e: Exception) {
                println("Failed to load selected voice: $e")
                null
            }
            selected = alreadySelected
        } catch (t: Throwable) {
            error = "Failed to load voices: ${t.message}"
            println("Voice loading error: $t")
        } finally {
            loading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Voice Selection", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        if (useSystemTts) {
            // System TTS selected - show info and skip voice selection
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "System Text-to-Speech Enabled",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "You've selected to use your device's built-in text-to-speech engine. Voice selection is handled through your device's system settings.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text("Back") }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onNext) { Text("Continue") }
            }
        } else if (loading) {
            CircularProgressIndicator()
        } else if (error != null) {
            Text("Error: $error")
        } else {
            // Language filter chips
            val scrollState = rememberScrollState()
            Row(modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(vertical = 8.dp), 
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = {
                    selectedLanguageFilter = null
                }) {
                    // "All" chip
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "All")
                }

                supportedLanguages.forEach { lang ->
                    AssistChip(
                        onClick = { 
                            selectedLanguageFilter = if (selectedLanguageFilter == lang) null else lang
                        },
                        label = { Text(lang) },
                        modifier = Modifier.clickable {
                            // Update primary language in settings if available
                            settingsUseCase?.let { settingsRepo ->
                                scope.launch {
                                    try {
                                        val currentSettings = withContext(Dispatchers.IO) { 
                                            runCatching { settingsRepo.get() }.getOrNull() 
                                        }
                                        val updatedSettings = currentSettings?.copy(primaryLanguage = lang) ?: return@launch
                                        withContext(Dispatchers.IO) { settingsRepo.update(updatedSettings) }
                                        println("Updated primary language to $lang")
                                    } catch (t: Throwable) {
                                        println("Failed to update primary language: $t")
                                    }
                                }
                            }
                        }
                    )
                }

                IconButton(onClick = {
                    // Settings button - for now just do nothing
                }) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(voices.filter { voice ->
                    selectedLanguageFilter?.let { filter ->
                        voice.primaryLanguage == filter || (voice.supportedLanguages?.contains(filter) == true)
                    } ?: true
                }) { voice ->
                    Row(modifier = Modifier
                        .fillMaxWidth()
                        .clickable { 
                            if (useCase == null) return@clickable
                            scope.launch {
                                try {
                                    val currentFilter = selectedLanguageFilter
                                    val voiceToSelect = if (voice.selectedLanguage != currentFilter && currentFilter != null && (voice.primaryLanguage == currentFilter || (voice.supportedLanguages?.contains(currentFilter) == true))) {
                                        voice.copy(selectedLanguage = currentFilter)
                                    } else {
                                        voice
                                    }
                                    withContext(Dispatchers.IO) { useCase.select(voiceToSelect) }
                                    println("Selected voice: ${voice.name} with language: ${voiceToSelect.selectedLanguage}")
                                    selected = voiceToSelect
                                } catch (t: Throwable) {
                                    println("Failed to select voice: ${voice.name}: $t")
                                }
                            }
                        }
                        .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(voice.displayName ?: voice.name ?: "Unknown Voice")
                            Text(voice.primaryLanguage ?: "Unknown Language", modifier = Modifier.padding(top = 4.dp))
                        }
                        
                        Button(onClick = {
                            // Settings button for individual voice
                        }) {
                            Text("Settings")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(modifier = Modifier.width(16.dp))
            Button(onClick = onNext) { Text("Next") }
        }
    }
}
