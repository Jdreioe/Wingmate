package io.github.jdreioe.wingmate.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.domain.PiperVoicePack
import io.github.jdreioe.wingmate.domain.PiperVoiceState
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.piperVoiceKey
import io.github.jdreioe.wingmate.infrastructure.AndroidPiperSynthesizer
import io.github.jdreioe.wingmate.infrastructure.PiperVoiceInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Data access for the Piper voice list; faked in tests. */
internal interface PiperVoiceOperations {
    val catalog: List<PiperVoicePack>
    val sharedDataBytes: Long
    val states: StateFlow<Map<String, PiperVoiceState>>
    suspend fun refresh()
    fun download(key: String)
    suspend fun cancel(key: String)
    suspend fun delete(key: String)

    /** Verifies and loads the voice; throws when it cannot be used. */
    suspend fun prepare(key: String)
    suspend fun selectedVoice(): Voice?

    /** Saves [voice] and makes its language the primary language. */
    suspend fun select(voice: Voice)
    suspend fun primaryLanguage(): String

    /** Streams [url] and returns when it finishes; throws when it cannot play. Cancelling stops it. */
    suspend fun playSample(url: String)
}

internal class DefaultPiperVoiceOperations(
    private val installer: PiperVoiceInstaller,
    private val synthesizer: AndroidPiperSynthesizer,
    private val voiceUseCase: VoiceUseCase,
    private val settingsUseCase: SettingsUseCase,
) : PiperVoiceOperations {
    override val catalog: List<PiperVoicePack> get() = installer.catalog
    override val sharedDataBytes: Long get() = installer.sharedDataBytes
    override val states: StateFlow<Map<String, PiperVoiceState>> get() = installer.states
    override suspend fun refresh() = installer.refresh()
    override fun download(key: String) = installer.download(key)
    override suspend fun cancel(key: String) = installer.cancel(key)

    override suspend fun delete(key: String) {
        synthesizer.unload(key)
        installer.delete(key)
    }

    override suspend fun prepare(key: String) = synthesizer.prepare(key)

    override suspend fun selectedVoice(): Voice? = withContext(Dispatchers.Default) { voiceUseCase.selected() }

    override suspend fun select(voice: Voice) {
        withContext(Dispatchers.Default) {
            voiceUseCase.select(voice)
            val language = voice.primaryLanguage ?: return@withContext
            settingsUseCase.update(settingsUseCase.get().copy(primaryLanguage = language))
        }
    }

    override suspend fun primaryLanguage(): String = withContext(Dispatchers.Default) { settingsUseCase.get().primaryLanguage }

    override suspend fun playSample(url: String) = suspendCancellableCoroutine { continuation ->
        val player = MediaPlayer()
        continuation.invokeOnCancellation { player.release() }
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        player.setOnPreparedListener { it.start() }
        player.setOnCompletionListener {
            it.release()
            if (continuation.isActive) continuation.resumeWith(Result.success(Unit))
        }
        player.setOnErrorListener { mp, what, extra ->
            mp.release()
            if (continuation.isActive) {
                continuation.resumeWith(Result.failure(IllegalStateException("sample playback failed: $what/$extra")))
            }
            true
        }
        try {
            player.setDataSource(url)
            player.prepareAsync()
        } catch (failure: Exception) {
            player.release()
            continuation.resumeWith(Result.failure(failure))
        }
    }
}

/** One catalog voice with its download state on this device. */
internal data class PiperVoiceRow(val pack: PiperVoicePack, val state: PiperVoiceState)

internal sealed interface PiperVoicesUiState {
    data object Loading : PiperVoicesUiState

    data class Ready(
        /** Voices with files on this device: installed, downloading, or incomplete. */
        val onDevice: List<PiperVoiceRow>,
        /** Voices not yet downloaded, in [languageFilter] when one is set. */
        val available: List<PiperVoiceRow>,
        val languages: List<String>,
        val languageFilter: String?,
        val selectedKey: String?,
        /** Shared phonemizer data downloaded with the first voice. */
        val sharedDataBytes: Long,
        /** The voice being verified and loaded after the user chose it. */
        val preparingKey: String? = null,
        /** The voice that failed to load; it should be deleted and downloaded again. */
        val unusableKey: String? = null,
        val selectFailed: Boolean = false,
        /** The voice whose sample is playing. */
        val testingKey: String? = null,
        /** The voice whose sample could not be played, usually because there is no connection. */
        val testFailedKey: String? = null,
    ) : PiperVoicesUiState
}

internal sealed interface PiperVoicesAction {
    data class LanguageFiltered(val language: String?) : PiperVoicesAction
    data class Download(val key: String) : PiperVoicesAction
    data class Cancel(val key: String) : PiperVoicesAction
    data class Delete(val key: String) : PiperVoicesAction
    data class Use(val key: String) : PiperVoicesAction

    /** Plays the voice's sample before download, or stops it when it is already playing. */
    data class Test(val key: String) : PiperVoicesAction
}

internal sealed interface PiperVoicesEvent {
    data object VoiceChosen : PiperVoicesEvent
}

/**
 * Browses Wingmate's Piper catalog: test, download, cancel, resume, delete, and choose voices.
 * Download progress comes straight from the installer, so it survives leaving the page.
 */
internal class PiperVoicesViewModel(private val operations: PiperVoiceOperations) : ViewModel() {
    private val filter = MutableStateFlow<String?>(null)
    private val selection = MutableStateFlow(SelectionState())
    private val sample = MutableStateFlow(SampleState())
    private var sampleJob: Job? = null
    private val mutableState = MutableStateFlow<PiperVoicesUiState>(PiperVoicesUiState.Loading)
    val state: StateFlow<PiperVoicesUiState> = mutableState.asStateFlow()

    private val mutableEvents = Channel<PiperVoicesEvent>(Channel.BUFFERED)
    val events: Flow<PiperVoicesEvent> = mutableEvents.receiveAsFlow()

    private val languages = operations.catalog.map { it.languageTag }.distinct().sorted()

    init {
        viewModelScope.launch {
            operations.refresh()
            val primary = operations.primaryLanguage()
            filter.value = languages.firstOrNull { it == primary }
                ?: languages.firstOrNull { it.substringBefore('-') == primary.substringBefore('-') }
            selection.value = SelectionState(selectedKey = operations.selectedVoice()?.piperVoiceKey)
            combine(operations.states, filter, selection, sample, ::readyState).collect { mutableState.value = it }
        }
    }

    fun onAction(action: PiperVoicesAction) {
        when (action) {
            is PiperVoicesAction.LanguageFiltered -> filter.value = action.language
            is PiperVoicesAction.Download -> {
                clearNotices()
                operations.download(action.key)
            }
            is PiperVoicesAction.Cancel -> viewModelScope.launch { operations.cancel(action.key) }
            is PiperVoicesAction.Delete -> viewModelScope.launch {
                clearNotices()
                operations.delete(action.key)
            }
            is PiperVoicesAction.Use -> use(action.key)
            is PiperVoicesAction.Test -> test(action.key)
        }
    }

    /** Stops a playing sample, for example when the page closes. */
    fun stopSample() {
        sampleJob?.cancel()
        sampleJob = null
        sample.value = SampleState()
    }

    private fun test(key: String) {
        val playing = sample.value.testingKey
        stopSample()
        if (playing == key) return
        val pack = operations.catalog.firstOrNull { it.key == key } ?: return
        sample.value = SampleState(testingKey = key)
        sampleJob = viewModelScope.launch {
            sample.value = try {
                operations.playSample(pack.sampleUrl)
                SampleState()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                SampleState(failedKey = key)
            }
        }
    }

    private fun use(key: String) {
        val pack = operations.catalog.firstOrNull { it.key == key } ?: return
        if (selection.value.preparingKey != null) return
        selection.value = selection.value.copy(preparingKey = key, unusableKey = null, selectFailed = false)
        viewModelScope.launch {
            try {
                operations.prepare(key)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                selection.value = selection.value.copy(preparingKey = null, unusableKey = key)
                return@launch
            }
            try {
                operations.select(pack.toVoice())
                selection.value = SelectionState(selectedKey = key)
                mutableEvents.send(PiperVoicesEvent.VoiceChosen)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                selection.value = selection.value.copy(preparingKey = null, selectFailed = true)
            }
        }
    }

    private fun clearNotices() {
        selection.value = selection.value.copy(unusableKey = null, selectFailed = false)
    }

    private fun readyState(
        states: Map<String, PiperVoiceState>,
        languageFilter: String?,
        selection: SelectionState,
        sample: SampleState,
    ): PiperVoicesUiState.Ready {
        val rows = operations.catalog.map { PiperVoiceRow(it, states[it.key] ?: PiperVoiceState.NotInstalled) }
        return PiperVoicesUiState.Ready(
            onDevice = rows.filter { it.state != PiperVoiceState.NotInstalled },
            available = rows.filter {
                it.state == PiperVoiceState.NotInstalled && (languageFilter == null || it.pack.languageTag == languageFilter)
            },
            languages = languages,
            languageFilter = languageFilter,
            selectedKey = selection.selectedKey,
            sharedDataBytes = operations.sharedDataBytes,
            preparingKey = selection.preparingKey,
            unusableKey = selection.unusableKey,
            selectFailed = selection.selectFailed,
            testingKey = sample.testingKey,
            testFailedKey = sample.failedKey,
        )
    }

    private data class SampleState(val testingKey: String? = null, val failedKey: String? = null)

    private data class SelectionState(
        val selectedKey: String? = null,
        val preparingKey: String? = null,
        val unusableKey: String? = null,
        val selectFailed: Boolean = false,
    )
}
