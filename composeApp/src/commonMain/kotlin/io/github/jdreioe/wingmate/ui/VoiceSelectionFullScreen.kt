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
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import wingmatekmp.composeapp.generated.resources.Res
import wingmatekmp.composeapp.generated.resources.common_back
import wingmatekmp.composeapp.generated.resources.common_continue
import wingmatekmp.composeapp.generated.resources.language_filter_languages
import wingmatekmp.composeapp.generated.resources.voice_all_languages
import wingmatekmp.composeapp.generated.resources.voice_azure_title
import wingmatekmp.composeapp.generated.resources.voice_azure_title_with_lang
import wingmatekmp.composeapp.generated.resources.voice_error
import wingmatekmp.composeapp.generated.resources.voice_filter_languages_content_desc
import wingmatekmp.composeapp.generated.resources.voice_no_azure_match
import wingmatekmp.composeapp.generated.resources.voice_no_system_match
import wingmatekmp.composeapp.generated.resources.voice_select_title
import wingmatekmp.composeapp.generated.resources.voice_selected
import wingmatekmp.composeapp.generated.resources.voice_settings
import wingmatekmp.composeapp.generated.resources.voice_system_title
import wingmatekmp.composeapp.generated.resources.voice_system_title_with_lang

@Composable
fun VoiceSelectionFullScreen(onNext: () -> Unit, onCancel: () -> Unit, onBackToWelcome: (() -> Unit)? = null) {
    val koin = getKoin()
    val useCase = koinInject<VoiceUseCase>()
    val settingsUseCase = remember(koin) { koin.getOrNull<SettingsUseCase>() }
    val systemVoiceProvider = remember(koin) { koin.getOrNull<io.github.jdreioe.wingmate.infrastructure.SystemVoiceProvider>() }
    
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
        }

        // If using system TTS, skip voice loading and go straight to next
        if (useSystemTts) {
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
        Text(stringResource(Res.string.voice_select_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        if (useSystemTts) {
            // System TTS selected - show available system voices for selection
            Text(
                text = if (selectedLanguageFilter != null) {
                    stringResource(Res.string.voice_system_title_with_lang, selectedLanguageFilter ?: "")
                } else {
                    stringResource(Res.string.voice_system_title)
                },
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
                        stringResource(Res.string.language_filter_languages),
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
                                contentDescription = stringResource(Res.string.voice_filter_languages_content_desc),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                selectedLanguageFilter ?: stringResource(Res.string.voice_all_languages),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        }
                        
                        DropdownMenu(
                            expanded = showLanguageFilter,
                            onDismissRequest = { showLanguageFilter = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.voice_all_languages)) },
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
                    stringResource(Res.string.voice_no_system_match),
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
                                            useCase.select(voice)
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
                                        stringResource(Res.string.voice_selected),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else if (loading) {
            CircularProgressIndicator()
        } else if (error != null) {
            Text(stringResource(Res.string.voice_error, error ?: ""))
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
                        stringResource(Res.string.language_filter_languages),
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
                                contentDescription = stringResource(Res.string.voice_filter_languages_content_desc),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                selectedLanguageFilter ?: stringResource(Res.string.voice_all_languages),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        }
                        
                        DropdownMenu(
                            expanded = showLanguageFilter,
                            onDismissRequest = { showLanguageFilter = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.voice_all_languages)) },
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
                    stringResource(Res.string.voice_no_azure_match),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                Text(
                    text = if (selectedLanguageFilter != null) {
                        stringResource(Res.string.voice_azure_title_with_lang, selectedLanguageFilter ?: "")
                    } else {
                        stringResource(Res.string.voice_azure_title)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filteredAzureVoices) { voice ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
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
                                            stringResource(Res.string.voice_selected),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                
                                IconButton(onClick = {
                                    // Settings button for individual voice - placeholder
                                }) {
                                    Icon(Icons.Default.Settings, contentDescription = stringResource(Res.string.voice_settings))
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Navigation buttons for Azure TTS section
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onCancel) { Text(stringResource(Res.string.common_back)) }
            Button(onClick = onNext) { Text(stringResource(Res.string.common_continue)) }
        }
    }
}
