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
import io.github.jdreioe.wingmate.domain.SpeechPlaybackStatus
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.withPreferredSupportedLanguage
import io.github.jdreioe.wingmate.infrastructure.SystemVoiceProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    /** Plays [text] with [voice] on exactly [engine], without History or caching; throws on failure. */
    suspend fun previewVoice(voice: Voice, engine: TtsEngine, text: String)
    suspend fun stopPreview()
    fun reportEvent(event: String, vararg metadata: Pair<String, String>)
}

internal class DefaultVoiceSelectionOperations(
    private val voiceUseCase: VoiceUseCase,
    private val settingsUseCase: SettingsUseCase,
    private val systemVoiceProvider: SystemVoiceProvider?,
    private val featureUsageReporter: FeatureUsageReporter,
    private val speechService: SpeechService,
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
        when (engine) {
            TtsEngine.ELEVENLABS -> voiceUseCase.refreshFromElevenLabs()
            TtsEngine.GOOGLE_CLOUD -> voiceUseCase.refreshFromGoogle()
            else -> voiceUseCase.refreshFromAzure()
        }
    }

    override suspend fun cachedVoices(engine: TtsEngine): List<Voice> =
        withContext(Dispatchers.Default) { voiceUseCase.listForEngine(engine) }

    override suspend fun selectedVoice(): Voice? = withContext(Dispatchers.Default) { voiceUseCase.selected() }

    override suspend fun selectVoice(voice: Voice) {
        withContext(Dispatchers.Default) { voiceUseCase.select(voice) }
    }

    override suspend fun previewVoice(voice: Voice, engine: TtsEngine, text: String) {
        speechService.speakPreview(text, voice, engine)
    }

    override suspend fun stopPreview() {
        withContext(Dispatchers.IO) { speechService.stop() }
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
        val preview: VoicePreview = VoicePreview.Idle,
    ) : VoiceSelectionUiState
}

/** Hearing a voice from the list before choosing it. Keyed by voice name. */
internal sealed interface VoicePreview {
    data object Idle : VoicePreview
    data class Playing(val voiceName: String) : VoicePreview
    data class Failed(val voiceName: String) : VoicePreview
}

internal sealed interface VoiceSelectionAction {
    /** Sent each time the page is shown; reloads because the engine may have changed. */
    data object Load : VoiceSelectionAction

    /** [filterLanguage] is the language chip in use, preferred when the voice supports several. */
    data class VoiceSelected(val voice: Voice, val filterLanguage: String?) : VoiceSelectionAction
    data class VoiceSettingsSaved(val voice: Voice) : VoiceSelectionAction

    /** Plays [text] with [voice], or stops it if that voice is already playing. */
    data class PreviewToggled(val voice: Voice, val text: String) : VoiceSelectionAction
    /** The page left the screen; a preview must not keep playing over the next one. */
    data object PreviewStopped : VoiceSelectionAction

    /** Usage reporting only; filtering itself is local UI state. */
    data class FilterApplied(val filter: String, val cleared: Boolean) : VoiceSelectionAction
}

internal sealed interface VoiceSelectionEvent {
    data object VoiceChosen : VoiceSelectionEvent
}

/**
 * [engineToSave] lists that engine's voices instead of the saved engine's and saves it
 * together with the chosen voice, so setup never leaves an engine paired with another
 * engine's voice. Null keeps the saved engine (Settings).
 */
internal class VoiceSelectionViewModel(
    private val operations: VoiceSelectionOperations,
    private val engineToSave: TtsEngine? = null,
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
            is VoiceSelectionAction.PreviewToggled -> togglePreview(action.voice, action.text)
            VoiceSelectionAction.PreviewStopped -> stopPreview()
            is VoiceSelectionAction.FilterApplied -> operations.reportEvent(
                FeatureUsageEvents.VOICE_FILTER_APPLIED,
                "filter" to action.filter,
                "value" to if (action.cleared) "all" else "selected",
            )
        }
    }

    /** Bumped on each Load; a choice made during an earlier visit must not advance this one. */
    private var visit = 0

    private fun load() {
        visit++
        while (mutableEvents.tryReceive().isSuccess) Unit
        loadJob?.cancel()
        mutableState.value = VoiceSelectionUiState.Loading
        loadJob = viewModelScope.launch {
            mutableState.value = try {
                val settings = operations.settings()
                val engine = engineToSave ?: settings.ttsEngine
                val voices = when (engine) {
                    TtsEngine.SYSTEM -> operations.systemVoices()
                    // Piper voices are listed by PiperVoicesPage from the downloadable catalog.
                    TtsEngine.PIPER -> emptyList()
                    else -> cloudVoices(engine)
                }
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
        val choiceVisit = visit
        viewModelScope.launch {
            updateReady { it.copy(saveFailed = false) }
            try {
                val chosen = (if (ready.engine == TtsEngine.SYSTEM) voice else {
                    voice.withPreferredSupportedLanguage(filterLanguage ?: ready.preferredLanguage)
                }).withTuningOf(ready.selected)
                saveVoice(chosen, ready.engine)
                updateReady { it.copy(selected = chosen) }
                if (choiceVisit == visit) mutableEvents.send(VoiceSelectionEvent.VoiceChosen)
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
                saveVoice(voice, ready.engine)
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

    /**
     * Saves [voice] with its language and, in setup, the engine it belongs to. Every way of
     * saving a voice goes through here, so as long as both writes succeed, setup never pairs
     * an engine with another engine's voice. (A failed second write is shown as saveFailed.)
     */
    private suspend fun saveVoice(voice: Voice, engine: TtsEngine) {
        operations.selectVoice(voice)
        val primary = if (engine == TtsEngine.SYSTEM) {
            voice.primaryLanguage.orEmpty()
        } else {
            voice.selectedLanguage.ifBlank { voice.primaryLanguage.orEmpty() }
        }
        if (primary.isNotBlank() || engineToSave != null) {
            operations.updateSettings { current ->
                current.copy(
                    ttsEngine = engineToSave ?: current.ttsEngine,
                    primaryLanguage = primary.ifBlank { current.primaryLanguage },
                )
            }
        }
    }

    private var previewJob: Job? = null

    private fun togglePreview(voice: Voice, text: String) {
        val ready = state.value as? VoiceSelectionUiState.Ready ?: return
        val name = voice.name ?: return
        val wasPlaying = (ready.preview as? VoicePreview.Playing)?.voiceName == name
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            runCatching { operations.stopPreview() }.onFailure { if (it is CancellationException) throw it }
            if (wasPlaying) {
                updateReady { it.copy(preview = VoicePreview.Idle) }
                return@launch
            }
            updateReady { it.copy(preview = VoicePreview.Playing(name)) }
            val outcome = try {
                operations.previewVoice(voice.withTuningOf(ready.selected), ready.engine, text)
                VoicePreview.Idle
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                VoicePreview.Failed(name)
            }
            updateReady { it.copy(preview = outcome) }
        }
    }

    private fun stopPreview() {
        val ready = state.value as? VoiceSelectionUiState.Ready
        // Only a preview this list started is stopped, never other speech (e.g. in Settings).
        if (ready?.preview !is VoicePreview.Playing) return
        previewJob?.cancel()
        previewJob = null
        updateReady { it.copy(preview = VoicePreview.Idle) }
        viewModelScope.launch { runCatching { operations.stopPreview() } }
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

/**
 * Plays a preview of [voice]: kept out of History and the audio cache, on exactly [engine] (a
 * failing cloud voice is never replaced by the system voice), and returns only when playback
 * has finished, since the speech service returns once audio starts. Throws if it fails.
 */
internal suspend fun SpeechService.speakPreview(
    text: String,
    voice: Voice?,
    engine: TtsEngine,
    /** Receives the speech request id once playing, so an owner can stop only its own preview. */
    onStarted: (requestId: Long) -> Unit = {},
) {
    withContext(Dispatchers.IO) {
        speakWithoutHistory(
            text = text,
            voice = voice,
            pitch = voice?.pitch,
            rate = voice?.rate,
            cacheAudio = false,
            engine = engine,
        )
        val requestId = playbackState().requestId
        onStarted(requestId)
        var waitedMillis = 0L
        while (isPlaying() && waitedMillis < 120_000) {
            delay(50)
            waitedMillis += 50
        }
        // Past the limit, end this preview rather than leave it talking with no Stop shown.
        if (isPlaying() && playbackState().requestId == requestId) stop()
    }
    check(playbackState().status != SpeechPlaybackStatus.FAILED) { "Preview playback failed" }
}

/**
 * The list's copy of a voice carries no speed or pitch. Choosing (or hearing) the voice that
 * is already saved keeps the speed and pitch set for it; any other voice starts fresh.
 */
internal fun Voice.withTuningOf(saved: Voice?): Voice =
    if (saved == null || saved.name != name) this
    else copy(rate = saved.rate, pitch = saved.pitch, rateForSSML = saved.rateForSSML, pitchForSSML = saved.pitchForSSML)
