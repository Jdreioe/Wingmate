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
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import wingmatekmp.composeapp.generated.resources.Res
import wingmatekmp.composeapp.generated.resources.common_close
import wingmatekmp.composeapp.generated.resources.language_all
import wingmatekmp.composeapp.generated.resources.voice_all_languages
import wingmatekmp.composeapp.generated.resources.voice_azure_title
import wingmatekmp.composeapp.generated.resources.voice_azure_title_with_lang
import wingmatekmp.composeapp.generated.resources.voice_error
import wingmatekmp.composeapp.generated.resources.voice_filter_languages_content_desc
import wingmatekmp.composeapp.generated.resources.voice_gender_label
import wingmatekmp.composeapp.generated.resources.voice_no_azure_match
import wingmatekmp.composeapp.generated.resources.voice_no_system_match
import wingmatekmp.composeapp.generated.resources.voice_search_label
import wingmatekmp.composeapp.generated.resources.voice_search_placeholder
import wingmatekmp.composeapp.generated.resources.voice_select_title
import wingmatekmp.composeapp.generated.resources.voice_selected
import wingmatekmp.composeapp.generated.resources.voice_settings
import wingmatekmp.composeapp.generated.resources.voice_showing_count
import wingmatekmp.composeapp.generated.resources.voice_system_title
import wingmatekmp.composeapp.generated.resources.voice_system_title_with_lang

@Composable
fun VoiceSelectionDialog(show: Boolean, onDismiss: () -> Unit, onOpenWelcomeFlow: (() -> Unit)? = null) {
    if (!show) return

    val koin = getKoin()
    val useCase = koinInject<VoiceUseCase>()
    val settingsUseCase = remember(koin) { koin.getOrNull<SettingsUseCase>() }
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
    var showGenderFilter by remember { mutableStateOf(false) }
    var voiceSearch by remember { mutableStateOf("") }
    var genderFilter by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val systemVoiceProvider = remember(koin) { koin.getOrNull<io.github.jdreioe.wingmate.infrastructure.SystemVoiceProvider>() }

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

    val queryTerms = remember(voiceSearch) {
        voiceSearch
            .trim()
            .lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
    }

    // Filter voices by exact language selection first.
    val languageFilteredSystemVoices = if (selectedLanguage != null) {
        systemVoices.filter { it.primaryLanguage == selectedLanguage }
    } else {
        systemVoices
    }

    val languageFilteredAzureVoices = if (selectedLanguage != null) {
        voices.filter { voice ->
            voice.primaryLanguage == selectedLanguage ||
                voice.supportedLanguages?.contains(selectedLanguage) == true
        }
    } else {
        voices
    }

    val activeLanguageFilteredVoices = if (useSystemTts) languageFilteredSystemVoices else languageFilteredAzureVoices
    val allLabel = stringResource(Res.string.language_all)
    val availableGenders = remember(activeLanguageFilteredVoices) {
        activeLanguageFilteredVoices
            .mapNotNull { it.gender?.trim()?.takeIf { gender -> gender.isNotEmpty() } }
            .distinct()
            .sorted()
    }

    LaunchedEffect(availableGenders, genderFilter) {
        if (genderFilter != null && !availableGenders.contains(genderFilter)) {
            genderFilter = null
        }
    }

    val filteredSystemVoices = remember(languageFilteredSystemVoices, queryTerms, genderFilter) {
        languageFilteredSystemVoices.filter { voice ->
            matchesVoiceFilters(voice = voice, queryTerms = queryTerms, genderFilter = genderFilter)
        }
    }

    val filteredAzureVoices = remember(languageFilteredAzureVoices, queryTerms, genderFilter) {
        languageFilteredAzureVoices.filter { voice ->
            matchesVoiceFilters(voice = voice, queryTerms = queryTerms, genderFilter = genderFilter)
        }
    }

    val visibleVoiceCount = if (useSystemTts) filteredSystemVoices.size else filteredAzureVoices.size
    val totalVoiceCount = if (useSystemTts) systemVoices.size else voices.size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.voice_select_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                // Voice search and filter section.
                if (!loading && error == null) {
                    val showKeyboard = rememberShowKeyboardOnFocus()
                    OutlinedTextField(
                        value = voiceSearch,
                        onValueChange = { voiceSearch = it },
                        label = { Text(stringResource(Res.string.voice_search_label)) },
                        placeholder = { Text(stringResource(Res.string.voice_search_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().then(showKeyboard)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { showLanguageFilter = !showLanguageFilter },
                                modifier = Modifier
                                    .height(36.dp)
                                    .fillMaxWidth(),
                                enabled = availableLanguages.isNotEmpty()
                            ) {
                                Icon(
                                    Icons.Default.FilterList,
                                    contentDescription = stringResource(Res.string.voice_filter_languages_content_desc),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    selectedLanguage ?: stringResource(Res.string.voice_all_languages),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1
                                )
                            }
                            
                            DropdownMenu(
                                expanded = showLanguageFilter,
                                onDismissRequest = { showLanguageFilter = false },
                                modifier = Modifier
                                    .widthIn(min = 220.dp, max = 420.dp)
                                    .heightIn(max = 320.dp)
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.voice_all_languages)) },
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

                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { showGenderFilter = !showGenderFilter },
                                modifier = Modifier
                                    .height(36.dp)
                                    .fillMaxWidth()
                            ) {
                                Text(
                                    stringResource(Res.string.voice_gender_label, genderFilter ?: allLabel),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1
                                )
                            }

                            DropdownMenu(
                                expanded = showGenderFilter,
                                onDismissRequest = { showGenderFilter = false },
                                modifier = Modifier
                                    .widthIn(min = 180.dp, max = 320.dp)
                                    .heightIn(max = 300.dp)
                            ) {
                                DropdownMenuItem(
                                    text = { Text(allLabel) },
                                    onClick = {
                                        genderFilter = null
                                        showGenderFilter = false
                                    }
                                )
                                availableGenders.forEach { gender ->
                                    DropdownMenuItem(
                                        text = { Text(gender) },
                                        onClick = {
                                            genderFilter = gender
                                            showGenderFilter = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = stringResource(Res.string.voice_showing_count, visibleVoiceCount, totalVoiceCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
                
                if (loading) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                } else if (error != null) {
                    Text(stringResource(Res.string.voice_error, error ?: ""))
                } else if (useSystemTts) {
                    // Show system voices
                    Text(
                        text = if (selectedLanguage != null) {
                            stringResource(Res.string.voice_system_title_with_lang, selectedLanguage ?: "")
                        } else {
                            stringResource(Res.string.voice_system_title)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    if (filteredSystemVoices.isEmpty()) {
                        Text(
                            stringResource(Res.string.voice_no_system_match),
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
                                                text = stringResource(Res.string.voice_selected),
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
                        text = if (selectedLanguage != null) {
                            stringResource(Res.string.voice_azure_title_with_lang, selectedLanguage ?: "")
                        } else {
                            stringResource(Res.string.voice_azure_title)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    if (filteredAzureVoices.isEmpty()) {
                        Text(
                            stringResource(Res.string.voice_no_azure_match),
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
                                                val primary = v.selectedLanguage.ifBlank { v.primaryLanguage ?: "" }
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
                                        Text(stringResource(Res.string.voice_settings))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_close)) }
        }
    )

    // nested voice settings dialog
    val scope2 = rememberCoroutineScope()
    if (showVoiceSettings && editingVoice != null) {
        VoiceSettingsDialog(
            show = true, 
            voice = editingVoice!!, 
            onDismiss = { showVoiceSettings = false }, 
            onSave = { updated ->
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
            },
            onOpenWelcomeFlow = onOpenWelcomeFlow
        )
    }
}

private fun matchesVoiceFilters(
    voice: Voice,
    queryTerms: List<String>,
    genderFilter: String?
): Boolean {
    if (genderFilter != null && !voice.gender.equals(genderFilter, ignoreCase = true)) {
        return false
    }

    if (queryTerms.isEmpty()) {
        return true
    }

    val searchable = buildVoiceSearchText(voice)
    return queryTerms.all { term -> searchable.contains(term) }
}

private fun buildVoiceSearchText(voice: Voice): String {
    val supported = voice.supportedLanguages ?: emptyList()
    return buildString {
        append(voice.displayName.orEmpty())
        append(' ')
        append(voice.name.orEmpty())
        append(' ')
        append(voice.primaryLanguage.orEmpty())
        append(' ')
        append(voice.gender.orEmpty())
        if (supported.isNotEmpty()) {
            append(' ')
            append(supported.joinToString(" "))
        }
    }.lowercase()
}
