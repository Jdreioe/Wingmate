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
import io.github.jdreioe.wingmate.domain.TtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import org.koin.compose.getKoin
import org.koin.compose.koinInject

import com.hojmoseit.wingmate.R
@Composable
fun VoiceSelectionDialog(show: Boolean, onDismiss: () -> Unit, onOpenWelcomeFlow: (() -> Unit)? = null) {
    if (!show) return

    val koin = getKoin()
    val useCase = koinInject<VoiceUseCase>()
    val settingsUseCase = remember(koin) { koin.getOrNull<SettingsUseCase>() }
    var loading by remember { mutableStateOf(true) }
    var voices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var operationError by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Voice?>(null) }
    var showVoiceSettings by remember { mutableStateOf(false) }
    var editingVoice by remember { mutableStateOf<Voice?>(null) }
    var ttsEngine by remember { mutableStateOf(TtsEngine.SYSTEM) }
    var systemVoices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var selectedLanguage by remember { mutableStateOf<String?>(null) }
    var availableLanguages by remember { mutableStateOf<List<String>>(emptyList()) }
    var showLanguageFilter by remember { mutableStateOf(false) }
    var showGenderFilter by remember { mutableStateOf(false) }
    var voiceSearch by remember { mutableStateOf("") }
    var genderFilter by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val voiceLoadFailed = stringResource(R.string.voice_load_failed)
    val voiceSaveFailed = stringResource(R.string.voice_save_failed)

    val systemVoiceProvider = remember(koin) { koin.getOrNull<io.github.jdreioe.wingmate.infrastructure.SystemVoiceProvider>() }

    LaunchedEffect(retryKey) {
        loading = true
        error = null
        try {
            val settings = checkNotNull(settingsUseCase) { "Settings are unavailable" }
                .let { withContext(Dispatchers.Default) { it.get() } }
            ttsEngine = settings.ttsEngine
            if (ttsEngine == TtsEngine.SYSTEM) {
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
                selected = useCase.selected()
            } else {
                // Load Azure voices
                var cloudRefreshFailed = false
                val fromCloud = try {
                    withContext(Dispatchers.Default) {
                        if (ttsEngine == TtsEngine.GOOGLE_CLOUD) useCase.refreshFromGoogle()
                        else useCase.refreshFromAzure()
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    cloudRefreshFailed = true
                    emptyList()
                }
                val local = withContext(Dispatchers.Default) { useCase.list() }
                val allVoices = (fromCloud + local).distinctBy { it.name }
                if (allVoices.isEmpty() && cloudRefreshFailed) {
                    error("No cached voices were available after refresh failed")
                }
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
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            error = voiceLoadFailed
        } finally {
            loading = false
        }
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

    val activeLanguageFilteredVoices = if (ttsEngine == TtsEngine.SYSTEM) languageFilteredSystemVoices else languageFilteredAzureVoices
    val allLabel = stringResource(R.string.language_all)
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

    val visibleVoiceCount = if (ttsEngine == TtsEngine.SYSTEM) filteredSystemVoices.size else filteredAzureVoices.size
    val totalVoiceCount = if (ttsEngine == TtsEngine.SYSTEM) systemVoices.size else voices.size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_select_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                operationError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                }
                // Voice search and filter section.
                if (!loading && error == null) {
                    val showKeyboard = Modifier.showKeyboardOnFocus()
                    OutlinedTextField(
                        value = voiceSearch,
                        onValueChange = { voiceSearch = it },
                        label = { Text(stringResource(R.string.voice_search_label)) },
                        placeholder = { Text(stringResource(R.string.voice_search_placeholder)) },
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
                                    contentDescription = stringResource(R.string.voice_filter_languages_content_desc),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    selectedLanguage ?: stringResource(R.string.voice_all_languages),
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
                                    text = { Text(stringResource(R.string.voice_all_languages)) },
                                    onClick = {
                                        selectedLanguage = null
                                        showLanguageFilter = false
                                    }
                                )
                                availableLanguages.forEach { language ->
                                    DropdownMenuItem(
                                        text = { Text(localizedLocaleDisplayName(language)) },
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
                                    stringResource(R.string.voice_gender_label, genderFilter ?: allLabel),
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
                        text = pluralStringResource(
                            R.plurals.voice_showing_count,
                            totalVoiceCount,
                            visibleVoiceCount,
                            totalVoiceCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
                
                if (loading) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                } else if (error != null) {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { retryKey++ }) {
                        Text(stringResource(R.string.common_retry))
                    }
                } else if (ttsEngine == TtsEngine.SYSTEM) {
                    // Show system voices
                    Text(
                        text = if (selectedLanguage != null) {
                            stringResource(R.string.voice_system_title_with_lang, selectedLanguage ?: "")
                        } else {
                            stringResource(R.string.voice_system_title)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    if (filteredSystemVoices.isEmpty()) {
                        Text(
                            stringResource(R.string.voice_no_system_match),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                            items(filteredSystemVoices) { v ->
                                Row(modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            operationError = null
                                            try {
                                                useCase.select(v)
                                                // Update settings if needed
                                                val primary = v.primaryLanguage ?: ""
                                                if (primary.isNotBlank() && settingsUseCase != null) {
                                                    val current = settingsUseCase.get()
                                                    val updated = current.copy(primaryLanguage = primary)
                                                    settingsUseCase.update(updated)
                                                }
                                                selected = v
                                                onDismiss()
                                            } catch (failure: CancellationException) {
                                                throw failure
                                            } catch (_: Exception) {
                                                operationError = voiceSaveFailed
                                            }
                                        }
                                    }
                                    .padding(8.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(text = v.displayName ?: v.name ?: stringResource(R.string.common_unknown))
                                        Text(text = v.primaryLanguage ?: "", modifier = Modifier.padding(top = 2.dp))
                                        if (selected?.name == v.name) {
                                            Text(
                                                text = stringResource(R.string.voice_selected),
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
                            stringResource(R.string.voice_azure_title_with_lang, selectedLanguage ?: "")
                        } else {
                            stringResource(R.string.voice_azure_title)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    if (filteredAzureVoices.isEmpty()) {
                        Text(
                            stringResource(R.string.voice_no_azure_match),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                            items(filteredAzureVoices) { v ->
                                Row(modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = true, onClickLabel = null) {
                                        scope.launch {
                                            operationError = null
                                            try {
                                                useCase.select(v)
                                                // also persist UI primary language when selecting a voice
                                                val primary = v.selectedLanguage.ifBlank { v.primaryLanguage ?: "" }
                                                if (primary.isNotBlank() && settingsUseCase != null) {
                                                    val current = settingsUseCase.get()
                                                    val updated = current.copy(primaryLanguage = primary)
                                                    settingsUseCase.update(updated)
                                                }
                                                selected = v
                                                onDismiss()
                                            } catch (failure: CancellationException) {
                                                throw failure
                                            } catch (_: Exception) {
                                                operationError = voiceSaveFailed
                                            }
                                        }
                                    }
                                    .padding(8.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(text = v.displayName ?: v.name ?: stringResource(R.string.common_unknown))
                                        Text(text = v.primaryLanguage ?: "", modifier = Modifier.padding(top = 2.dp))
                                    }
                                    Button(onClick = {
                                        // open settings for this voice
                                        editingVoice = v
                                        showVoiceSettings = true
                                    }) {
                                        Text(stringResource(R.string.voice_settings))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
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
                    operationError = null
                    try {
                        useCase.select(updated)
                        // also persist primary language from updated voice if available
                        val primary = updated.selectedLanguage.ifBlank { updated.primaryLanguage ?: "" }
                        if (primary.isNotBlank() && settingsUseCase != null) {
                            val current = settingsUseCase.get()
                            val updatedSettings = current.copy(primaryLanguage = primary)
                            settingsUseCase.update(updatedSettings)
                        }
                        showVoiceSettings = false
                        val refreshed = if (ttsEngine == TtsEngine.GOOGLE_CLOUD) {
                            useCase.refreshFromGoogle()
                        } else {
                            useCase.refreshFromAzure()
                        }
                        voices = (refreshed + useCase.list()).distinctBy { it.name }
                        selected = useCase.selected()
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: Exception) {
                        operationError = voiceSaveFailed
                    }
                }
            },
            onOpenWelcomeFlow = onOpenWelcomeFlow
        )
    }
}

internal fun matchesVoiceFilters(
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

internal fun buildVoiceSearchText(voice: Voice): String {
    val supported = voice.supportedLanguages ?: emptyList()
    return buildString {
        append(voice.displayName.orEmpty())
        append(' ')
        append(voice.name.orEmpty())
        append(' ')
        append(voice.primaryLanguage.orEmpty())
        voice.primaryLanguage?.let { append(' '); append(localizedLocaleDisplayName(it)) }
        append(' ')
        append(voice.gender.orEmpty())
        if (supported.isNotEmpty()) {
            append(' ')
            append(supported.joinToString(" "))
            append(' ')
            append(supported.joinToString(" ") { localizedLocaleDisplayName(it) })
        }
    }.lowercase()
}
