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
import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.GoogleVoiceModel
import io.github.jdreioe.wingmate.domain.resolvedGoogleModel
import io.github.jdreioe.wingmate.domain.withPreferredSupportedLanguage
import io.github.jdreioe.wingmate.domain.loggingClassName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import org.koin.compose.getKoin
import org.koin.compose.koinInject

import com.hojmoseit.wingmate.R
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
    var googleModelFilter by remember { mutableStateOf<GoogleVoiceModel?>(null) }
    var preferredLanguage by remember { mutableStateOf<String?>(null) }
    var ttsEngine by remember { mutableStateOf(TtsEngine.SYSTEM) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(useCase, settingsUseCase) {
        // Check TTS preference first
        if (settingsUseCase != null) {
            val settings = withContext(Dispatchers.IO) {
                runCatching { settingsUseCase.get() }.getOrNull()
            }
            ttsEngine = settings?.ttsEngine ?: TtsEngine.SYSTEM
            preferredLanguage = settings?.primaryLanguage
        }

        // If using system TTS, skip voice loading and go straight to next
        if (ttsEngine == TtsEngine.SYSTEM) {
            loading = false
            return@LaunchedEffect
        }
        
        loading = true
        try {
            // Sequential operations to avoid database concurrency issues
            val fromCloud = try {
                withContext(Dispatchers.IO) {
                    if (ttsEngine == TtsEngine.GOOGLE_CLOUD) useCase.refreshFromGoogle()
                    else useCase.refreshFromAzure()
                }
            } catch (e: Exception) {
                OperationalLogger.warn("voice_catalog.refresh", "failed", exceptionClass = e.loggingClassName())
                emptyList()
            }
            
            val local = try {
                withContext(Dispatchers.IO) { useCase.listForEngine(ttsEngine) }
            } catch (e: Exception) {
                OperationalLogger.warn("voice_catalog.load", "failed", exceptionClass = e.loggingClassName())
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
                OperationalLogger.warn("voice_selection.load", "failed", exceptionClass = e.loggingClassName())
                null
            }
            selected = alreadySelected
        } catch (t: Throwable) {
            error = "Failed to load voices: ${t.message}"
            OperationalLogger.warn("voice_catalog.load", "failed", exceptionClass = t.loggingClassName())
        } finally {
            loading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(stringResource(R.string.voice_select_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        if (ttsEngine == TtsEngine.SYSTEM) {
            // System TTS selected - show available system voices for selection
            Text(
                text = if (selectedLanguageFilter != null) {
                    stringResource(R.string.voice_system_title_with_lang, selectedLanguageFilter ?: "")
                } else {
                    stringResource(R.string.voice_system_title)
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
                        stringResource(R.string.language_filter_languages),
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
                                contentDescription = stringResource(R.string.voice_filter_languages_content_desc),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                selectedLanguageFilter?.let(::localizedLocaleDisplayName)
                                    ?: stringResource(R.string.voice_all_languages),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        }
                        
                        DropdownMenu(
                            expanded = showLanguageFilter,
                            onDismissRequest = { showLanguageFilter = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.voice_all_languages)) },
                                onClick = {
                                    selectedLanguageFilter = null
                                    showLanguageFilter = false
                                }
                            )
                            supportedLanguages.forEach { language ->
                                DropdownMenuItem(
                                    text = { Text(localizedLocaleDisplayName(language)) },
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
                    stringResource(R.string.voice_no_system_match),
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
                                .clickable {
                                    scope.launch {
                                        try {
                                            useCase.select(voice)
                                            onNext()
                                        } catch (e: Exception) {
                                            // Handle error
                                        }
                                    }
                                }
                                .padding(vertical = 4.dp),
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
                                        stringResource(R.string.voice_selected),
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
            Text(stringResource(R.string.voice_error, error ?: ""))
        } else {
            val availableGoogleModels = if (ttsEngine == TtsEngine.GOOGLE_CLOUD) {
                GoogleVoiceModel.entries.filter { model -> voices.any { it.resolvedGoogleModel() == model } }
            } else emptyList()
            val languageFilteredVoices = if (selectedLanguageFilter != null) {
                voices.filter { voice ->
                    voice.primaryLanguage == selectedLanguageFilter || 
                    voice.supportedLanguages?.contains(selectedLanguageFilter) == true
                }
            } else {
                voices
            }
            val filteredAzureVoices = languageFilteredVoices.filter { voice ->
                ttsEngine != TtsEngine.GOOGLE_CLOUD || googleModelFilter == null ||
                    voice.resolvedGoogleModel() == googleModelFilter
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
                        stringResource(R.string.language_filter_languages),
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
                                contentDescription = stringResource(R.string.voice_filter_languages_content_desc),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                selectedLanguageFilter?.let(::localizedLocaleDisplayName)
                                    ?: stringResource(R.string.voice_all_languages),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        }
                        
                        DropdownMenu(
                            expanded = showLanguageFilter,
                            onDismissRequest = { showLanguageFilter = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.voice_all_languages)) },
                                onClick = {
                                    selectedLanguageFilter = null
                                    showLanguageFilter = false
                                }
                            )
                            supportedLanguages.forEach { language ->
                                DropdownMenuItem(
                                    text = { Text(localizedLocaleDisplayName(language)) },
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

            if (ttsEngine == TtsEngine.GOOGLE_CLOUD) {
                GoogleModelFilterChips(
                    models = availableGoogleModels,
                    selected = googleModelFilter,
                    onSelected = { googleModelFilter = it },
                )
                Spacer(Modifier.height(8.dp))
            }
            
            // Show filtered voices or empty message
            if (filteredAzureVoices.isEmpty()) {
                Text(
                    stringResource(
                        if (ttsEngine == TtsEngine.GOOGLE_CLOUD) R.string.voice_no_google_match
                        else R.string.voice_no_azure_match,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                Text(
                    text = if (selectedLanguageFilter != null) {
                        stringResource(
                            if (ttsEngine == TtsEngine.GOOGLE_CLOUD) R.string.voice_google_title_with_lang
                            else R.string.voice_azure_title_with_lang,
                            selectedLanguageFilter ?: "",
                        )
                    } else {
                        stringResource(
                            if (ttsEngine == TtsEngine.GOOGLE_CLOUD) R.string.voice_google_title
                            else R.string.voice_azure_title,
                        )
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filteredAzureVoices) { voice ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        try {
                                            val currentFilter = selectedLanguageFilter
                                            val voiceToSelect = voice.withPreferredSupportedLanguage(
                                                currentFilter ?: preferredLanguage,
                                            )
                                            withContext(Dispatchers.IO) { useCase.select(voiceToSelect) }
                                            OperationalLogger.info("voice_selection.save", "succeeded")
                                            selected = voiceToSelect
                                        } catch (t: Throwable) {
                                            OperationalLogger.warn(
                                                operation = "voice_selection.save",
                                                outcome = "failed",
                                                exceptionClass = t.loggingClassName(),
                                            )
                                        }
                                    }
                                }
                                .padding(vertical = 4.dp),
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
                                            stringResource(R.string.voice_selected),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                
                                IconButton(onClick = {
                                    // Settings button for individual voice - placeholder
                                }) {
                                    Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.voice_settings))
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
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_back)) }
            Button(onClick = onNext) { Text(stringResource(R.string.common_continue)) }
        }
    }
}
