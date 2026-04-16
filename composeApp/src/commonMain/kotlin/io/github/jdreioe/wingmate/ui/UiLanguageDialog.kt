package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.application.FeatureUsageEvents
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.application.reportEvent
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.domain.Settings
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import wingmatekmp.composeapp.generated.resources.Res
import wingmatekmp.composeapp.generated.resources.common_cancel
import wingmatekmp.composeapp.generated.resources.common_save
import wingmatekmp.composeapp.generated.resources.language_all
import wingmatekmp.composeapp.generated.resources.language_dialog_title
import wingmatekmp.composeapp.generated.resources.language_filter_code
import wingmatekmp.composeapp.generated.resources.language_filter_languages
import wingmatekmp.composeapp.generated.resources.language_filter_region
import wingmatekmp.composeapp.generated.resources.language_no_available
import wingmatekmp.composeapp.generated.resources.language_no_match
import wingmatekmp.composeapp.generated.resources.language_primary
import wingmatekmp.composeapp.generated.resources.language_search_label
import wingmatekmp.composeapp.generated.resources.language_secondary

@Composable
fun UiLanguageDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    openPrimaryMenuInitially: Boolean = false
) {
    if (!show) return
    val voiceUseCase = koinInject<VoiceUseCase>()
    val settingsUseCase = koinInject<SettingsUseCase>()
    val featureUsageReporter = koinInject<FeatureUsageReporter>()
    val scope = rememberCoroutineScope()
    val allLabel = stringResource(Res.string.language_all)
    val noLanguagesAvailableLabel = stringResource(Res.string.language_no_available)
    val noLanguagesMatchLabel = stringResource(Res.string.language_no_match)

    var available by remember { mutableStateOf<List<String>>(emptyList()) }
    var filter by remember { mutableStateOf("") }
    var languageCodeFilter by remember { mutableStateOf<String?>(null) }
    var regionFilter by remember { mutableStateOf<String?>(null) }
    var primary by remember { mutableStateOf("en-US") }
    var secondary by remember { mutableStateOf("en-US") }
    var openPrimaryMenuRequest by remember(openPrimaryMenuInitially) {
        mutableStateOf(openPrimaryMenuInitially)
    }

    LaunchedEffect(Unit) {
        // load current settings and selected voice
        val settings = runCatching { settingsUseCase.get() }.getOrNull() ?: Settings()
        primary = settings.primaryLanguage
        secondary = settings.secondaryLanguage
        val sel = runCatching { voiceUseCase.selected() }.getOrNull()
        available = (sel?.supportedLanguages ?: emptyList())
            .ifEmpty { listOf(settings.primaryLanguage, settings.secondaryLanguage, "en-US") }
            .distinct()
    }

    val normalizedAvailable = remember(available) {
        available
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
    }

    val languageCodeOptions = remember(normalizedAvailable) {
        normalizedAvailable
            .map { languageCodePart(it).uppercase() }
            .distinct()
            .sorted()
    }

    val regionOptions = remember(normalizedAvailable) {
        normalizedAvailable
            .mapNotNull { regionCodePart(it)?.uppercase() }
            .distinct()
            .sorted()
    }

    val queryTerms = remember(filter) {
        filter
            .trim()
            .lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
    }

    val filteredLanguages = remember(normalizedAvailable, queryTerms, languageCodeFilter, regionFilter) {
        normalizedAvailable.filter { lang ->
            val codePart = languageCodePart(lang)
            val regionPart = regionCodePart(lang)

            val matchesCodeFilter = languageCodeFilter == null || codePart.equals(languageCodeFilter, ignoreCase = true)
            val matchesRegionFilter = regionFilter == null || (regionPart?.equals(regionFilter, ignoreCase = true) == true)
            val matchesSearch = queryTerms.all { term ->
                lang.contains(term, ignoreCase = true) ||
                    codePart.contains(term, ignoreCase = true) ||
                    (regionPart?.contains(term, ignoreCase = true) == true)
            }

            matchesCodeFilter && matchesRegionFilter && matchesSearch
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.language_dialog_title)) },
        text = {
            val scrollState = rememberScrollState()
            Column(Modifier.fillMaxWidth().verticalScroll(scrollState)) {
                Text(stringResource(Res.string.language_filter_languages), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                val showKeyboard = rememberShowKeyboardOnFocus()
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text(stringResource(Res.string.language_search_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().then(showKeyboard)
                )
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LanguageFilterMenu(
                        label = stringResource(Res.string.language_filter_code),
                        selected = languageCodeFilter,
                        allLabel = allLabel,
                        options = languageCodeOptions,
                        onSelect = { languageCodeFilter = it },
                        modifier = Modifier.weight(1f)
                    )
                    LanguageFilterMenu(
                        label = stringResource(Res.string.language_filter_region),
                        selected = regionFilter,
                        allLabel = allLabel,
                        options = regionOptions,
                        onSelect = { regionFilter = it },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(12.dp))

                Text(stringResource(Res.string.language_primary))
                Spacer(Modifier.height(8.dp))
                LanguageMenu(
                    available = filteredLanguages,
                    selected = primary,
                    forceExpand = openPrimaryMenuRequest,
                    onForceExpandHandled = { openPrimaryMenuRequest = false },
                    emptyLabel = if (normalizedAvailable.isEmpty()) noLanguagesAvailableLabel else noLanguagesMatchLabel,
                    onSelect = { sel ->
                        primary = sel
                        // persist immediately
                        scope.launch {
                            try {
                                val current = runCatching { settingsUseCase.get() }.getOrNull() ?: Settings()
                                val updated = current.copy(primaryLanguage = sel)
                                settingsUseCase.update(updated)
                                println("Saved primaryLanguage='$sel'")
                                featureUsageReporter.reportEvent(
                                    FeatureUsageEvents.LANGUAGE_UPDATED,
                                    "target" to "primary",
                                    "value" to sel
                                )
                                // Also update selected voice's selectedLanguage to match immediately
                                try {
                                    val vuse = runCatching { voiceUseCase.selected() }.getOrNull()
                                    if (vuse != null) {
                                        val updatedVoice = vuse.copy(selectedLanguage = sel)
                                        runCatching { voiceUseCase.select(updatedVoice) }.onSuccess {
                                            println("Updated selected voice '${vuse.name}' selectedLanguage='$sel'")
                                        }.onFailure { t ->
                                            println("Failed to persist selected voice language: $t")
                                        }
                                    }
                                } catch (t: Throwable) {
                                    println("Error while updating selected voice language: $t")
                                }
                            } catch (t: Throwable) {
                                println("Failed saving primary language: $t")
                            }
                        }
                    }
                )
                Spacer(Modifier.height(12.dp))

                Text(stringResource(Res.string.language_secondary))
                Spacer(Modifier.height(8.dp))
                LanguageMenu(
                    available = filteredLanguages,
                    selected = secondary,
                    emptyLabel = if (normalizedAvailable.isEmpty()) noLanguagesAvailableLabel else noLanguagesMatchLabel,
                    onSelect = { sel ->
                        secondary = sel
                        // persist immediately
                        scope.launch {
                            try {
                                val current = runCatching { settingsUseCase.get() }.getOrNull() ?: Settings()
                                val updated = current.copy(secondaryLanguage = sel)
                                settingsUseCase.update(updated)
                                println("Saved secondaryLanguage='$sel'")
                                featureUsageReporter.reportEvent(
                                    FeatureUsageEvents.LANGUAGE_UPDATED,
                                    "target" to "secondary",
                                    "value" to sel
                                )
                            } catch (t: Throwable) {
                                println("Failed saving secondary language: $t")
                            }
                        }
                    }
                )
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
                                    println("Updated selected voice '${vuse.name}' selectedLanguage='$primary'")
                                }.onFailure { t ->
                                    println("Failed to update selected voice language: $t")
                                }
                            }
                        } catch (t: Throwable) {
                            println("Error while updating selected voice language: $t")
                        }
                    } catch (_: Throwable) {
                        // ignore
                    }
                    onDismiss()
                }
            }) { Text(stringResource(Res.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } }
    )
}

@Composable
private fun LanguageFilterMenu(
    label: String,
    selected: String?,
    allLabel: String,
    options: List<String>,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selected ?: allLabel)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .widthIn(min = 160.dp, max = 280.dp)
                    .heightIn(max = 280.dp)
            ) {
                DropdownMenuItem(
                    text = { Text(allLabel) },
                    onClick = {
                        onSelect(null)
                        expanded = false
                    }
                )
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun LanguageMenu(
    available: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    forceExpand: Boolean = false,
    onForceExpandHandled: () -> Unit = {},
    emptyLabel: String = "No languages found"
) {
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(forceExpand) {
        if (forceExpand) {
            expanded = true
            onForceExpandHandled()
        }
    }

    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .widthIn(min = 220.dp, max = 420.dp)
                .heightIn(max = 300.dp)
        ) {
            if (available.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(emptyLabel) },
                    onClick = {},
                    enabled = false
                )
            } else {
                available.forEach { lang ->
                    DropdownMenuItem(text = { Text(lang) }, onClick = { onSelect(lang); expanded = false })
                }
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
