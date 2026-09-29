package io.github.jdreioe.wingmate.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jdreioe.wingmate.application.FeatureUsageEvents
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.application.reportEvent
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.withPreferredSupportedLanguage
import io.github.jdreioe.wingmate.infrastructure.SystemVoiceProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Data access shared by the voice and language pages in Settings and Welcome. */
internal interface VoiceSelectionOperations {
    suspend fun settings(): Settings
    suspend fun updateSettings(transform: (Settings) -> Settings)
    suspend fun systemVoices(): List<Voice>
    suspend fun refreshCloudVoices(engine: TtsEngine): List<Voice>
    suspend fun cachedVoices(engine: TtsEngine): List<Voice>
    suspend fun selectedVoice(): Voice?
    suspend fun selectVoice(voice: Voice)
    fun reportEvent(event: String, vararg metadata: Pair<String, String>)
}

internal class DefaultVoiceSelectionOperations(
    private val voiceUseCase: VoiceUseCase,
    private val settingsUseCase: SettingsUseCase,
    private val systemVoiceProvider: SystemVoiceProvider?,
    private val featureUsageReporter: FeatureUsageReporter,
) : VoiceSelectionOperations {
    override suspend fun settings(): Settings = withContext(Dispatchers.Default) { settingsUseCase.get() }

    override suspend fun updateSettings(transform: (Settings) -> Settings) {
        withContext(Dispatchers.Default) { settingsUseCase.update(transform(settingsUseCase.get())) }
    }

    override suspend fun systemVoices(): List<Voice> =
        systemVoiceProvider?.getSystemVoices() ?: listOf(
            Voice(name = "system-default", displayName = "System Default", primaryLanguage = "en-US", gender = "Unknown")
        )

    override suspend fun refreshCloudVoices(engine: TtsEngine): List<Voice> = withContext(Dispatchers.Default) {
        if (engine == TtsEngine.GOOGLE_CLOUD) voiceUseCase.refreshFromGoogle() else voiceUseCase.refreshFromAzure()
    }

    override suspend fun cachedVoices(engine: TtsEngine): List<Voice> =
        withContext(Dispatchers.Default) { voiceUseCase.listForEngine(engine) }

    override suspend fun selectedVoice(): Voice? = withContext(Dispatchers.Default) { voiceUseCase.selected() }

    override suspend fun selectVoice(voice: Voice) {
        withContext(Dispatchers.Default) { voiceUseCase.select(voice) }
    }

    override fun reportEvent(event: String, vararg metadata: Pair<String, String>) {
        featureUsageReporter.reportEvent(event, *metadata)
    }
}

internal sealed interface VoiceSelectionUiState {
    data object Loading : VoiceSelectionUiState
    data object LoadFailed : VoiceSelectionUiState

    /** [saveFailed] keeps the list visible so the user can try another voice or retry. */
    data class Ready(
        val engine: TtsEngine,
        val voices: List<Voice>,
        val languages: List<String>,
        val selected: Voice?,
        val preferredLanguage: String,
        val saveFailed: Boolean = false,
    ) : VoiceSelectionUiState
}

internal sealed interface VoiceSelectionAction {
    /** Sent each time the page is shown; reloads because the engine may have changed. */
    data object Load : VoiceSelectionAction

    /** [filterLanguage] is the language chip in use, preferred when the voice supports several. */
    data class VoiceSelected(val voice: Voice, val filterLanguage: String?) : VoiceSelectionAction
    data class VoiceSettingsSaved(val voice: Voice) : VoiceSelectionAction

    /** Usage reporting only; filtering itself is local UI state. */
    data class FilterApplied(val filter: String, val cleared: Boolean) : VoiceSelectionAction
}

internal sealed interface VoiceSelectionEvent {
    data object VoiceChosen : VoiceSelectionEvent
}

internal class VoiceSelectionViewModel(
    private val operations: VoiceSelectionOperations,
) : ViewModel() {
    private val mutableState = MutableStateFlow<VoiceSelectionUiState>(VoiceSelectionUiState.Loading)
    val state: StateFlow<VoiceSelectionUiState> = mutableState.asStateFlow()

    private val mutableEvents = Channel<VoiceSelectionEvent>(Channel.BUFFERED)
    val events: Flow<VoiceSelectionEvent> = mutableEvents.receiveAsFlow()

    private var loadJob: Job? = null

    fun onAction(action: VoiceSelectionAction) {
        when (action) {
            VoiceSelectionAction.Load -> load()
            is VoiceSelectionAction.VoiceSelected -> select(action.voice, action.filterLanguage)
            is VoiceSelectionAction.VoiceSettingsSaved -> saveVoiceSettings(action.voice)
            is VoiceSelectionAction.FilterApplied -> operations.reportEvent(
                FeatureUsageEvents.VOICE_FILTER_APPLIED,
                "filter" to action.filter,
                "value" to if (action.cleared) "all" else "selected",
            )
        }
    }

    private fun load() {
        loadJob?.cancel()
        mutableState.value = VoiceSelectionUiState.Loading
        loadJob = viewModelScope.launch {
            mutableState.value = try {
                val settings = operations.settings()
                val engine = settings.ttsEngine
                val voices = if (engine == TtsEngine.SYSTEM) operations.systemVoices() else cloudVoices(engine)
                VoiceSelectionUiState.Ready(
                    engine = engine,
                    voices = voices,
                    languages = voices.languages(engine),
                    selected = operations.selectedVoice(),
                    preferredLanguage = settings.primaryLanguage,
                )
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                VoiceSelectionUiState.LoadFailed
            }
        }
    }

    /** Fresh cloud voices merged with the cached catalog; only fails when nothing is left to show. */
    private suspend fun cloudVoices(engine: TtsEngine): List<Voice> {
        var refreshFailed = false
        val fromCloud = try {
            operations.refreshCloudVoices(engine)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            refreshFailed = true
            emptyList()
        }
        val voices = (fromCloud + operations.cachedVoices(engine)).distinctBy { it.name }
        check(voices.isNotEmpty() || !refreshFailed) { "No cached voices were available after refresh failed" }
        return voices
    }

    private fun select(voice: Voice, filterLanguage: String?) {
        val ready = state.value as? VoiceSelectionUiState.Ready ?: return
        viewModelScope.launch {
            updateReady { it.copy(saveFailed = false) }
            try {
                val chosen = if (ready.engine == TtsEngine.SYSTEM) voice else {
                    voice.withPreferredSupportedLanguage(filterLanguage ?: ready.preferredLanguage)
                }
                operations.selectVoice(chosen)
                val primary = if (ready.engine == TtsEngine.SYSTEM) {
                    chosen.primaryLanguage.orEmpty()
                } else {
                    chosen.selectedLanguage.ifBlank { chosen.primaryLanguage.orEmpty() }
                }
                if (primary.isNotBlank()) operations.updateSettings { it.copy(primaryLanguage = primary) }
                updateReady { it.copy(selected = chosen) }
                mutableEvents.send(VoiceSelectionEvent.VoiceChosen)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                updateReady { it.copy(saveFailed = true) }
            }
        }
    }

    private fun saveVoiceSettings(voice: Voice) {
        val ready = state.value as? VoiceSelectionUiState.Ready ?: return
        viewModelScope.launch {
            updateReady { it.copy(saveFailed = false) }
            try {
                operations.selectVoice(voice)
                val primary = voice.selectedLanguage.ifBlank { voice.primaryLanguage.orEmpty() }
                if (primary.isNotBlank()) operations.updateSettings { it.copy(primaryLanguage = primary) }
                val voices = (operations.refreshCloudVoices(ready.engine) + operations.cachedVoices(ready.engine))
                    .distinctBy { it.name }
                val selected = operations.selectedVoice()
                updateReady { it.copy(voices = voices, languages = voices.languages(it.engine), selected = selected) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                updateReady { it.copy(saveFailed = true) }
            }
        }
    }

    private fun updateReady(transform: (VoiceSelectionUiState.Ready) -> VoiceSelectionUiState.Ready) {
        mutableState.update { current -> if (current is VoiceSelectionUiState.Ready) transform(current) else current }
    }
}

private fun List<Voice>.languages(engine: TtsEngine): List<String> =
    if (engine == TtsEngine.SYSTEM) {
        mapNotNull { it.primaryLanguage }
    } else {
        flatMap { voice -> listOfNotNull(voice.primaryLanguage) + voice.supportedLanguages.orEmpty() }
    }.distinct().sorted()
