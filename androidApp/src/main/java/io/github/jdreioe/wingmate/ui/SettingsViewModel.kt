package io.github.jdreioe.wingmate.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.application.BackupRestoreResult
import io.github.jdreioe.wingmate.application.CompleteBackupManager
import io.github.jdreioe.wingmate.application.EditingAccessController
import io.github.jdreioe.wingmate.application.EditingAccessState
import io.github.jdreioe.wingmate.application.FeatureUsageEvents
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.application.SettingsStateManager
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository
import io.github.jdreioe.wingmate.domain.PronunciationEntry
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.domain.SpeechPolicy
import io.github.jdreioe.wingmate.domain.StartupMode
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.application.BoardSetUseCase
import io.github.jdreioe.wingmate.domain.PointerEmphasisStyle
import io.github.jdreioe.wingmate.domain.WordTypeColorScheme
import io.github.jdreioe.wingmate.domain.obf.BoardActivationBehavior
import io.github.jdreioe.wingmate.domain.obf.BoardReturnBehavior
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.infrastructure.ArasaacDownloadProgress
import io.github.jdreioe.wingmate.infrastructure.ArasaacSymbolDownloadService
import io.github.jdreioe.wingmate.infrastructure.AzureSpeechEndpoint
import io.github.jdreioe.wingmate.infrastructure.AzureSpeechEndpointResult
import io.github.jdreioe.wingmate.platform.FilePicker
import io.github.jdreioe.wingmate.platform.ShareService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

internal enum class SettingsTab { Speech, Display, Accessibility, Privacy, General }

/** One settings destination; replaces the previous three nullable navigation flags. */
internal sealed interface SettingsRoute {
    data object Home : SettingsRoute
    data object PronunciationDictionary : SettingsRoute
    data class Category(val tab: SettingsTab) : SettingsRoute
    data object VoiceSelection : SettingsRoute
    data object LanguageSelection : SettingsRoute
    data object F0Setup : SettingsRoute
    data object GoogleSetup : SettingsRoute
}

internal sealed interface SettingsMessage {
    data class Resource(@param:StringRes val id: Int) : SettingsMessage
    data class Dynamic(val value: String) : SettingsMessage
}

@Stable
internal data class SettingsUiState(
    val route: SettingsRoute = SettingsRoute.Home,
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    /** A settings write failed; the shown values are kept and can be retried. */
    val saveFailed: Boolean = false,
    val settings: Settings = Settings(),
    // Azure credential drafts are transient entry state and deliberately do not
    // survive process death.
    val azureEndpoint: String = "",
    val azureSubscriptionKey: String = "",
    val azureCredentialConfigured: Boolean = false,
    val replacingAzureCredentials: Boolean = false,
    val azureEndpointInvalid: Boolean = false,
    val googleCredentialConfigured: Boolean = false,
    val boardSets: List<ObfBoardSet> = emptyList(),
    val arasaacAvailable: Boolean = false,
    val cachedArasaacSymbols: Int = 0,
    val arasaacProgress: ArasaacDownloadProgress? = null,
    val arasaacDownloadFailed: Boolean = false,
    val arasaacFailedCount: Int = 0,
    val dictionaryEntries: List<PronunciationEntry> = emptyList(),
    val partnerDeviceConnected: Boolean = false,
    val editingAccessDialog: EditingAccessDialogMode? = null,
    val backupWorking: Boolean = false,
    val backupStatus: SettingsMessage? = null,
    val pendingRestorePath: String? = null,
)

internal sealed interface SettingsAction {
    data object Initialize : SettingsAction
    data object RetryLoad : SettingsAction
    data object RetrySave : SettingsAction
    data object BackClicked : SettingsAction

    data class CategorySelected(val tab: SettingsTab) : SettingsAction
    data object PronunciationOpened : SettingsAction
    data object VoiceSelectionOpened : SettingsAction
    data object LanguageSelectionOpened : SettingsAction
    data object F0SetupOpened : SettingsAction
    data object GoogleSetupOpened : SettingsAction
    data object F0SetupCompleted : SettingsAction
    data object GoogleSetupCompleted : SettingsAction

    data class TtsEngineSelected(val engine: TtsEngine) : SettingsAction
    data class VirtualMicChanged(val enabled: Boolean) : SettingsAction
    data class AzureEndpointChanged(val value: String) : SettingsAction
    data class AzureSubscriptionKeyChanged(val value: String) : SettingsAction
    data object ReplaceAzureCredentialsClicked : SettingsAction
    data object GoogleCredentialsCleared : SettingsAction

    data class FontSizeScaleChanged(val value: Float) : SettingsAction
    data class PlaybackIconScaleChanged(val value: Float) : SettingsAction
    data class CategoryChipScaleChanged(val value: Float) : SettingsAction
    data class ButtonScaleChanged(val value: Float) : SettingsAction
    data class InputFieldScaleChanged(val value: Float) : SettingsAction
    data class ShowLabelsChanged(val checked: Boolean) : SettingsAction
    data class ShowSymbolsChanged(val checked: Boolean) : SettingsAction
    data class LabelAtTopChanged(val checked: Boolean) : SettingsAction
    data class BoardShowMessageBarChanged(val checked: Boolean) : SettingsAction
    data class BoardActivationBehaviorChanged(val behavior: BoardActivationBehavior) : SettingsAction
    data class BoardReturnBehaviorChanged(val behavior: BoardReturnBehavior) : SettingsAction
    data class GridColumnsChanged(val columns: Int) : SettingsAction
    data object GridColumnsChangeFinished : SettingsAction
    data class HighContrastModeChanged(val checked: Boolean) : SettingsAction
    data class WordTypeColorsChanged(val enabled: Boolean) : SettingsAction

    data class HoldToSelectChanged(val millis: Long) : SettingsAction
    data object HoldToSelectChangeFinished : SettingsAction
    data class DwellToSelectChanged(val millis: Long) : SettingsAction
    data object DwellToSelectChangeFinished : SettingsAction
    data class SelectionSoundChanged(val checked: Boolean) : SettingsAction
    data class AuditoryFishingChanged(val checked: Boolean) : SettingsAction
    data class SpeechPolicyChanged(val policy: SpeechPolicy) : SettingsAction
    data class SelectionDebounceChanged(val millis: Long) : SettingsAction
    data object SelectionDebounceChangeFinished : SettingsAction
    data class DwellRearmDelayChanged(val millis: Long) : SettingsAction
    data object DwellRearmDelayChangeFinished : SettingsAction
    data class SelectionHighlightChanged(val millis: Long) : SettingsAction
    data object SelectionHighlightChangeFinished : SettingsAction
    data class SelectKeyBindingChanged(val binding: String) : SettingsAction
    data class RestModeKeyBindingChanged(val binding: String) : SettingsAction
    data class PointerEmphasisStyleChanged(val style: PointerEmphasisStyle) : SettingsAction
    data class PointerEmphasisScaleChanged(val scale: Float) : SettingsAction
    data object PointerEmphasisScaleChangeFinished : SettingsAction

    data class HistoryVisibleChanged(val checked: Boolean) : SettingsAction
    data class BoardSetSentenceCachingChanged(val boardSetId: String, val enabled: Boolean) : SettingsAction
    data class UsageLoggingChanged(val checked: Boolean) : SettingsAction
    data class FeatureReportingChanged(val checked: Boolean) : SettingsAction

    data class StartupModeChanged(val mode: StartupMode) : SettingsAction
    data class StartupBoardSetChanged(val boardSetId: String?) : SettingsAction
    data class PartnerWindowChanged(val enabled: Boolean) : SettingsAction
    data object DownloadArasaacClicked : SettingsAction

    data object CreateBackupClicked : SettingsAction
    data object RestoreBackupClicked : SettingsAction
    data object RestoreConfirmed : SettingsAction
    data object RestoreDismissed : SettingsAction

    data object EditingAccessConfigureClicked : SettingsAction
    data object EditingAccessUnlockClicked : SettingsAction
    data object EditingAccessDisableClicked : SettingsAction
    data object EditingAccessLockNowClicked : SettingsAction
    data object EditingAccessDialogDismissed : SettingsAction

    data class DictionaryEntryAdded(
        val word: String,
        val phoneme: String,
        val alphabet: String,
    ) : SettingsAction

    data class DictionaryEntryDeleted(val word: String) : SettingsAction
    data class DictionaryTestRequested(
        val word: String,
        val phoneme: String,
        val alphabet: String,
    ) : SettingsAction
}

internal sealed interface SettingsEvent {
    data object Close : SettingsEvent
}

/**
 * Persistence boundary for the settings presentation model. Keeping it narrow makes
 * state transitions deterministic without replacing the shared settings use cases.
 */
internal interface SettingsOperations {
    /** True when an ARASAAC symbol store is available on this platform. */
    val arasaacAvailable: Boolean
    val partnerDeviceConnected: Flow<Boolean>
    val editingAccessState: StateFlow<EditingAccessState>?

    suspend fun getSettings(): Settings
    suspend fun updateSettings(update: (Settings) -> Settings)
    suspend fun azureStatus(): Pair<String, Boolean>
    suspend fun googleCredentialConfigured(): Boolean
    suspend fun saveAzureCredentials(endpoint: String, subscriptionKey: String): Boolean
    suspend fun clearGoogleCredentials()
    suspend fun listBoardSets(): List<ObfBoardSet>
    suspend fun setSentenceCaching(boardSetId: String, enabled: Boolean): ObfBoardSet?
    suspend fun cachedArasaacSymbolCount(): Int
    suspend fun downloadArasaacSymbols(
        languageTag: String,
        onProgress: (ArasaacDownloadProgress) -> Unit,
    ): ArasaacDownloadProgress

    suspend fun dictionaryEntries(): List<PronunciationEntry>
    suspend fun addDictionaryEntry(entry: PronunciationEntry)
    suspend fun deleteDictionaryEntry(word: String)
    suspend fun speakPronunciation(word: String, phoneme: String, alphabet: String)
    suspend fun guessPronunciation(word: String, languageTag: String): String?

    fun setUsageReportingEnabled(enabled: Boolean)
    fun reportEvent(event: String, metadata: Map<String, String> = emptyMap())

    suspend fun exportBackup(): ByteArray
    fun shareBackupFile(fileName: String, bytes: ByteArray): Boolean
    suspend fun pickRestoreFile(): String?
    suspend fun restoreBackup(path: String): BackupRestoreResult?

    suspend fun refreshEditingAccess()
    fun lockEditingAccess()
}

internal class DefaultSettingsOperations(
    private val configRepo: ConfigRepository?,
    private val settingsUseCase: SettingsUseCase?,
    private val settingsStateManager: SettingsStateManager?,
    private val boardSetUseCase: BoardSetUseCase?,
    private val voiceUseCase: VoiceUseCase?,
    private val speechService: SpeechService?,
    private val pronunciationRepo: PronunciationDictionaryRepository?,
    private val featureUsageReporter: FeatureUsageReporter?,
    private val arasaacDownloader: ArasaacSymbolDownloadService?,
    private val backupManager: CompleteBackupManager?,
    private val filePicker: FilePicker?,
    private val shareService: ShareService?,
    private val editingAccessController: EditingAccessController?,
) : SettingsOperations {
    override val arasaacAvailable: Boolean get() = arasaacDownloader != null
    override val partnerDeviceConnected: Flow<Boolean> =
        PartnerWindowAvailability.deviceConnected
    override val editingAccessState: StateFlow<EditingAccessState>? =
        editingAccessController?.state

    override suspend fun getSettings(): Settings =
        checkNotNull(settingsUseCase) { "Settings are unavailable" }.get()

    override suspend fun updateSettings(update: (Settings) -> Settings) {
        if (settingsStateManager != null) {
            settingsStateManager.updateSettings(update)
        } else {
            val useCase = checkNotNull(settingsUseCase) { "Settings are unavailable" }
            useCase.update(update(useCase.get()))
        }
    }

    override suspend fun azureStatus(): Pair<String, Boolean> {
        val status = configRepo?.getSpeechConfigStatus() ?: return "" to false
        return status.endpoint to status.credentialConfigured
    }

    override suspend fun googleCredentialConfigured(): Boolean =
        configRepo?.getGoogleSpeechConfigStatus()?.credentialConfigured == true

    override suspend fun saveAzureCredentials(endpoint: String, subscriptionKey: String): Boolean {
        val repo = configRepo ?: return false
        return runCatching {
            repo.saveSpeechConfig(
                SpeechServiceConfig(endpoint = endpoint, subscriptionKey = subscriptionKey)
            )
        }.isSuccess
    }

    override suspend fun clearGoogleCredentials() {
        runCatching { configRepo?.clearGoogleSpeechConfig() }
    }

    override suspend fun listBoardSets(): List<ObfBoardSet> =
        checkNotNull(boardSetUseCase) { "Screen storage is unavailable" }.listBoardSets()

    override suspend fun setSentenceCaching(boardSetId: String, enabled: Boolean): ObfBoardSet? =
        boardSetUseCase?.setSentenceCaching(boardSetId, enabled)

    override suspend fun cachedArasaacSymbolCount(): Int =
        runCatching { arasaacDownloader?.cachedCount() ?: 0 }.getOrDefault(0)

    override suspend fun downloadArasaacSymbols(
        languageTag: String,
        onProgress: (ArasaacDownloadProgress) -> Unit,
    ): ArasaacDownloadProgress =
        checkNotNull(arasaacDownloader) { "ARASAAC storage unavailable" }
            .downloadAll(languageTag, onProgress)

    override suspend fun dictionaryEntries(): List<PronunciationEntry> =
        runCatching { pronunciationRepo?.getAll().orEmpty() }.getOrDefault(emptyList())

    override suspend fun addDictionaryEntry(entry: PronunciationEntry) {
        val repo = pronunciationRepo ?: return
        repo.add(entry)
    }

    override suspend fun deleteDictionaryEntry(word: String) {
        val repo = pronunciationRepo ?: return
        repo.delete(word)
    }

    override suspend fun speakPronunciation(word: String, phoneme: String, alphabet: String) {
        val service = speechService ?: return
        val voice = runCatching { voiceUseCase?.selected() }.getOrNull()
        val markup = if (alphabet == "text") {
            "<sub alias=\"$phoneme\">$word</sub>"
        } else {
            "<phoneme alphabet=\"$alphabet\" ph=\"$phoneme\">$word</phoneme>"
        }
        runCatching { service.speak(markup, voice, voice?.pitch, voice?.rate) }
    }

    override suspend fun guessPronunciation(word: String, languageTag: String): String? {
        val service = speechService ?: return null
        val voice = runCatching { voiceUseCase?.selected() }.getOrNull()
        return runCatching {
            service.guessPronunciation(
                word,
                voice?.selectedLanguage ?: voice?.primaryLanguage ?: languageTag,
            )
        }.getOrNull()
    }

    override fun setUsageReportingEnabled(enabled: Boolean) {
        featureUsageReporter?.setEnabled(enabled)
    }

    override fun reportEvent(event: String, metadata: Map<String, String>) {
        featureUsageReporter?.report(event, metadata)
    }

    override suspend fun exportBackup(): ByteArray =
        checkNotNull(backupManager).exportBackup()

    override fun shareBackupFile(fileName: String, bytes: ByteArray): Boolean =
        shareService?.shareFile(fileName, bytes) == true

    override suspend fun pickRestoreFile(): String? =
        filePicker?.pickFile(
            title = "Restore Wingmate backup",
            extensions = listOf("wingmate-backup", "zip"),
        )

    override suspend fun restoreBackup(path: String): BackupRestoreResult? =
        backupManager?.restoreBackup(path)

    override suspend fun refreshEditingAccess() {
        editingAccessController?.refresh()
    }

    override fun lockEditingAccess() {
        editingAccessController?.lock()
    }
}

internal class SettingsViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val operations: SettingsOperations,
) : ViewModel() {
    private val restoredRoute: SettingsRoute = savedStateHandle.get<String>(ROUTE_KEY)
        ?.let { stored ->
            when {
                stored == ROUTE_DICTIONARY -> SettingsRoute.PronunciationDictionary
                stored.startsWith(ROUTE_CATEGORY_PREFIX) -> stored
                    .removePrefix(ROUTE_CATEGORY_PREFIX)
                    .let { name -> SettingsTab.entries.firstOrNull { it.name == name } }
                    ?.let { SettingsRoute.Category(it) }
                else -> null
            }
        }
        ?: SettingsRoute.Home

    private val _state = MutableStateFlow(SettingsUiState(route = restoredRoute))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _events = Channel<SettingsEvent>()
    val events: Flow<SettingsEvent> = _events.receiveAsFlow()

    private val updateMutex = Mutex()
    private var lastFailedUpdate: ((Settings) -> Settings)? = null
    private var azureSaveJob: Job? = null
    private var initialized = false

    init {
        viewModelScope.launch {
            operations.partnerDeviceConnected.collect { connected ->
                _state.update { it.copy(partnerDeviceConnected = connected) }
            }
        }
    }

    fun onAction(action: SettingsAction) {
        when (action) {
            SettingsAction.Initialize -> initialize()
            SettingsAction.RetryLoad -> load()
            SettingsAction.RetrySave -> retrySave()
            SettingsAction.BackClicked -> handleBack()

            is SettingsAction.CategorySelected -> selectCategory(action.tab)
            SettingsAction.PronunciationOpened -> openDictionary()
            SettingsAction.VoiceSelectionOpened -> openSubPage(
                SettingsRoute.VoiceSelection, "voice_selection"
            )
            SettingsAction.LanguageSelectionOpened -> openSubPage(
                SettingsRoute.LanguageSelection, "language_selection"
            )
            SettingsAction.F0SetupOpened -> openSubPage(SettingsRoute.F0Setup, "azure_f0_setup")
            SettingsAction.GoogleSetupOpened -> openSubPage(
                SettingsRoute.GoogleSetup, "google_tts_setup"
            )
            SettingsAction.F0SetupCompleted -> completeF0Setup()
            SettingsAction.GoogleSetupCompleted -> completeGoogleSetup()

            is SettingsAction.TtsEngineSelected -> persist {
                it.copy(ttsEngine = action.engine)
            }
            is SettingsAction.VirtualMicChanged -> persist {
                it.copy(virtualMicEnabled = action.enabled)
            }
            is SettingsAction.AzureEndpointChanged -> {
                _state.update {
                    it.copy(azureEndpoint = action.value, azureEndpointInvalid = false)
                }
                scheduleAzureCredentialSave()
            }
            is SettingsAction.AzureSubscriptionKeyChanged -> {
                _state.update { it.copy(azureSubscriptionKey = action.value) }
                scheduleAzureCredentialSave()
            }
            SettingsAction.ReplaceAzureCredentialsClicked -> _state.update {
                it.copy(replacingAzureCredentials = true, azureSubscriptionKey = "")
            }
            SettingsAction.GoogleCredentialsCleared -> clearGoogleCredentials()

            is SettingsAction.FontSizeScaleChanged -> persist {
                it.copy(fontSizeScale = action.value)
            }
            is SettingsAction.PlaybackIconScaleChanged -> persist {
                it.copy(playbackIconScale = action.value)
            }
            is SettingsAction.CategoryChipScaleChanged -> persist {
                it.copy(categoryChipScale = action.value)
            }
            is SettingsAction.ButtonScaleChanged -> persist {
                it.copy(buttonScale = action.value)
            }
            is SettingsAction.InputFieldScaleChanged -> persist {
                it.copy(inputFieldScale = action.value)
            }
            is SettingsAction.ShowLabelsChanged -> changeShowLabels(action.checked)
            is SettingsAction.ShowSymbolsChanged -> changeShowSymbols(action.checked)
            is SettingsAction.LabelAtTopChanged -> persist { it.copy(labelAtTop = action.checked) }
            is SettingsAction.BoardShowMessageBarChanged -> persist {
                it.copy(boardShowMessageBar = action.checked)
            }
            is SettingsAction.BoardActivationBehaviorChanged -> persist {
                it.copy(boardActivationBehavior = action.behavior)
            }
            is SettingsAction.BoardReturnBehaviorChanged -> persist {
                it.copy(boardReturnBehavior = action.behavior)
            }
            is SettingsAction.GridColumnsChanged -> updateLocally {
                it.copy(gridColumns = action.columns)
            }
            SettingsAction.GridColumnsChangeFinished -> commitLocal { copy(gridColumns = gridColumns) }
            is SettingsAction.HighContrastModeChanged -> persist {
                it.copy(highContrastMode = action.checked)
            }
            is SettingsAction.WordTypeColorsChanged -> persist {
                it.copy(
                    wordTypeColorScheme = if (action.enabled) {
                        WordTypeColorScheme.Fitzgerald
                    } else {
                        WordTypeColorScheme.None
                    },
                )
            }

            is SettingsAction.HoldToSelectChanged -> updateLocally {
                it.copy(holdToSelectMillis = action.millis)
            }
            SettingsAction.HoldToSelectChangeFinished -> commitLocal {
                copy(holdToSelectMillis = holdToSelectMillis)
            }
            is SettingsAction.DwellToSelectChanged -> updateLocally {
                it.copy(dwellToSelectMillis = action.millis)
            }
            SettingsAction.DwellToSelectChangeFinished -> commitLocal {
                copy(dwellToSelectMillis = dwellToSelectMillis)
            }
            is SettingsAction.SelectionSoundChanged -> persist {
                it.copy(selectionSoundEnabled = action.checked)
            }
            is SettingsAction.AuditoryFishingChanged -> persist {
                it.copy(auditoryFishingEnabled = action.checked)
            }
            is SettingsAction.SpeechPolicyChanged -> persist {
                it.copy(speechPolicy = action.policy)
            }
            is SettingsAction.SelectionDebounceChanged -> updateLocally {
                it.copy(selectionDebounceMillis = action.millis)
            }
            SettingsAction.SelectionDebounceChangeFinished -> commitLocal {
                copy(selectionDebounceMillis = selectionDebounceMillis)
            }
            is SettingsAction.DwellRearmDelayChanged -> updateLocally {
                it.copy(dwellRearmDelayMillis = action.millis)
            }
            SettingsAction.DwellRearmDelayChangeFinished -> commitLocal {
                copy(dwellRearmDelayMillis = dwellRearmDelayMillis)
            }
            is SettingsAction.SelectionHighlightChanged -> updateLocally {
                it.copy(selectionHighlightMillis = action.millis)
            }
            SettingsAction.SelectionHighlightChangeFinished -> commitLocal {
                copy(selectionHighlightMillis = selectionHighlightMillis)
            }
            is SettingsAction.SelectKeyBindingChanged -> persist {
                it.copy(selectKeyBinding = action.binding)
            }
            is SettingsAction.RestModeKeyBindingChanged -> persist {
                it.copy(restModeKeyBinding = action.binding)
            }
            is SettingsAction.PointerEmphasisStyleChanged -> persist {
                it.copy(pointerEmphasisStyle = action.style)
            }
            is SettingsAction.PointerEmphasisScaleChanged -> updateLocally {
                it.copy(pointerEmphasisScale = action.scale)
            }
            SettingsAction.PointerEmphasisScaleChangeFinished -> commitLocal {
                copy(pointerEmphasisScale = pointerEmphasisScale)
            }

            is SettingsAction.HistoryVisibleChanged -> persist {
                it.copy(historyVisible = action.checked)
            }
            is SettingsAction.BoardSetSentenceCachingChanged ->
                setSentenceCaching(action.boardSetId, action.enabled)
            is SettingsAction.UsageLoggingChanged -> persist {
                it.copy(usageLoggingEnabled = action.checked)
            }
            is SettingsAction.FeatureReportingChanged -> changeFeatureReporting(action.checked)

            is SettingsAction.StartupModeChanged -> persist {
                it.copy(startupMode = action.mode)
            }
            is SettingsAction.StartupBoardSetChanged -> persist {
                it.copy(startupBoardSetId = action.boardSetId)
            }
            is SettingsAction.PartnerWindowChanged -> persist {
                it.copy(partnerWindowEnabled = action.enabled)
            }
            SettingsAction.DownloadArasaacClicked -> downloadArasaacSymbols()

            SettingsAction.CreateBackupClicked -> exportBackup()
            SettingsAction.RestoreBackupClicked -> pickRestoreFile()
            SettingsAction.RestoreConfirmed -> confirmRestore()
            SettingsAction.RestoreDismissed -> _state.update { it.copy(pendingRestorePath = null) }

            SettingsAction.EditingAccessConfigureClicked -> showEditingAccessDialog(
                EditingAccessDialogMode.Configure
            )
            SettingsAction.EditingAccessUnlockClicked -> showEditingAccessDialog(
                EditingAccessDialogMode.Unlock
            )
            SettingsAction.EditingAccessDisableClicked -> showEditingAccessDialog(
                EditingAccessDialogMode.Disable
            )
            SettingsAction.EditingAccessLockNowClicked -> operations.lockEditingAccess()
            SettingsAction.EditingAccessDialogDismissed -> _state.update {
                it.copy(editingAccessDialog = null)
            }

            is SettingsAction.DictionaryEntryAdded -> addDictionaryEntry(
                PronunciationEntry(action.word, action.phoneme, action.alphabet)
            )
            is SettingsAction.DictionaryEntryDeleted -> deleteDictionaryEntry(action.word)
            is SettingsAction.DictionaryTestRequested -> viewModelScope.launch {
                operations.speakPronunciation(action.word, action.phoneme, action.alphabet)
            }
        }
    }

    /** Resolves a phoneme guess so the dictionary can pre-fill its entry form. */
    suspend fun guessPronunciation(word: String): String? =
        operations.guessPronunciation(word, systemLanguageTag())

    private fun initialize() {
        if (initialized) return
        initialized = true
        operations.reportEvent(FeatureUsageEvents.SETTINGS_OPENED)
        viewModelScope.launch { operations.refreshEditingAccess() }
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadFailed = false) }
            try {
                val settings = operations.getSettings()
                val (endpoint, credentialConfigured) = operations.azureStatus()
                val googleConfigured = operations.googleCredentialConfigured()
                val boardSets = operations.listBoardSets()
                val cachedSymbols = operations.cachedArasaacSymbolCount()
                _state.update {
                    it.copy(
                        isLoading = false,
                        settings = settings,
                        azureEndpoint = endpoint,
                        azureCredentialConfigured = credentialConfigured,
                        googleCredentialConfigured = googleConfigured,
                        boardSets = boardSets,
                        arasaacAvailable = operations.arasaacAvailable,
                        cachedArasaacSymbols = cachedSymbols,
                    )
                }
                operations.setUsageReportingEnabled(settings.featureUsageReportingEnabled)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                _state.update { it.copy(isLoading = false, loadFailed = true) }
            }
        }
    }

    /**
     * Re-runs the last failed write against the currently shown values so a transient
     * failure never silently reverts what the user chose.
     */
    private fun retrySave() {
        val failed = lastFailedUpdate ?: return
        lastFailedUpdate = null
        persist(failed)
    }

    /** Applies the change optimistically, then persists it as one serialized write. */
    private fun persist(update: (Settings) -> Settings) {
        updateLocally(update)
        viewModelScope.launch {
            try {
                updateMutex.withLock { operations.updateSettings(update) }
                lastFailedUpdate = null
                _state.update { it.copy(saveFailed = false) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                lastFailedUpdate = update
                _state.update { it.copy(saveFailed = true) }
            }
        }
    }

    private fun updateLocally(update: (Settings) -> Settings) {
        _state.update { it.copy(settings = update(it.settings)) }
    }

    /** Persists the current local value of a slider after the drag has finished. */
    private fun commitLocal(select: Settings.() -> Settings) {
        val current = _state.value.settings
        val target = current.select()
        persist { target }
    }

    private fun changeShowLabels(checked: Boolean) {
        if (!checked && !_state.value.settings.showSymbols) return
        persist { it.copy(showLabels = checked) }
    }

    private fun changeShowSymbols(checked: Boolean) {
        if (!checked && !_state.value.settings.showLabels) return
        persist { it.copy(showSymbols = checked) }
    }

    private fun changeFeatureReporting(checked: Boolean) {
        persist { it.copy(featureUsageReportingEnabled = checked) }
        operations.setUsageReportingEnabled(checked)
        operations.reportEvent(
            FeatureUsageEvents.ANALYTICS_CONSENT_CHANGED,
            mapOf(
                "enabled" to checked.toString(),
                "source" to "privacy_settings",
            ),
        )
    }

    private fun selectCategory(tab: SettingsTab) {
        setRoute(SettingsRoute.Category(tab))
        operations.reportEvent(
            FeatureUsageEvents.SETTINGS_SECTION_OPENED,
            mapOf("section" to tab.name.lowercase()),
        )
    }

    private fun openDictionary() {
        setRoute(SettingsRoute.PronunciationDictionary)
        viewModelScope.launch {
            val entries = operations.dictionaryEntries()
            _state.update { it.copy(dictionaryEntries = entries) }
        }
    }

    private fun openSubPage(page: SettingsRoute, analyticsSection: String) {
        setRoute(page)
        operations.reportEvent(
            FeatureUsageEvents.SETTINGS_SECTION_OPENED,
            mapOf("section" to analyticsSection),
        )
    }

    private fun completeF0Setup() {
        persist { it.copy(ttsEngine = TtsEngine.AZURE_USER_RESOURCE) }
        viewModelScope.launch {
            val (endpoint, configured) = operations.azureStatus()
            _state.update {
                it.copy(
                    azureEndpoint = endpoint,
                    azureCredentialConfigured = configured,
                    replacingAzureCredentials = false,
                    azureSubscriptionKey = "",
                )
            }
        }
        setRoute(SettingsRoute.Category(SettingsTab.Speech))
    }

    private fun completeGoogleSetup() {
        persist { it.copy(ttsEngine = TtsEngine.GOOGLE_CLOUD) }
        viewModelScope.launch {
            _state.update {
                it.copy(googleCredentialConfigured = operations.googleCredentialConfigured())
            }
        }
        setRoute(SettingsRoute.Category(SettingsTab.Speech))
    }

    private fun clearGoogleCredentials() {
        viewModelScope.launch {
            operations.clearGoogleCredentials()
            _state.update { it.copy(googleCredentialConfigured = false) }
        }
    }

    /**
     * Persists Azure credentials once typing pauses, mirroring the previous debounced
     * text-field behaviour without waiting for an explicit save button.
     */
    private fun scheduleAzureCredentialSave() {
        azureSaveJob?.cancel()
        val current = _state.value
        if (current.azureCredentialConfigured && !current.replacingAzureCredentials) return
        val endpoint = current.azureEndpoint
        val key = current.azureSubscriptionKey
        if (endpoint.isBlank() || key.isBlank()) return
        azureSaveJob = viewModelScope.launch {
            delay(AZURE_SAVE_DEBOUNCE_MS)
            if (AzureSpeechEndpoint.parse(endpoint) is AzureSpeechEndpointResult.Invalid) {
                _state.update { it.copy(azureEndpointInvalid = true) }
                return@launch
            }
            val saved = operations.saveAzureCredentials(endpoint, key)
            if (saved) {
                _state.update {
                    it.copy(
                        azureEndpointInvalid = false,
                        azureCredentialConfigured = true,
                        replacingAzureCredentials = false,
                        azureSubscriptionKey = "",
                    )
                }
            }
        }
    }

    private fun setSentenceCaching(boardSetId: String, enabled: Boolean) {
        viewModelScope.launch {
            val updated = operations.setSentenceCaching(boardSetId, enabled)
            if (updated != null) {
                _state.update { current ->
                    current.copy(
                        boardSets = current.boardSets.map { boardSet ->
                            if (boardSet.id == updated.id) updated else boardSet
                        }
                    )
                }
            }
        }
    }

    private fun downloadArasaacSymbols() {
        if (_state.value.arasaacProgress != null) return
        viewModelScope.launch {
            _state.update { it.copy(arasaacDownloadFailed = false) }
            try {
                val result = operations.downloadArasaacSymbols(systemLanguageTag()) { progress ->
                    _state.update { it.copy(arasaacProgress = progress) }
                }
                _state.update {
                    it.copy(
                        arasaacProgress = null,
                        cachedArasaacSymbols = result.total - result.failed,
                        arasaacDownloadFailed = result.failed > 0,
                        arasaacFailedCount = result.failed,
                    )
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                _state.update { current ->
                    current.copy(
                        arasaacProgress = null,
                        arasaacDownloadFailed = true,
                        arasaacFailedCount = current.arasaacProgress?.failed ?: 0,
                        cachedArasaacSymbols = operations.cachedArasaacSymbolCount(),
                    )
                }
            }
        }
    }

    private fun exportBackup() {
        if (_state.value.backupWorking) return
        viewModelScope.launch {
            _state.update { it.copy(backupWorking = true) }
            val status = try {
                val bytes = operations.exportBackup()
                val shared = operations.shareBackupFile(
                    "wingmate-${Clock.System.now().toEpochMilliseconds()}.wingmate-backup",
                    bytes,
                )
                when {
                    shared -> SettingsMessage.Resource(R.string.backup_exported)
                    else -> SettingsMessage.Resource(R.string.backup_cancelled)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                SettingsMessage.Resource(R.string.backup_create_failed)
            }
            _state.update { it.copy(backupWorking = false, backupStatus = status) }
        }
    }

    private fun pickRestoreFile() {
        viewModelScope.launch {
            _state.update { it.copy(backupStatus = null) }
            try {
                val path = operations.pickRestoreFile()
                _state.update { it.copy(pendingRestorePath = path) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                _state.update {
                    it.copy(backupStatus = SettingsMessage.Resource(R.string.backup_picker_failed))
                }
            }
        }
    }

    private fun confirmRestore() {
        val path = _state.value.pendingRestorePath ?: return
        viewModelScope.launch {
            _state.update { it.copy(pendingRestorePath = null, backupWorking = true) }
            val status = when (val result = operations.restoreBackup(path)) {
                is BackupRestoreResult.Success -> SettingsMessage.Resource(R.string.backup_restored)
                is BackupRestoreResult.Failure -> SettingsMessage.Dynamic(result.message)
                null -> SettingsMessage.Dynamic("Backup restore unavailable")
            }
            _state.update { it.copy(backupWorking = false, backupStatus = status) }
        }
    }

    private fun addDictionaryEntry(entry: PronunciationEntry) {
        viewModelScope.launch {
            operations.addDictionaryEntry(entry)
            _state.update { it.copy(dictionaryEntries = operations.dictionaryEntries()) }
        }
    }

    private fun deleteDictionaryEntry(word: String) {
        viewModelScope.launch {
            operations.deleteDictionaryEntry(word)
            _state.update { it.copy(dictionaryEntries = operations.dictionaryEntries()) }
        }
    }

    private fun showEditingAccessDialog(mode: EditingAccessDialogMode) {
        _state.update { it.copy(editingAccessDialog = mode) }
    }

    private fun handleBack() {
        val route = _state.value.route
        if (route == SettingsRoute.Home) {
            sendEvent(SettingsEvent.Close)
        } else {
            setRoute(backTargetOf(route))
        }
    }

    private fun backTargetOf(route: SettingsRoute): SettingsRoute = when (route) {
        SettingsRoute.Home -> SettingsRoute.Home
        is SettingsRoute.Category -> SettingsRoute.Home
        SettingsRoute.PronunciationDictionary -> SettingsRoute.Home
        SettingsRoute.VoiceSelection,
        SettingsRoute.LanguageSelection,
        SettingsRoute.F0Setup,
        SettingsRoute.GoogleSetup,
        -> SettingsRoute.Category(SettingsTab.Speech)
    }

    private fun setRoute(route: SettingsRoute) {
        savedStateHandle[ROUTE_KEY] = serializeRoute(route)
        _state.update { it.copy(route = route) }
    }

    private fun sendEvent(event: SettingsEvent) {
        viewModelScope.launch { _events.send(event) }
    }

    private fun serializeRoute(route: SettingsRoute): String = when (route) {
        SettingsRoute.Home -> ""
        SettingsRoute.PronunciationDictionary -> ROUTE_DICTIONARY
        is SettingsRoute.Category -> ROUTE_CATEGORY_PREFIX + route.tab.name
        // Speech sub-pages are transient views of the Speech category.
        else -> ROUTE_CATEGORY_PREFIX + SettingsTab.Speech.name
    }

    private companion object {
        const val AZURE_SAVE_DEBOUNCE_MS = 400L
        const val ROUTE_KEY = "settings.route"
        const val ROUTE_CATEGORY_PREFIX = "category:"
        const val ROUTE_DICTIONARY = "pronunciation_dictionary"
    }
}
