package io.github.jdreioe.wingmate.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jdreioe.wingmate.application.FeatureUsageEvents
import io.github.jdreioe.wingmate.domain.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal sealed interface LanguageSelectionUiState {
    data object Loading : LanguageSelectionUiState
    data object LoadFailed : LanguageSelectionUiState

    /**
     * Choices show immediately; if saving fails they return to the persisted values and
     * [saveFailed] explains why. [secondary] is blank when no secondary language is used.
     */
    data class Ready(
        val languages: List<String>,
        val primary: String,
        val secondary: String,
        val voiceIsMultilingual: Boolean,
        val saveFailed: Boolean = false,
    ) : LanguageSelectionUiState {
        val usesSecondary: Boolean get() = voiceIsMultilingual && secondary.isNotBlank() && secondary != primary
    }
}

internal sealed interface LanguageSelectionAction {
    /** Sent each time the page is shown; the selected voice may have changed. */
    data object Load : LanguageSelectionAction
    data class PrimarySelected(val language: String) : LanguageSelectionAction
    data class SecondaryToggled(val enabled: Boolean) : LanguageSelectionAction
    data class SecondarySelected(val language: String) : LanguageSelectionAction
}

internal class LanguageSelectionViewModel(
    private val operations: VoiceSelectionOperations,
) : ViewModel() {
    private val mutableState = MutableStateFlow<LanguageSelectionUiState>(LanguageSelectionUiState.Loading)
    val state: StateFlow<LanguageSelectionUiState> = mutableState.asStateFlow()

    private var loadJob: Job? = null

    fun onAction(action: LanguageSelectionAction) {
        when (action) {
            LanguageSelectionAction.Load -> load()
            is LanguageSelectionAction.PrimarySelected -> save(Target.Primary, action.language)
            is LanguageSelectionAction.SecondarySelected -> save(Target.Secondary, action.language)
            is LanguageSelectionAction.SecondaryToggled -> {
                val ready = state.value as? LanguageSelectionUiState.Ready ?: return
                operations.reportEvent(
                    FeatureUsageEvents.SECONDARY_LANGUAGE_TOGGLED,
                    "enabled" to action.enabled.toString(),
                    "source" to "language_selection",
                )
                val secondary = if (action.enabled) {
                    ready.languages.firstOrNull { it != ready.primary } ?: ready.languages.firstOrNull() ?: ready.primary
                } else {
                    ""
                }
                save(Target.Secondary, secondary)
            }
        }
    }

    private fun load() {
        loadJob?.cancel()
        mutableState.value = LanguageSelectionUiState.Loading
        loadJob = viewModelScope.launch {
            mutableState.value = try {
                val settings = operations.settings()
                val voiceLanguages = operations.selectedVoice()?.supportedLanguages.orEmpty()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                val languages = voiceLanguages
                    .ifEmpty { listOf(settings.primaryLanguage, settings.secondaryLanguage, "en-US") }
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                    .sorted()
                LanguageSelectionUiState.Ready(
                    languages = languages,
                    primary = settings.primaryLanguage,
                    secondary = settings.secondaryLanguage,
                    voiceIsMultilingual = voiceLanguages.size > 1,
                )
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                LanguageSelectionUiState.LoadFailed
            }
        }
    }

    private enum class Target(val metadata: String) { Primary("primary"), Secondary("secondary") }

    private fun save(target: Target, language: String) {
        val before = state.value as? LanguageSelectionUiState.Ready ?: return
        updateReady {
            when (target) {
                Target.Primary -> it.copy(primary = language, saveFailed = false)
                Target.Secondary -> it.copy(secondary = language, saveFailed = false)
            }
        }
        viewModelScope.launch {
            try {
                operations.updateSettings {
                    when (target) {
                        Target.Primary -> it.copy(primaryLanguage = language)
                        Target.Secondary -> it.copy(secondaryLanguage = language)
                    }
                }
                operations.reportEvent(
                    FeatureUsageEvents.LANGUAGE_UPDATED,
                    "target" to target.metadata,
                    "value" to language,
                )
                if (target == Target.Primary) {
                    operations.selectedVoice()?.let { operations.selectVoice(it.copy(selectedLanguage = language)) }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                val persisted: Settings? = try {
                    operations.settings()
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    null
                }
                updateReady { ready ->
                    ready.copy(
                        primary = persisted?.primaryLanguage ?: before.primary,
                        secondary = persisted?.secondaryLanguage ?: before.secondary,
                        saveFailed = true,
                    )
                }
            }
        }
    }

    private fun updateReady(transform: (LanguageSelectionUiState.Ready) -> LanguageSelectionUiState.Ready) {
        mutableState.update { current -> if (current is LanguageSelectionUiState.Ready) transform(current) else current }
    }
}
