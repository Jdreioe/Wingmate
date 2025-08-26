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
import androidx.compose.material.icons.filled.FilterList
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
fun VoiceSelectionFullScreen(onNext: () -> Unit, onCancel: () -> Unit, onBackToWelcome: (() -> Unit)? = null) {
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
    var showLanguageFilter by remember { mutableStateOf(false) }
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
            // System TTS selected - show available system voices for selection
            Text(
                "System TTS Voices",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            // Language filter for system voices
            if (supportedLanguages.isNotEmpty()) {
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
                                selectedLanguageFilter ?: "All Languages",
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
                                    selectedLanguageFilter = null
                                    showLanguageFilter = false
                                }
                            )
                            supportedLanguages.forEach { language ->
                                DropdownMenuItem(
                                    text = { Text(language) },
                                    onClick = {
                                        selectedLanguageFilter = language
                                        showLanguageFilter = false
                                    }
                                )
                            }
                        }
                    }
                }
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            
            // Get system voice provider
            val systemVoiceProvider = remember {
                try {
                    val koin = GlobalContext.getOrNull()
                    koin?.let { runCatching { it.get<io.github.jdreioe.wingmate.infrastructure.SystemVoiceProvider>() }.getOrNull() }
                } catch (e: Exception) {
                    null
                }
            }
            
            var systemVoices by remember { mutableStateOf<List<Voice>>(emptyList()) }
            var systemVoicesLoading by remember { mutableStateOf(true) }
            
            LaunchedEffect(systemVoiceProvider) {
                systemVoicesLoading = true
                try {
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
                    supportedLanguages = allSystemVoices
                        .mapNotNull { it.primaryLanguage }
                        .distinct()
                        .sorted()
                } catch (e: Exception) {
                    systemVoices = listOf(
                        Voice(
                            name = "system-default",
                            displayName = "System Default",
                            primaryLanguage = "en-US", 
                            gender = "Unknown"
                        )
                    )
                    supportedLanguages = listOf("en-US")
                }
                systemVoicesLoading = false
            }
            
            // Filter system voices based on selected language
            val filteredSystemVoices = if (selectedLanguageFilter != null) {
                systemVoices.filter { it.primaryLanguage == selectedLanguageFilter }
            } else {
                systemVoices
            }
            
            if (systemVoicesLoading) {
                CircularProgressIndicator()
            } else if (filteredSystemVoices.isEmpty() && selectedLanguageFilter != null) {
                Text(
                    "No system voices found for $selectedLanguageFilter",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f)
                ) {
                    items(filteredSystemVoices) { voice ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    scope.launch {
                                        try {
                                            useCase?.select(voice)
                                            onNext()
                                        } catch (e: Exception) {
                                            // Handle error
                                        }
                                    }
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected?.name == voice.name) 
                                    MaterialTheme.colorScheme.primaryContainer 
                                else 
                                    MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = voice.displayName ?: voice.name ?: "Unknown",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                val primaryLang = voice.primaryLanguage
                                if (!primaryLang.isNullOrBlank()) {
                                    Text(
                                        text = primaryLang,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (selected?.name == voice.name) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        "✓ Selected",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onCancel) { Text("Back") }
                Button(onClick = onNext) { Text("Continue") }
            }
        } else if (loading) {
            CircularProgressIndicator()
        } else if (error != null) {
            Text("Error: $error")
        } else {
            // Filter Azure voices based on selected language
            val filteredAzureVoices = if (selectedLanguageFilter != null) {
                voices.filter { voice ->
                    voice.primaryLanguage == selectedLanguageFilter || 
                    voice.supportedLanguages?.contains(selectedLanguageFilter) == true
                }
            } else {
                voices
            }
            
            // Language filter for Azure voices
            if (supportedLanguages.isNotEmpty()) {
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
                                selectedLanguageFilter ?: "All Languages",
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
                                    selectedLanguageFilter = null
                                    showLanguageFilter = false
                                }
                            )
                            supportedLanguages.forEach { language ->
                                DropdownMenuItem(
                                    text = { Text(language) },
                                    onClick = {
                                        selectedLanguageFilter = language
                                        showLanguageFilter = false
                                    }
                                )
                            }
                        }
                    }
                }
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            
            // Show filtered voices or empty message
            if (filteredAzureVoices.isEmpty() && selectedLanguageFilter != null) {
                Text(
                    "No Azure voices found for $selectedLanguageFilter",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filteredAzureVoices) { voice ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
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
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected?.name == voice.name) 
                                    MaterialTheme.colorScheme.primaryContainer 
                                else 
                                    MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = voice.displayName ?: voice.name ?: "Unknown Voice",
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    val primaryLang = voice.primaryLanguage
                                    if (!primaryLang.isNullOrBlank()) {
                                        Text(
                                            text = primaryLang,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                    if (selected?.name == voice.name) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            "✓ Selected",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                
                                IconButton(onClick = {
                                    // Settings button for individual voice - placeholder
                                }) {
                                    Icon(Icons.Default.Settings, contentDescription = "Voice Settings")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
