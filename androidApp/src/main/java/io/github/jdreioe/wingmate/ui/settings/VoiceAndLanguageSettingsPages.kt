package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.domain.GoogleVoiceModel
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.resolvedGoogleModel
import org.koin.compose.getKoin

@Composable
private fun rememberVoiceSelectionOperations(): VoiceSelectionOperations {
    val koin = getKoin()
    return remember(koin) {
        DefaultVoiceSelectionOperations(
            voiceUseCase = koin.get(),
            settingsUseCase = koin.get(),
            systemVoiceProvider = koin.getOrNull(),
            featureUsageReporter = koin.get(),
            speechService = koin.get(),
        )
    }
}

/**
 * Voice picker shared by Settings and Welcome; [onVoiceSelected] defaults to [onBack].
 * Setup passes [engineToSave] so the engine is saved together with the chosen voice.
 */
@Composable
internal fun VoiceSelectionPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onVoiceSelected: (() -> Unit)? = null,
    engineToSave: TtsEngine? = null,
) {
    val operations = rememberVoiceSelectionOperations()
    val viewModel: VoiceSelectionViewModel = viewModel(
        // Keyed by engine: a cached view model must not keep another engine.
        key = "voice-selection-${engineToSave?.name ?: "saved"}",
        factory = remember(operations, engineToSave) {
            viewModelFactory { initializer { VoiceSelectionViewModel(operations, engineToSave) } }
        }
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.onAction(VoiceSelectionAction.Load) }
    // The view model outlives this page; a preview must stop when the page leaves.
    DisposableEffect(viewModel) { onDispose { viewModel.onAction(VoiceSelectionAction.PreviewStopped) } }
    val currentOnChosen by rememberUpdatedState(onVoiceSelected ?: onBack)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                VoiceSelectionEvent.VoiceChosen -> currentOnChosen()
            }
        }
    }
    VoiceSelectionContent(state = state, onAction = viewModel::onAction, modifier = modifier)
}

@Composable
private fun VoiceSelectionContent(
    state: VoiceSelectionUiState,
    onAction: (VoiceSelectionAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = state as? VoiceSelectionUiState.Ready
    val ttsEngine = ready?.engine ?: TtsEngine.SYSTEM
    val voices = ready?.voices.orEmpty()
    var selectedLanguage by remember { mutableStateOf<String?>(null) }
    var voiceSearch by remember { mutableStateOf("") }
    var genderFilter by remember { mutableStateOf<String?>(null) }
    var googleModelFilter by remember { mutableStateOf<GoogleVoiceModel?>(null) }
    var editingVoice by remember { mutableStateOf<Voice?>(null) }

    val queryTerms = remember(voiceSearch) {
        voiceSearch.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }
    val languageFilteredVoices = remember(voices, selectedLanguage, ttsEngine) {
        val language = selectedLanguage ?: return@remember voices
        voices.filter { voice ->
            voice.primaryLanguage == language ||
                (ttsEngine != TtsEngine.SYSTEM && voice.supportedLanguages?.contains(language) == true)
        }
    }
    val availableGenders = remember(languageFilteredVoices) {
        languageFilteredVoices.mapNotNull { it.gender?.trim()?.takeIf { gender -> gender.isNotEmpty() } }.distinct().sorted()
    }
    LaunchedEffect(availableGenders, genderFilter) {
        if (genderFilter != null && !availableGenders.contains(genderFilter)) {
            genderFilter = null
        }
    }
    val availableGoogleModels = remember(voices, ttsEngine) {
        if (ttsEngine == TtsEngine.GOOGLE_CLOUD) {
            GoogleVoiceModel.entries.filter { model -> voices.any { it.resolvedGoogleModel() == model } }
        } else emptyList()
    }
    LaunchedEffect(availableGoogleModels, googleModelFilter) {
        if (googleModelFilter != null && googleModelFilter !in availableGoogleModels) googleModelFilter = null
    }
    val filteredVoices = remember(languageFilteredVoices, queryTerms, genderFilter, googleModelFilter, ttsEngine) {
        languageFilteredVoices.filter { voice ->
            matchesVoiceFilters(voice = voice, queryTerms = queryTerms, genderFilter = genderFilter) &&
                (ttsEngine != TtsEngine.GOOGLE_CLOUD || googleModelFilter == null || voice.resolvedGoogleModel() == googleModelFilter)
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(top = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OutlinedTextField(
            value = voiceSearch,
            onValueChange = { voiceSearch = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.voice_search_placeholder)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent
            )
        )

        VoiceFilterChips(
            languages = ready?.languages.orEmpty(),
            selectedLanguage = selectedLanguage,
            genders = availableGenders,
            selectedGender = genderFilter,
            onLanguageSelected = { language ->
                selectedLanguage = language
                onAction(VoiceSelectionAction.FilterApplied("language", cleared = language == null))
            },
            onGenderSelected = { gender ->
                genderFilter = gender
                onAction(VoiceSelectionAction.FilterApplied("gender", cleared = gender == null))
            }
        )

        if (ttsEngine == TtsEngine.GOOGLE_CLOUD) {
            GoogleModelFilterChips(
                models = availableGoogleModels,
                selected = googleModelFilter,
                onSelected = { googleModelFilter = it },
            )
        }

        // Hidden while loading: "Showing 0 of 0 voices" over a spinner reads as an empty list.
        if (ready != null) {
            Text(
                pluralStringResource(
                    R.plurals.voice_showing_count,
                    voices.size,
                    filteredVoices.size,
                    voices.size,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (ready?.saveFailed == true) {
            Text(stringResource(R.string.voice_save_failed), color = MaterialTheme.colorScheme.error)
        }

        when (state) {
            VoiceSelectionUiState.Loading ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            VoiceSelectionUiState.LoadFailed -> {
                Text(stringResource(R.string.voice_load_failed), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { onAction(VoiceSelectionAction.Load) }) {
                    Text(stringResource(R.string.common_retry))
                }
            }
            is VoiceSelectionUiState.Ready -> {
                val titleRes = when (ttsEngine) {
                    TtsEngine.SYSTEM -> if (selectedLanguage != null) R.string.voice_system_title_with_lang else R.string.voice_system_title
                    TtsEngine.ELEVENLABS -> if (selectedLanguage != null) R.string.voice_elevenlabs_title_with_lang else R.string.elevenlabs_title
                    TtsEngine.GOOGLE_CLOUD -> if (selectedLanguage != null) R.string.voice_google_title_with_lang else R.string.voice_google_title
                    else -> if (selectedLanguage != null) R.string.voice_azure_title_with_lang else R.string.voice_azure_title
                }
                val emptyRes = when (ttsEngine) {
                    TtsEngine.SYSTEM -> R.string.voice_no_system_match
                    TtsEngine.ELEVENLABS -> R.string.voice_no_elevenlabs_match
                    TtsEngine.GOOGLE_CLOUD -> R.string.voice_no_google_match
                    else -> R.string.voice_no_azure_match
                }

                SettingsGroup(title = stringResource(titleRes, selectedLanguage ?: "")) {
                    if (filteredVoices.isEmpty()) {
                        Text(
                            stringResource(emptyRes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        val sampleText = stringResource(R.string.test_voice_default_text)
                        filteredVoices.forEachIndexed { index, v ->
                            VoiceRow(
                                voice = v,
                                isSelected = state.selected?.name == v.name,
                                showSettings = ttsEngine != TtsEngine.SYSTEM,
                                preview = state.preview,
                                onSelect = { onAction(VoiceSelectionAction.VoiceSelected(v, selectedLanguage)) },
                                onPreview = { onAction(VoiceSelectionAction.PreviewToggled(v, sampleText)) },
                                onSettings = { editingVoice = v }
                            )
                            if (index < filteredVoices.lastIndex) {
                                SettingsGroupDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    editingVoice?.let { voice ->
        VoiceSettingsDialog(
            show = true,
            voice = voice,
            onDismiss = { editingVoice = null },
            onSave = { updated ->
                editingVoice = null
                onAction(VoiceSelectionAction.VoiceSettingsSaved(updated))
            }
        )
    }
}

@Composable
private fun VoiceRow(
    voice: Voice,
    isSelected: Boolean,
    showSettings: Boolean,
    preview: VoicePreview,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
    onSettings: () -> Unit
) {
    val playing = preview is VoicePreview.Playing && preview.voiceName == voice.name
    val failed = preview is VoicePreview.Failed && preview.voiceName == voice.name
    val name = voice.displayName ?: voice.name ?: stringResource(R.string.common_unknown)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = voice.primaryLanguage?.let(::localizedLocaleDisplayName).orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isSelected) {
                Text(
                    stringResource(R.string.voice_selected),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (failed) {
                Text(
                    stringResource(R.string.voice_preview_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        // Hear a voice without choosing it; a large target, labelled with the voice for TalkBack.
        val previewLabel = stringResource(if (playing) R.string.voice_preview_stop else R.string.voice_preview_play, name)
        FilledTonalIconButton(
            onClick = onPreview,
            modifier = Modifier.size(56.dp).semantics { contentDescription = previewLabel },
        ) {
            Icon(if (playing) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
        }
        if (showSettings) {
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onSettings) { Text(stringResource(R.string.voice_settings)) }
        }
    }
}

@Composable
private fun VoiceFilterChips(
    languages: List<String>,
    selectedLanguage: String?,
    genders: List<String>,
    selectedGender: String?,
    onLanguageSelected: (String?) -> Unit,
    onGenderSelected: (String?) -> Unit
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selectedLanguage == null,
            onClick = { onLanguageSelected(null) },
            label = { Text(stringResource(R.string.voice_all_languages)) }
        )
        languages.forEach { language ->
            FilterChip(
                selected = selectedLanguage == language,
                onClick = { onLanguageSelected(language) },
                label = { Text(localizedLocaleDisplayName(language)) }
            )
        }
    }
    // A row whose only choice is "Unknown" (device voices) filters nothing.
    if (genders.any { !it.equals("Unknown", ignoreCase = true) }) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedGender == null,
                onClick = { onGenderSelected(null) },
                label = { Text(stringResource(R.string.language_all)) }
            )
            genders.forEach { gender ->
                FilterChip(
                    selected = selectedGender == gender,
                    onClick = { onGenderSelected(gender) },
                    label = { Text(gender) }
                )
            }
        }
    }
}

/** Primary/secondary language picker shared by Settings and Welcome. */
@Composable
internal fun LanguageSelectionPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onContinue: (() -> Unit)? = null
) {
    val operations = rememberVoiceSelectionOperations()
    val viewModel: LanguageSelectionViewModel = viewModel(
        factory = remember(operations) { viewModelFactory { initializer { LanguageSelectionViewModel(operations) } } }
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.onAction(LanguageSelectionAction.Load) }
    LanguageSelectionContent(state = state, onAction = viewModel::onAction, onContinue = onContinue, modifier = modifier)
}

@Composable
private fun LanguageSelectionContent(
    state: LanguageSelectionUiState,
    onAction: (LanguageSelectionAction) -> Unit,
    onContinue: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val ready = when (state) {
        LanguageSelectionUiState.Loading -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return
        }
        LanguageSelectionUiState.LoadFailed -> {
            Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.language_load_failed), color = MaterialTheme.colorScheme.error)
                Button(onClick = { onAction(LanguageSelectionAction.Load) }) { Text(stringResource(R.string.common_retry)) }
            }
            return
        }
        is LanguageSelectionUiState.Ready -> state
    }
    val noLanguagesAvailableLabel = stringResource(R.string.language_no_available)
    val noLanguagesMatchLabel = stringResource(R.string.language_no_match)
    var filter by remember { mutableStateOf("") }

    val queryTerms = remember(filter) {
        filter.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }
    val filteredLanguages = remember(ready.languages, queryTerms) {
        ready.languages.filter { lang ->
            val codePart = languageCodePart(lang)
            val regionPart = regionCodePart(lang)
            queryTerms.all { term ->
                lang.contains(term, ignoreCase = true) ||
                    localizedLocaleDisplayName(lang).contains(term, ignoreCase = true) ||
                    codePart.contains(term, ignoreCase = true) ||
                    (regionPart?.contains(term, ignoreCase = true) == true)
            }
        }
    }
    val emptyLabel = if (ready.languages.isEmpty()) noLanguagesAvailableLabel else noLanguagesMatchLabel

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(top = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (ready.saveFailed) {
            Text(stringResource(R.string.language_save_failed), color = MaterialTheme.colorScheme.error)
        }

        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.language_search_label)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent
            )
        )

        SettingsGroup(title = stringResource(R.string.language_primary)) {
            Text(
                stringResource(R.string.language_primary_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            LanguageList(
                available = filteredLanguages,
                selected = ready.primary,
                emptyLabel = emptyLabel,
                onSelect = { onAction(LanguageSelectionAction.PrimarySelected(it)) }
            )
        }

        if (ready.voiceIsMultilingual) {
            SettingsGroup(title = stringResource(R.string.language_secondary)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.language_secondary_enable), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.language_secondary_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = ready.usesSecondary,
                        onCheckedChange = { onAction(LanguageSelectionAction.SecondaryToggled(it)) }
                    )
                }
                if (ready.usesSecondary) {
                    Spacer(Modifier.height(8.dp))
                    LanguageList(
                        available = filteredLanguages,
                        selected = ready.secondary,
                        emptyLabel = emptyLabel,
                        onSelect = { onAction(LanguageSelectionAction.SecondarySelected(it)) }
                    )
                }
            }
        } else {
            Text(
                stringResource(R.string.language_secondary_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (onContinue != null) {
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.common_continue))
            }
        }
    }
}

@Composable
private fun LanguageList(
    available: List<String>,
    selected: String,
    emptyLabel: String,
    onSelect: (String) -> Unit
) {
    if (available.isEmpty()) {
        Text(
            emptyLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )
    } else {
        available.forEach { lang ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(lang) }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(localizedLocaleDisplayName(lang), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (lang == selected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            if (lang != available.last()) {
                SettingsGroupDivider()
            }
        }
    }
}

private fun languageCodePart(localeTag: String): String {
    return localeTag.substringBefore('-').ifBlank { localeTag }
}

private fun regionCodePart(localeTag: String): String? {
    val region = localeTag.substringAfter('-', "").ifBlank { return null }
    return region
}
