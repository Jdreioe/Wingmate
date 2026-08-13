package io.github.jdreioe.wingmate.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.application.FeatureUsageEvents
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.application.reportEvent
import io.github.jdreioe.wingmate.application.BoardSetUseCase
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.SettingsStateManager
import io.github.jdreioe.wingmate.application.EditingAccessController
import io.github.jdreioe.wingmate.application.CompleteBackupManager
import io.github.jdreioe.wingmate.application.BackupRestoreResult
import io.github.jdreioe.wingmate.platform.FilePicker
import io.github.jdreioe.wingmate.platform.ShareService
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository
import io.github.jdreioe.wingmate.domain.PronunciationEntry
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.StartupMode
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.PointerEmphasisStyle
import io.github.jdreioe.wingmate.domain.WordTypeColorScheme
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.domain.obf.BoardActivationBehavior
import io.github.jdreioe.wingmate.domain.obf.BoardReturnBehavior
import io.github.jdreioe.wingmate.domain.SpeechPolicy
import io.github.jdreioe.wingmate.infrastructure.ArasaacDownloadProgress
import io.github.jdreioe.wingmate.infrastructure.ArasaacSymbolDownloadService
import io.github.jdreioe.wingmate.infrastructure.ImageCacher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import org.koin.compose.getKoin
import org.koin.compose.koinInject

import com.hojmoseit.wingmate.R
private enum class SettingsTab { Speech, Display, Accessibility, Privacy, General }

private sealed class SettingsSpeechSubPage {
    object VoiceSelection : SettingsSpeechSubPage()
    object LanguageSelection : SettingsSpeechSubPage()
    object F0Setup : SettingsSpeechSubPage()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onDismiss: () -> Unit,
    onSaved: (() -> Unit)? = null,
    onBackToWelcome: (() -> Unit)? = null
) {
    val koin = getKoin()
    val configRepo = remember(koin) { koin.getOrNull<ConfigRepository>() }
    val settingsUseCase = remember(koin) { koin.getOrNull<SettingsUseCase>() }
    val settingsStateManager = remember(koin) { koin.getOrNull<SettingsStateManager>() }
    val featureUsageReporter = remember(koin) { koin.getOrNull<FeatureUsageReporter>() }
    val boardSetUseCase = remember(koin) { koin.getOrNull<BoardSetUseCase>() }
    val pronunciationRepo = remember(koin) { koin.getOrNull<PronunciationDictionaryRepository>() }
    val speechService = remember(koin) { koin.getOrNull<SpeechService>() }
    val voiceUseCase = remember(koin) { koin.getOrNull<VoiceUseCase>() }
    val imageCacher = remember(koin) { koin.getOrNull<ImageCacher>() }
    val arasaacDownloader = remember(imageCacher) {
        imageCacher?.let(::ArasaacSymbolDownloadService)
    }

    // Null represents the Pixel-style settings index; categories open as child pages.
    var selectedTab by remember { mutableStateOf<SettingsTab?>(null) }
    var speechSubPage by remember { mutableStateOf<SettingsSpeechSubPage?>(null) }

    // --- Speech section state ---
    var endpoint by remember { mutableStateOf("") }
    var subscriptionKey by remember { mutableStateOf("") }
    var credentialConfigured by remember { mutableStateOf(false) }
    var replacingAzureCredentials by remember { mutableStateOf(false) }
    var ttsEngine by remember { mutableStateOf(TtsEngine.SYSTEM) }
    var virtualMic by remember { mutableStateOf(false) }

    // --- Display section state ---
    var fontSizeScale by remember { mutableStateOf(1.0f) }
    var playbackIconScale by remember { mutableStateOf(1.0f) }
    var categoryChipScale by remember { mutableStateOf(1.0f) }
    var buttonScale by remember { mutableStateOf(1.0f) }
    var inputFieldScale by remember { mutableStateOf(1.0f) }
    var showLabels by remember { mutableStateOf(true) }
    var showSymbols by remember { mutableStateOf(true) }
    var labelAtTop by remember { mutableStateOf(false) }
    var boardShowMessageBar by remember { mutableStateOf(true) }
    var boardActivationBehavior by remember {
        mutableStateOf(BoardActivationBehavior.SpeakAndAdd)
    }
    var boardReturnBehavior by remember { mutableStateOf(BoardReturnBehavior.Stay) }
    var gridColumns by remember { mutableStateOf(3) }
    var highContrastMode by remember { mutableStateOf(false) }
    var wordTypeColorScheme by remember { mutableStateOf(WordTypeColorScheme.None) }

    // --- Accessibility section state ---
    var holdToSelectMillis by remember { mutableStateOf(0L) }
    var dwellToSelectMillis by remember { mutableStateOf(0L) }
    var selectionSoundEnabled by remember { mutableStateOf(false) }
    var auditoryFishingEnabled by remember { mutableStateOf(false) }
    var speechPolicy by remember { mutableStateOf(SpeechPolicy.Immediate) }
    var selectionDebounceMillis by remember { mutableStateOf(0L) }
    var selectionHighlightMillis by remember { mutableStateOf(0L) }
    var selectKeyBinding by remember { mutableStateOf("") }
    var restModeKeyBinding by remember { mutableStateOf("") }
    var pointerEmphasisStyle by remember { mutableStateOf(PointerEmphasisStyle.System) }
    var pointerEmphasisScale by remember { mutableStateOf(1.5f) }
    var usageLoggingEnabled by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        featureUsageReporter?.reportEvent(FeatureUsageEvents.SETTINGS_OPENED)
    }

    // --- General section state ---
    var featureUsageReportingEnabled by remember { mutableStateOf(false) }
    var historyVisible by remember { mutableStateOf(true) }
    var partnerWindowEnabled by remember { mutableStateOf(false) }
    var startupMode by remember { mutableStateOf(StartupMode.Keyboard) }
    var startupBoardSetId by remember { mutableStateOf<String?>(null) }
    var availableBoardSets by remember { mutableStateOf<List<ObfBoardSet>>(emptyList()) }
    var cachedArasaacSymbols by remember { mutableStateOf(0) }
    var arasaacProgress by remember { mutableStateOf<ArasaacDownloadProgress?>(null) }
    var arasaacDownloadError by remember { mutableStateOf(false) }
    var arasaacFailedCount by remember { mutableStateOf(0) }

    var showPronunciationDictionary by remember { mutableStateOf(false) }
    var dictionaryEntries by remember { mutableStateOf<List<PronunciationEntry>>(emptyList()) }

    var loading by remember { mutableStateOf(true) }
    var settingsError by remember { mutableStateOf<String?>(null) }
    var settingsRetryKey by remember { mutableIntStateOf(0) }
    val settingsLoadFailed = stringResource(R.string.settings_load_failed)
    val settingsSaveFailed = stringResource(R.string.settings_save_failed)
    val voiceReadFailed = stringResource(R.string.voice_load_failed)
    val scope = rememberCoroutineScope()
    val settingsUpdateMutex = remember { Mutex() }

    // Partner window device detection (desktop-only)
    val partnerDeviceConnected by PartnerWindowAvailability.deviceConnected.collectAsStateWithLifecycle()

    // When the settings screen opens it is layered on top of the previous screen, which may
    // still hold focus on a text field. Prevent the software keyboard from popping up by
    // dropping focus and hiding the IME as soon as settings is shown.
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    // Helper to update settings reactively
    fun updateSettings(update: (Settings) -> Settings) {
        scope.launch {
            try {
                settingsUpdateMutex.withLock {
                    if (settingsStateManager != null) {
                        settingsStateManager.updateSettings(update)
                    } else {
                        val useCase = checkNotNull(settingsUseCase) { "Settings are unavailable" }
                        withContext(Dispatchers.Default) {
                            useCase.update(update(useCase.get()))
                        }
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                settingsError = settingsSaveFailed
            }
        }
    }

    suspend fun selectedVoiceOrReport(): Voice? = try {
        voiceUseCase?.selected()
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        settingsError = voiceReadFailed
        null
    }

    // Load all settings on first composition
    LaunchedEffect(settingsRetryKey) {
        loading = true
        settingsError = null
        try {
        val cfg = withContext(Dispatchers.Default) { configRepo?.getSpeechConfigStatus() }
        cfg?.let {
            endpoint = it.endpoint
            credentialConfigured = it.credentialConfigured
        }

        val s = withContext(Dispatchers.Default) {
            checkNotNull(settingsUseCase) { "Settings are unavailable" }.get()
        }
        ttsEngine = s.ttsEngine
        virtualMic = s.virtualMicEnabled
        featureUsageReportingEnabled = s.featureUsageReportingEnabled
        historyVisible = s.historyVisible
        partnerWindowEnabled = s.partnerWindowEnabled
        startupMode = s.startupMode
        startupBoardSetId = s.startupBoardSetId
        availableBoardSets = withContext(Dispatchers.Default) {
            checkNotNull(boardSetUseCase) { "Screen storage is unavailable" }.listBoardSets()
        }
        showLabels = s.showLabels
        showSymbols = s.showSymbols
        labelAtTop = s.labelAtTop
        boardShowMessageBar = s.boardShowMessageBar
        boardActivationBehavior = s.boardActivationBehavior
        boardReturnBehavior = s.boardReturnBehavior
        holdToSelectMillis = s.holdToSelectMillis
        gridColumns = s.gridColumns
        highContrastMode = s.highContrastMode
        wordTypeColorScheme = s.wordTypeColorScheme
        dwellToSelectMillis = s.dwellToSelectMillis
        selectionSoundEnabled = s.selectionSoundEnabled
        auditoryFishingEnabled = s.auditoryFishingEnabled
        speechPolicy = s.speechPolicy
        selectionDebounceMillis = s.selectionDebounceMillis
        selectionHighlightMillis = s.selectionHighlightMillis
        selectKeyBinding = s.selectKeyBinding
        restModeKeyBinding = s.restModeKeyBinding
        pointerEmphasisStyle = s.pointerEmphasisStyle
        pointerEmphasisScale = s.pointerEmphasisScale
        usageLoggingEnabled = s.usageLoggingEnabled
        fontSizeScale = s.fontSizeScale
        playbackIconScale = s.playbackIconScale
        categoryChipScale = s.categoryChipScale
        buttonScale = s.buttonScale
        inputFieldScale = s.inputFieldScale
        featureUsageReporter?.setEnabled(s.featureUsageReportingEnabled)
        cachedArasaacSymbols = runCatching { arasaacDownloader?.cachedCount() ?: 0 }.getOrDefault(0)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            settingsError = settingsLoadFailed
        } finally {
            loading = false
        }
    }

    // Load pronunciation entries when opening the dictionary
    LaunchedEffect(showPronunciationDictionary, pronunciationRepo) {
        if (showPronunciationDictionary) {
            dictionaryEntries = withContext(Dispatchers.Default) {
                runCatching { pronunciationRepo?.getAll().orEmpty() }.getOrDefault(emptyList())
            }
        }
    }

    // Persist text input after the user pauses typing instead of waiting for a Save button.
    LaunchedEffect(endpoint, subscriptionKey, loading, replacingAzureCredentials) {
        if (!loading && (!credentialConfigured || replacingAzureCredentials) &&
            endpoint.isNotBlank() && subscriptionKey.isNotBlank()
        ) {
            delay(400)
            val repository = configRepo ?: return@LaunchedEffect
            val saved = runCatching {
                repository.saveSpeechConfig(
                    SpeechServiceConfig(endpoint = endpoint, subscriptionKey = subscriptionKey)
                )
            }.isSuccess
            if (saved) {
                credentialConfigured = true
                replacingAzureCredentials = false
                subscriptionKey = ""
            }
        }
    }

    fun closeSettings() {
        onSaved?.invoke()
        onDismiss()
    }

    fun handleBack() {
        when {
            showPronunciationDictionary -> showPronunciationDictionary = false
            speechSubPage != null -> speechSubPage = null
            selectedTab != null -> selectedTab = null
            else -> closeSettings()
        }
    }

    PlatformBackHandler(enabled = true, onBack = ::handleBack)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        when {
                            showPronunciationDictionary -> stringResource(R.string.dictionary_title)
                            speechSubPage is SettingsSpeechSubPage.VoiceSelection -> stringResource(R.string.voice_select_title)
                            speechSubPage is SettingsSpeechSubPage.LanguageSelection -> stringResource(R.string.language_dialog_title)
                            selectedTab != null -> settingsCategoryTitle(selectedTab!!)
                            else -> stringResource(R.string.ui_settings_title)
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = ::handleBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_close))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                )
            )
        }
        ) { contentPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                ) {
                    if (loading) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (settingsError != null) {
                        Column(
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(settingsError.orEmpty(), color = MaterialTheme.colorScheme.error)
                            Button(onClick = { settingsRetryKey++ }) {
                                Text(stringResource(R.string.common_retry))
                            }
                        }
                    } else if (showPronunciationDictionary) {
                        DictionaryScreen(
                            entries = dictionaryEntries,
                            showTopBar = false,
                            onAddEntry = { word, phoneme, alphabet ->
                                scope.launch {
                                    val repo = pronunciationRepo ?: return@launch
                                    withContext(Dispatchers.Default) {
                                        repo.add(PronunciationEntry(word, phoneme, alphabet))
                                    }
                                    dictionaryEntries = withContext(Dispatchers.Default) {
                                        repo.getAll()
                                    }
                                }
                            },
                            onDeleteEntry = { entry ->
                                scope.launch {
                                    val repo = pronunciationRepo ?: return@launch
                                    withContext(Dispatchers.Default) {
                                        repo.delete(entry.word)
                                    }
                                    dictionaryEntries = withContext(Dispatchers.Default) {
                                        repo.getAll()
                                    }
                                }
                            },
                            onTestEntry = { word, phoneme, alphabet ->
                                scope.launch {
                                    val voice = selectedVoiceOrReport()
                                    val pronunciationMarkup = if (alphabet == "text") {
                                        "<sub alias=\"$phoneme\">$word</sub>"
                                    } else {
                                        "<phoneme alphabet=\"$alphabet\" ph=\"$phoneme\">$word</phoneme>"
                                    }
                                    speechService?.speak(
                                        pronunciationMarkup,
                                        voice,
                                        voice?.pitch,
                                        voice?.rate
                                    )
                                }
                            },
                            onGuessPronunciation = { word ->
                                val voice = selectedVoiceOrReport()
                                speechService?.guessPronunciation(
                                    word,
                                    voice?.selectedLanguage ?: voice?.primaryLanguage ?: "en"
                                )
                            },
                            onBack = { showPronunciationDictionary = false },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .widthIn(max = 920.dp)
                                .align(Alignment.TopCenter)
                        ) {
                            if (selectedTab == null) {
                                SettingsHomePage(
                                    onSelectCategory = {
                                        selectedTab = it
                                        featureUsageReporter?.reportEvent(
                                            FeatureUsageEvents.SETTINGS_SECTION_OPENED,
                                            "section" to it.name.lowercase()
                                        )
                                    },
                                    onOpenPronunciation = { showPronunciationDictionary = true },
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                val currentTab = checkNotNull(selectedTab)
                                val subPage = speechSubPage
                                if (subPage != null && currentTab == SettingsTab.Speech) {
                                    // Sub-pages manage their own scrolling, so they must
                                    // NOT be placed inside another verticalScroll container.
                                    when (subPage) {
                                        SettingsSpeechSubPage.VoiceSelection -> VoiceSelectionPage(
                                            onBack = { speechSubPage = null }
                                        )
                                        SettingsSpeechSubPage.LanguageSelection -> LanguageSelectionPage(
                                            onBack = { speechSubPage = null }
                                        )
                                        SettingsSpeechSubPage.F0Setup -> F0SetupScreen(
                                            onDone = {
                                                ttsEngine = TtsEngine.AZURE_USER_RESOURCE
                                                updateSettings { it.copy(ttsEngine = TtsEngine.AZURE_USER_RESOURCE) }
                                                scope.launch {
                                                    configRepo?.getSpeechConfigStatus()?.let {
                                                        endpoint = it.endpoint
                                                        credentialConfigured = it.credentialConfigured
                                                        replacingAzureCredentials = false
                                                        subscriptionKey = ""
                                                    }
                                                }
                                                speechSubPage = null
                                            },
                                            onBack = { speechSubPage = null }
                                        )
                                    }
                                } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                when (currentTab) {
                            SettingsTab.Speech -> SpeechSection(
                                ttsEngine = ttsEngine,
                                onTtsEngineChange = { engine ->
                                    ttsEngine = engine
                                    updateSettings { it.copy(ttsEngine = engine) }
                                },
                                endpoint = endpoint,
                                onEndpointChange = { endpoint = it },
                                subscriptionKey = subscriptionKey,
                                onSubscriptionKeyChange = { subscriptionKey = it },
                                credentialConfigured = credentialConfigured,
                                replacingCredentials = replacingAzureCredentials,
                                onReplaceCredentials = {
                                    replacingAzureCredentials = true
                                    subscriptionKey = ""
                                },
                                virtualMic = virtualMic,
                                onVirtualMicChange = { checked ->
                                    virtualMic = checked
                                    updateSettings { it.copy(virtualMicEnabled = checked) }
                                },
                                onOpenVoiceSelection = {
                                    speechSubPage = SettingsSpeechSubPage.VoiceSelection
                                    featureUsageReporter?.reportEvent(FeatureUsageEvents.SETTINGS_SECTION_OPENED, "section" to "voice_selection")
                                },
                                onOpenLanguageSelection = {
                                    speechSubPage = SettingsSpeechSubPage.LanguageSelection
                                    featureUsageReporter?.reportEvent(FeatureUsageEvents.SETTINGS_SECTION_OPENED, "section" to "language_selection")
                                },
                                onOpenF0Setup = {
                                    speechSubPage = SettingsSpeechSubPage.F0Setup
                                    featureUsageReporter?.reportEvent(FeatureUsageEvents.SETTINGS_SECTION_OPENED, "section" to "azure_f0_setup")
                                }
                            )
                                    SettingsTab.Display -> DisplaySection(
                                        fontSizeScale = fontSizeScale,
                                        onFontSizeScaleChange = { fontSizeScale = it; updateSettings { s -> s.copy(fontSizeScale = it) } },
                                        playbackIconScale = playbackIconScale,
                                        onPlaybackIconScaleChange = { playbackIconScale = it; updateSettings { s -> s.copy(playbackIconScale = it) } },
                                        categoryChipScale = categoryChipScale,
                                        onCategoryChipScaleChange = { categoryChipScale = it; updateSettings { s -> s.copy(categoryChipScale = it) } },
                                        buttonScale = buttonScale,
                                        onButtonScaleChange = { buttonScale = it; updateSettings { s -> s.copy(buttonScale = it) } },
                                        inputFieldScale = inputFieldScale,
                                        onInputFieldScaleChange = { inputFieldScale = it; updateSettings { s -> s.copy(inputFieldScale = it) } },
                                        showLabels = showLabels,
                                        onShowLabelsChange = { checked ->
                                            if (checked || showSymbols) {
                                                showLabels = checked
                                                updateSettings { it.copy(showLabels = checked) }
                                            }
                                        },
                                        showSymbols = showSymbols,
                                        onShowSymbolsChange = { checked ->
                                            if (checked || showLabels) {
                                                showSymbols = checked
                                                updateSettings { it.copy(showSymbols = checked) }
                                            }
                                        },
                                        labelAtTop = labelAtTop,
                                        onLabelAtTopChange = { checked -> labelAtTop = checked; updateSettings { it.copy(labelAtTop = checked) } },
                                        boardShowMessageBar = boardShowMessageBar,
                                        onBoardShowMessageBarChange = { checked ->
                                            boardShowMessageBar = checked
                                            updateSettings { it.copy(boardShowMessageBar = checked) }
                                        },
                                        boardActivationBehavior = boardActivationBehavior,
                                        onBoardActivationBehaviorChange = { behavior ->
                                            boardActivationBehavior = behavior
                                            updateSettings { it.copy(boardActivationBehavior = behavior) }
                                        },
                                        boardReturnBehavior = boardReturnBehavior,
                                        onBoardReturnBehaviorChange = { behavior ->
                                            boardReturnBehavior = behavior
                                            updateSettings { it.copy(boardReturnBehavior = behavior) }
                                        },
                                        gridColumns = gridColumns,
                                        onGridColumnsChange = { gridColumns = it },
                                        onGridColumnsChangeFinished = { updateSettings { it.copy(gridColumns = gridColumns) } },
                                        highContrastMode = highContrastMode,
                                        onHighContrastModeChange = { checked -> highContrastMode = checked; updateSettings { it.copy(highContrastMode = checked) } },
                                        wordTypeColorScheme = wordTypeColorScheme,
                                        onWordTypeColorSchemeChange = { scheme ->
                                            wordTypeColorScheme = scheme
                                            updateSettings { it.copy(wordTypeColorScheme = scheme) }
                                        }
                                    )
                                    SettingsTab.Accessibility -> AccessibilitySection(
                                        holdToSelectMillis = holdToSelectMillis,
                                        onHoldToSelectChange = { holdToSelectMillis = it },
                                        onHoldToSelectChangeFinished = { updateSettings { it.copy(holdToSelectMillis = holdToSelectMillis) } },
                                        dwellToSelectMillis = dwellToSelectMillis,
                                        onDwellToSelectChange = { dwellToSelectMillis = it },
                                        onDwellToSelectChangeFinished = { updateSettings { it.copy(dwellToSelectMillis = dwellToSelectMillis) } },
                                        selectionSoundEnabled = selectionSoundEnabled,
                                        onSelectionSoundChange = { checked -> selectionSoundEnabled = checked; updateSettings { it.copy(selectionSoundEnabled = checked) } },
                                        auditoryFishingEnabled = auditoryFishingEnabled,
                                        onAuditoryFishingChange = { checked -> auditoryFishingEnabled = checked; updateSettings { it.copy(auditoryFishingEnabled = checked) } },
                                        speechPolicy = speechPolicy,
                                        onSpeechPolicyChange = { policy ->
                                            speechPolicy = policy
                                            updateSettings { it.copy(speechPolicy = policy) }
                                        },
                                        selectionDebounceMillis = selectionDebounceMillis,
                                        onSelectionDebounceChange = { selectionDebounceMillis = it },
                                        onSelectionDebounceChangeFinished = { updateSettings { it.copy(selectionDebounceMillis = selectionDebounceMillis) } },
                                        selectionHighlightMillis = selectionHighlightMillis,
                                        onSelectionHighlightChange = { selectionHighlightMillis = it },
                                        onSelectionHighlightChangeFinished = { updateSettings { it.copy(selectionHighlightMillis = selectionHighlightMillis) } },
                                        selectKeyBinding = selectKeyBinding,
                                        onSelectKeyBindingChange = { value -> selectKeyBinding = value; updateSettings { it.copy(selectKeyBinding = value) } },
                                        restModeKeyBinding = restModeKeyBinding,
                                        onRestModeKeyBindingChange = { value -> restModeKeyBinding = value; updateSettings { it.copy(restModeKeyBinding = value) } },
                                        pointerEmphasisStyle = pointerEmphasisStyle,
                                        onPointerEmphasisStyleChange = { value -> pointerEmphasisStyle = value; updateSettings { it.copy(pointerEmphasisStyle = value) } },
                                        pointerEmphasisScale = pointerEmphasisScale,
                                        onPointerEmphasisScaleChange = { pointerEmphasisScale = it },
                                        onPointerEmphasisScaleChangeFinished = { updateSettings { it.copy(pointerEmphasisScale = pointerEmphasisScale) } }
                                    )
                                    SettingsTab.Privacy -> PrivacySection(
                                        historyVisible = historyVisible,
                                        onHistoryVisibleChange = { checked ->
                                            historyVisible = checked
                                            updateSettings { it.copy(historyVisible = checked) }
                                        },
                                        boardSets = availableBoardSets,
                                        onBoardSetSentenceCachingChange = { boardSet, enabled ->
                                            scope.launch {
                                                val updated = boardSetUseCase?.setSentenceCaching(boardSet.id, enabled)
                                                if (updated != null) {
                                                    availableBoardSets = availableBoardSets.map {
                                                        if (it.id == updated.id) updated else it
                                                    }
                                                }
                                            }
                                        },
                                        usageLoggingEnabled = usageLoggingEnabled,
                                        onUsageLoggingChange = { checked ->
                                            usageLoggingEnabled = checked
                                            updateSettings { it.copy(usageLoggingEnabled = checked) }
                                        },
                                        featureUsageReportingEnabled = featureUsageReportingEnabled,
                                        onFeatureReportingChange = { checked ->
                                            featureUsageReportingEnabled = checked
                                            updateSettings { it.copy(featureUsageReportingEnabled = checked) }
                                            featureUsageReporter?.setEnabled(checked)
                                            featureUsageReporter?.reportEvent(
                                                FeatureUsageEvents.ANALYTICS_CONSENT_CHANGED,
                                                "enabled" to checked.toString(),
                                                "source" to "privacy_settings"
                                            )
                                        }
                                    )
                                    SettingsTab.General -> GeneralSection(
                                        onBackToWelcome = onBackToWelcome,
                                        startupMode = startupMode,
                                        startupBoardSetId = startupBoardSetId,
                                        availableBoardSets = availableBoardSets,
                                        onStartupModeChange = { mode ->
                                            startupMode = mode
                                            updateSettings { it.copy(startupMode = mode) }
                                        },
                                        onStartupBoardSetChange = { boardSetId ->
                                            startupBoardSetId = boardSetId
                                            updateSettings { it.copy(startupBoardSetId = boardSetId) }
                                        },
                                        partnerWindowEnabled = partnerWindowEnabled,
                                        partnerDeviceConnected = partnerDeviceConnected,
                                        onPartnerWindowChange = { checked -> partnerWindowEnabled = checked; updateSettings { it.copy(partnerWindowEnabled = checked) } },
                                        arasaacAvailable = arasaacDownloader != null,
                                        cachedArasaacSymbols = cachedArasaacSymbols,
                                        arasaacProgress = arasaacProgress,
                                        arasaacDownloadError = arasaacDownloadError,
                                        arasaacFailedCount = arasaacFailedCount,
                                        onDownloadArasaac = {
                                            if (arasaacProgress == null) {
                                                scope.launch {
                                                    arasaacDownloadError = false
                                                    runCatching {
                                                        arasaacDownloader?.downloadAll(systemLanguageTag()) { progress ->
                                                            arasaacProgress = progress
                                                        } ?: error("ARASAAC storage unavailable")
                                                    }.onSuccess { result ->
                                                        cachedArasaacSymbols = result.total - result.failed
                                                        arasaacDownloadError = result.failed > 0
                                                        arasaacFailedCount = result.failed
                                                    }.onFailure {
                                                        arasaacDownloadError = true
                                                        arasaacFailedCount = arasaacProgress?.failed ?: 0
                                                        cachedArasaacSymbols = runCatching {
                                                            arasaacDownloader?.cachedCount() ?: cachedArasaacSymbols
                                                        }.getOrDefault(cachedArasaacSymbols)
                                                    }
                                                    arasaacProgress = null
                                                }
                                            }
                                        }
                                    )
                                }
                                Spacer(Modifier.height(16.dp))
                                }
                                }
                            }
                        }
                    }
                }
            }
}

@Composable
private fun settingsCategoryTitle(tab: SettingsTab): String = when (tab) {
    SettingsTab.Speech -> stringResource(R.string.ui_settings_speech_title)
    SettingsTab.Display -> stringResource(R.string.ui_settings_display_title)
    SettingsTab.Accessibility -> stringResource(R.string.ui_settings_accessibility_title)
    SettingsTab.Privacy -> stringResource(R.string.ui_settings_privacy_title)
    SettingsTab.General -> stringResource(R.string.ui_settings_general_title)
}

private data class SettingsCategoryItem(
    val tab: SettingsTab?,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val iconContainerColor: Color,
    val iconColor: Color,
    val keywords: List<String> = emptyList(),
    val openPronunciation: Boolean = false
) {
    fun matches(query: String): Boolean {
        if (query.isEmpty()) return true
        if (title.lowercase().contains(query)) return true
        if (subtitle.lowercase().contains(query)) return true
        return keywords.any { it.lowercase().contains(query) }
    }
}

@Composable
private fun SettingsHomePage(
    onSelectCategory: (SettingsTab) -> Unit,
    onOpenPronunciation: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    val speechTitle = stringResource(R.string.ui_settings_speech_title)
    val displayTitle = stringResource(R.string.ui_settings_display_title)
    val accessibilityTitle = stringResource(R.string.ui_settings_accessibility_title)
    val privacyTitle = stringResource(R.string.ui_settings_privacy_title)
    val generalTitle = stringResource(R.string.ui_settings_general_title)

    val categories = listOf(
        SettingsCategoryItem(
            tab = SettingsTab.Speech,
            title = speechTitle,
            subtitle = stringResource(R.string.ui_settings_speech_desc),
            icon = Icons.Filled.RecordVoiceOver,
            iconContainerColor = Color(0xFF78D6F7),
            iconColor = Color(0xFF004E65),
            keywords = listOf(
                "tts", "azure", "system tts", "engine", "endpoint", "subscription",
                "region", "key", "voice", stringResource(R.string.phrase_screen_voice_settings),
                stringResource(R.string.voice_select_title), stringResource(R.string.common_language),
                stringResource(R.string.ui_settings_virtual_mic_title),
                stringResource(R.string.ui_settings_virtual_mic_desc),
                "microphone", "zoom", "meet", "language"
            )
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = displayTitle,
            subtitle = stringResource(R.string.ui_settings_display_desc),
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf(
                "grid", "layout", "scaling", "ui scaling", "font", "font size",
                "playback", "icons", "category", "chips", "buttons", "input",
                "fields", stringResource(R.string.ui_settings_show_labels_title),
                stringResource(R.string.ui_settings_show_symbols_title),
                stringResource(R.string.ui_settings_label_at_top_title),
                stringResource(R.string.ui_settings_grid_columns_title),
                stringResource(R.string.ui_settings_high_contrast_title),
                stringResource(R.string.board_settings_message_bar),
                stringResource(R.string.board_settings_activation),
                stringResource(R.string.board_settings_after_selection),
                "contrast", "symbols", "labels", "communication", "message bar",
                "speak and add", "return page"
            )
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Accessibility,
            title = accessibilityTitle,
            subtitle = stringResource(R.string.ui_settings_accessibility_desc),
            icon = Icons.Filled.Accessibility,
            iconContainerColor = Color(0xFFFFA8D8),
            iconColor = Color(0xFF700044),
            keywords = listOf(
                "touch", "timing", "feedback",
                stringResource(R.string.ui_settings_hold_to_select_title),
                stringResource(R.string.ui_settings_hold_to_select_desc),
                stringResource(R.string.ui_settings_dwell_to_select_title),
                stringResource(R.string.ui_settings_dwell_to_select_desc),
                stringResource(R.string.ui_settings_selection_sound_title),
                stringResource(R.string.ui_settings_auditory_fishing_title),
                stringResource(R.string.ui_settings_auditory_fishing_desc),
                "hold", "dwell", "hover", "sound", "whisper"
            )
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Privacy,
            title = privacyTitle,
            subtitle = stringResource(R.string.ui_settings_privacy_desc),
            icon = Icons.Filled.Security,
            iconContainerColor = Color(0xFFB8C4FF),
            iconColor = Color(0xFF263B80),
            keywords = listOf(
                stringResource(R.string.ui_settings_history_visible_title),
                stringResource(R.string.ui_settings_history_visible_desc),
                stringResource(R.string.ui_settings_feature_reporting_title),
                stringResource(R.string.ui_settings_feature_reporting_desc),
                stringResource(R.string.ui_settings_usage_logging_title),
                stringResource(R.string.ui_settings_usage_logging_desc),
                "history", "cache", "privacy", "analytics", "logging", "data"
            )
        ),
        SettingsCategoryItem(
            tab = SettingsTab.General,
            title = generalTitle,
            subtitle = stringResource(R.string.ui_settings_general_desc),
            icon = Icons.Filled.Storage,
            iconContainerColor = Color(0xFFA9D49A),
            iconColor = Color(0xFF1D4E18),
            keywords = listOf(
                stringResource(R.string.ui_settings_startup_mode_title),
                stringResource(R.string.ui_settings_startup_mode_desc),
                stringResource(R.string.ui_settings_startup_mode_keyboard),
                stringResource(R.string.ui_settings_startup_mode_screens),
                stringResource(R.string.ui_settings_startup_screen_title),
                stringResource(R.string.ui_settings_startup_screen_library),
                stringResource(R.string.ui_settings_symbols_title),
                stringResource(R.string.ui_settings_symbols_download_title),
                stringResource(R.string.ui_settings_symbols_download_desc),
                stringResource(R.string.ui_settings_symbols_download),
                stringResource(R.string.ui_settings_partner_window_title),
                stringResource(R.string.ui_settings_partner_window_desc),
                stringResource(R.string.phrase_screen_welcome_screen),
                stringResource(R.string.backup_title),
                stringResource(R.string.backup_create),
                stringResource(R.string.backup_restore),
                "startup", "arasaac", "offline",
                "partner", "welcome", "boards", "screens", "backup", "restore"
            )
        )
    )
    val pronunciationItem = SettingsCategoryItem(
        tab = null,
        title = stringResource(R.string.dictionary_title),
        subtitle = stringResource(R.string.dictionary_description),
        icon = Icons.AutoMirrored.Filled.MenuBook,
        iconContainerColor = Color(0xFFB39DDB),
        iconColor = Color(0xFF4A148C),
        openPronunciation = true,
        keywords = listOf(
            "pronunciation", "dictionary", "phoneme", "ipa", "speech",
            stringResource(R.string.dictionary_word_label),
            stringResource(R.string.dictionary_phoneme_label),
            stringResource(R.string.dictionary_add_entry_title)
        )
    )

    val subSettings = listOf(
        // Speech
        SettingsCategoryItem(
            tab = SettingsTab.Speech,
            title = stringResource(R.string.ui_settings_tts_engine_group),
            subtitle = speechTitle,
            icon = Icons.Filled.RecordVoiceOver,
            iconContainerColor = Color(0xFF78D6F7),
            iconColor = Color(0xFF004E65),
            keywords = listOf("tts", "azure", "system tts", "engine", "endpoint", "subscription", "region", "key")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Speech,
            title = stringResource(R.string.phrase_screen_voice_settings),
            subtitle = speechTitle,
            icon = Icons.Filled.RecordVoiceOver,
            iconContainerColor = Color(0xFF78D6F7),
            iconColor = Color(0xFF004E65),
            keywords = listOf(
                stringResource(R.string.voice_select_title),
                stringResource(R.string.common_language),
                "voice", "language"
            )
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Speech,
            title = stringResource(R.string.ui_settings_virtual_mic_title),
            subtitle = stringResource(R.string.ui_settings_virtual_mic_desc),
            icon = Icons.Filled.RecordVoiceOver,
            iconContainerColor = Color(0xFF78D6F7),
            iconColor = Color(0xFF004E65),
            keywords = listOf("microphone", "zoom", "meet", "virtual")
        ),
        // Display
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(R.string.ui_settings_show_labels_title),
            subtitle = displayTitle,
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("labels", "grid", "layout")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(R.string.ui_settings_show_symbols_title),
            subtitle = displayTitle,
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("symbols", "images", "grid")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(R.string.ui_settings_label_at_top_title),
            subtitle = displayTitle,
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000)
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(R.string.ui_settings_grid_columns_title),
            subtitle = displayTitle,
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("grid", "columns", "layout")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(R.string.ui_settings_high_contrast_title),
            subtitle = displayTitle,
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("contrast", "accessibility")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(R.string.board_settings_message_bar),
            subtitle = stringResource(R.string.board_settings_global_message_bar_desc),
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("communication", "message", "sentence", "bar")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(R.string.board_settings_activation),
            subtitle = stringResource(R.string.board_settings_global_activation_desc),
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("communication", "speak", "add", "button", "activation")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(R.string.board_settings_after_selection),
            subtitle = stringResource(R.string.board_settings_global_return_desc),
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("communication", "navigation", "return", "previous", "start page")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(R.string.ui_settings_font_size),
            subtitle = displayTitle,
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("ui scaling", "font", "text size", "scale")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(R.string.ui_settings_playback_icons),
            subtitle = displayTitle,
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("ui scaling", "icons", "scale")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(R.string.ui_settings_category_chips),
            subtitle = displayTitle,
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("ui scaling", "category", "chips", "scale")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(R.string.ui_settings_buttons),
            subtitle = displayTitle,
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("ui scaling", "button", "scale")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(R.string.ui_settings_input_fields),
            subtitle = displayTitle,
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("ui scaling", "input", "text field", "scale")
        ),
        // Accessibility
        SettingsCategoryItem(
            tab = SettingsTab.Accessibility,
            title = stringResource(R.string.ui_settings_hold_to_select_title),
            subtitle = stringResource(R.string.ui_settings_hold_to_select_desc),
            icon = Icons.Filled.Accessibility,
            iconContainerColor = Color(0xFFFFA8D8),
            iconColor = Color(0xFF700044),
            keywords = listOf("hold", "touch", "timing")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Accessibility,
            title = stringResource(R.string.ui_settings_dwell_to_select_title),
            subtitle = stringResource(R.string.ui_settings_dwell_to_select_desc),
            icon = Icons.Filled.Accessibility,
            iconContainerColor = Color(0xFFFFA8D8),
            iconColor = Color(0xFF700044),
            keywords = listOf("dwell", "hover", "timing")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Accessibility,
            title = stringResource(R.string.ui_settings_selection_sound_title),
            subtitle = accessibilityTitle,
            icon = Icons.Filled.Accessibility,
            iconContainerColor = Color(0xFFFFA8D8),
            iconColor = Color(0xFF700044),
            keywords = listOf("sound", "feedback")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Accessibility,
            title = stringResource(R.string.ui_settings_auditory_fishing_title),
            subtitle = stringResource(R.string.ui_settings_auditory_fishing_desc),
            icon = Icons.Filled.Accessibility,
            iconContainerColor = Color(0xFFFFA8D8),
            iconColor = Color(0xFF700044),
            keywords = listOf("whisper", "audio", "feedback")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Privacy,
            title = stringResource(R.string.ui_settings_usage_logging_title),
            subtitle = stringResource(R.string.ui_settings_usage_logging_desc),
            icon = Icons.Filled.Security,
            iconContainerColor = Color(0xFFB8C4FF),
            iconColor = Color(0xFF263B80),
            keywords = listOf("obl", "logging", "clinical")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Privacy,
            title = stringResource(R.string.ui_settings_history_visible_title),
            subtitle = stringResource(R.string.ui_settings_history_visible_desc),
            icon = Icons.Filled.Security,
            iconContainerColor = Color(0xFFB8C4FF),
            iconColor = Color(0xFF263B80),
            keywords = listOf("history", "cache", "local data")
        ),
        // General
        SettingsCategoryItem(
            tab = SettingsTab.General,
            title = stringResource(R.string.backup_title),
            subtitle = stringResource(R.string.backup_description),
            icon = Icons.Filled.Storage,
            iconContainerColor = Color(0xFFA9D49A),
            iconColor = Color(0xFF1D4E18),
            keywords = listOf("backup", "restore", "azure", "data")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.General,
            title = stringResource(R.string.ui_settings_startup_mode_title),
            subtitle = stringResource(R.string.ui_settings_startup_mode_desc),
            icon = Icons.Filled.Storage,
            iconContainerColor = Color(0xFFA9D49A),
            iconColor = Color(0xFF1D4E18),
            keywords = listOf(
                stringResource(R.string.ui_settings_startup_mode_keyboard),
                stringResource(R.string.ui_settings_startup_mode_screens),
                stringResource(R.string.ui_settings_startup_screen_title),
                "startup", "open"
            )
        ),
        SettingsCategoryItem(
            tab = SettingsTab.General,
            title = stringResource(R.string.ui_settings_symbols_title),
            subtitle = stringResource(R.string.ui_settings_symbols_download_desc),
            icon = Icons.Filled.Storage,
            iconContainerColor = Color(0xFFA9D49A),
            iconColor = Color(0xFF1D4E18),
            keywords = listOf(
                stringResource(R.string.ui_settings_symbols_download_title),
                "arasaac", "offline", "download", "symbols"
            )
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Privacy,
            title = stringResource(R.string.ui_settings_feature_reporting_title),
            subtitle = stringResource(R.string.ui_settings_feature_reporting_desc),
            icon = Icons.Filled.Security,
            iconContainerColor = Color(0xFFB8C4FF),
            iconColor = Color(0xFF263B80),
            keywords = listOf(
                stringResource(R.string.ui_settings_analytics_title),
                "analytics", "privacy", "telemetry"
            )
        ),
        SettingsCategoryItem(
            tab = SettingsTab.General,
            title = stringResource(R.string.phrase_screen_welcome_screen),
            subtitle = generalTitle,
            icon = Icons.Filled.Storage,
            iconContainerColor = Color(0xFFA9D49A),
            iconColor = Color(0xFF1D4E18),
            keywords = listOf("welcome", "onboarding")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.General,
            title = stringResource(R.string.ui_settings_partner_window_title),
            subtitle = stringResource(R.string.ui_settings_partner_window_desc),
            icon = Icons.Filled.Storage,
            iconContainerColor = Color(0xFFA9D49A),
            iconColor = Color(0xFF1D4E18),
            keywords = listOf("partner", "display", "td-i13", "mirror")
        ),
        pronunciationItem
    )

    val normalizedQuery = query.trim().lowercase()
    val showingSearch = normalizedQuery.isNotEmpty()
    val results = if (!showingSearch) {
        categories + pronunciationItem
    } else {
        val matchedCategories = categories.filter { it.matches(normalizedQuery) }
        val matchedSubs = subSettings.filter { it.matches(normalizedQuery) }
        // Prefer specific sub-settings; keep matching categories that have no matching child title.
        val coveredTabs = matchedSubs.mapNotNull { it.tab }.toSet()
        val uncoveredCategories = matchedCategories.filter { it.tab !in coveredTabs }
        (matchedSubs + uncoveredCategories).distinctBy { "${it.tab}-${it.title}-${it.openPronunciation}" }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.ui_settings_search)) },
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

        if (results.isEmpty()) {
            Text(
                text = stringResource(R.string.ui_settings_search_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp)
            )
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                results.forEachIndexed { index, item ->
                    SettingsCategoryRow(
                        item = item,
                        onClick = {
                            when {
                                item.openPronunciation -> onOpenPronunciation()
                                item.tab != null -> onSelectCategory(item.tab)
                            }
                        }
                    )
                    if (index < results.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 88.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SettingsCategoryRow(
    item: SettingsCategoryItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(52.dp),
            shape = CircleShape,
            color = item.iconContainerColor
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = item.iconColor,
                    modifier = Modifier.size(27.dp)
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                item.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─── Speech Settings ─────────────────────────────────────────────────────────

@Composable
private fun SpeechSection(
    ttsEngine: TtsEngine,
    onTtsEngineChange: (TtsEngine) -> Unit,
    endpoint: String,
    onEndpointChange: (String) -> Unit,
    subscriptionKey: String,
    onSubscriptionKeyChange: (String) -> Unit,
    credentialConfigured: Boolean,
    replacingCredentials: Boolean,
    onReplaceCredentials: () -> Unit,
    virtualMic: Boolean,
    onVirtualMicChange: (Boolean) -> Unit,
    onOpenVoiceSelection: () -> Unit = {},
    onOpenLanguageSelection: () -> Unit = {},
    onOpenF0Setup: () -> Unit = {}
) {
    SettingsGroup(title = stringResource(R.string.ui_settings_tts_engine_group)) {
        SettingsPreferenceRow(
            title = stringResource(R.string.ui_settings_speech_engine),
            subtitle = stringResource(if (ttsEngine == TtsEngine.SYSTEM) R.string.ui_settings_system_tts else R.string.ui_settings_azure_tts)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = ttsEngine != TtsEngine.SYSTEM,
                    onClick = { onTtsEngineChange(TtsEngine.AZURE_USER_RESOURCE) },
                    label = { Text(stringResource(R.string.ui_settings_azure)) }
                )
                FilterChip(
                    selected = ttsEngine == TtsEngine.SYSTEM,
                    onClick = { onTtsEngineChange(TtsEngine.SYSTEM) },
                    label = { Text(stringResource(R.string.ui_settings_system)) }
                )
            }
        }
        if (ttsEngine != TtsEngine.SYSTEM) {
            SettingsGroupDivider()
            AzureCredentialEditor(
                credentialConfigured = credentialConfigured,
                replacingCredentials = replacingCredentials,
                endpoint = endpoint,
                onEndpointChange = onEndpointChange,
                subscriptionKey = subscriptionKey,
                onSubscriptionKeyChange = onSubscriptionKeyChange,
                onReplaceCredentials = onReplaceCredentials,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onOpenF0Setup,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.ui_settings_azure_free_tier))
        }
    }

    SettingsGroup(title = stringResource(R.string.phrase_screen_voice_settings)) {
        SettingsNavRow(
            title = stringResource(R.string.voice_select_title),
            subtitle = stringResource(R.string.ui_settings_speech_desc),
            icon = Icons.Filled.RecordVoiceOver,
            onClick = onOpenVoiceSelection
        )
        SettingsGroupDivider()
        SettingsNavRow(
            title = stringResource(R.string.common_language),
            subtitle = stringResource(R.string.ui_settings_speech_desc),
            icon = Icons.Filled.Language,
            onClick = onOpenLanguageSelection
        )
        if (isDesktop()) {
            SettingsGroupDivider()
            SettingsSwitch(
                checked = virtualMic,
                onCheckedChange = onVirtualMicChange,
                title = stringResource(R.string.ui_settings_virtual_mic_title),
                description = stringResource(R.string.ui_settings_virtual_mic_desc)
            )
        }
    }
}

// ─── Display Tab ─────────────────────────────────────────────────────────────

@Composable
private fun DisplaySection(
    fontSizeScale: Float,
    onFontSizeScaleChange: (Float) -> Unit,
    playbackIconScale: Float,
    onPlaybackIconScaleChange: (Float) -> Unit,
    categoryChipScale: Float,
    onCategoryChipScaleChange: (Float) -> Unit,
    buttonScale: Float,
    onButtonScaleChange: (Float) -> Unit,
    inputFieldScale: Float,
    onInputFieldScaleChange: (Float) -> Unit,
    showLabels: Boolean,
    onShowLabelsChange: (Boolean) -> Unit,
    showSymbols: Boolean,
    onShowSymbolsChange: (Boolean) -> Unit,
    labelAtTop: Boolean,
    onLabelAtTopChange: (Boolean) -> Unit,
    boardShowMessageBar: Boolean,
    onBoardShowMessageBarChange: (Boolean) -> Unit,
    boardActivationBehavior: BoardActivationBehavior,
    onBoardActivationBehaviorChange: (BoardActivationBehavior) -> Unit,
    boardReturnBehavior: BoardReturnBehavior,
    onBoardReturnBehaviorChange: (BoardReturnBehavior) -> Unit,
    gridColumns: Int,
    onGridColumnsChange: (Int) -> Unit,
    onGridColumnsChangeFinished: () -> Unit,
    highContrastMode: Boolean,
    onHighContrastModeChange: (Boolean) -> Unit,
    wordTypeColorScheme: WordTypeColorScheme,
    onWordTypeColorSchemeChange: (WordTypeColorScheme) -> Unit
) {
    SettingsGroup(title = stringResource(R.string.ui_settings_grid_layout)) {
        SettingsSwitch(
            checked = showLabels,
            onCheckedChange = onShowLabelsChange,
            title = stringResource(R.string.ui_settings_show_labels_title)
        )
        SettingsGroupDivider()
        SettingsSwitch(
            checked = showSymbols,
            onCheckedChange = onShowSymbolsChange,
            title = stringResource(R.string.ui_settings_show_symbols_title)
        )
        if (showLabels && showSymbols) {
            SettingsGroupDivider()
            SettingsSwitch(
                checked = labelAtTop,
                onCheckedChange = onLabelAtTopChange,
                title = stringResource(R.string.ui_settings_label_at_top_title)
            )
        }
        SettingsGroupDivider()
        SettingsSlider(
            title = stringResource(R.string.ui_settings_grid_columns_title),
            value = gridColumns.toFloat(),
            onValueChange = { onGridColumnsChange(it.toInt()) },
            onValueChangeFinished = onGridColumnsChangeFinished,
            valueRange = 1f..6f,
            steps = 4,
            valueLabel = "$gridColumns"
        )
        SettingsGroupDivider()
        SettingsSwitch(
            checked = highContrastMode,
            onCheckedChange = onHighContrastModeChange,
            title = stringResource(R.string.ui_settings_high_contrast_title)
        )
        SettingsGroupDivider()
        SettingsSwitch(
            checked = wordTypeColorScheme == WordTypeColorScheme.Fitzgerald,
            onCheckedChange = {
                onWordTypeColorSchemeChange(
                    if (it) WordTypeColorScheme.Fitzgerald else WordTypeColorScheme.None
                )
            },
            title = stringResource(R.string.ui_settings_word_type_colors_title),
            description = stringResource(R.string.ui_settings_word_type_colors_desc)
        )
    }

    SettingsGroup(title = stringResource(R.string.ui_settings_scaling)) {
        ScaleSlider(stringResource(R.string.ui_settings_font_size), fontSizeScale, onFontSizeScaleChange)
        SettingsGroupDivider()
        ScaleSlider(stringResource(R.string.ui_settings_playback_icons), playbackIconScale, onPlaybackIconScaleChange)
        SettingsGroupDivider()
        ScaleSlider(stringResource(R.string.ui_settings_category_chips), categoryChipScale, onCategoryChipScaleChange)
        SettingsGroupDivider()
        ScaleSlider(stringResource(R.string.ui_settings_buttons), buttonScale, onButtonScaleChange)
        SettingsGroupDivider()
        ScaleSlider(stringResource(R.string.ui_settings_input_fields), inputFieldScale, onInputFieldScaleChange)
    }

    SettingsGroup(title = stringResource(R.string.board_settings_group_communication)) {
        SettingsSwitch(
            checked = boardShowMessageBar,
            onCheckedChange = onBoardShowMessageBarChange,
            title = stringResource(R.string.board_settings_message_bar),
            description = stringResource(R.string.board_settings_global_message_bar_desc)
        )
        SettingsGroupDivider()
        SettingsChoiceChips(
            title = stringResource(R.string.board_settings_activation),
            description = stringResource(R.string.board_settings_global_activation_desc),
            selected = boardActivationBehavior,
            options = BoardActivationBehavior.entries,
            label = { behavior ->
                stringResource(
                    when (behavior) {
                        BoardActivationBehavior.SpeakAndAdd -> R.string.board_settings_activation_speak_add
                        BoardActivationBehavior.AddOnly -> R.string.board_settings_activation_add
                        BoardActivationBehavior.SpeakOnly -> R.string.board_settings_activation_speak
                    }
                )
            },
            onSelect = onBoardActivationBehaviorChange
        )
        SettingsGroupDivider()
        SettingsChoiceChips(
            title = stringResource(R.string.board_settings_after_selection),
            description = stringResource(R.string.board_settings_global_return_desc),
            selected = boardReturnBehavior,
            options = BoardReturnBehavior.entries,
            label = { behavior ->
                stringResource(
                    when (behavior) {
                        BoardReturnBehavior.Stay -> R.string.board_settings_return_stay
                        BoardReturnBehavior.Previous -> R.string.board_settings_return_previous
                        BoardReturnBehavior.StartPage -> R.string.board_settings_return_start
                    }
                )
            },
            onSelect = onBoardReturnBehaviorChange
        )
    }
}

@Composable
private fun <T> SettingsChoiceChips(
    title: String,
    description: String,
    selected: T,
    options: List<T>,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(2.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(label(option)) }
                )
            }
        }
    }
}

// ─── Accessibility Tab ───────────────────────────────────────────────────────

@Composable
private fun AccessibilitySection(
    holdToSelectMillis: Long,
    onHoldToSelectChange: (Long) -> Unit,
    onHoldToSelectChangeFinished: () -> Unit,
    dwellToSelectMillis: Long,
    onDwellToSelectChange: (Long) -> Unit,
    onDwellToSelectChangeFinished: () -> Unit,
    selectionSoundEnabled: Boolean,
    onSelectionSoundChange: (Boolean) -> Unit,
    auditoryFishingEnabled: Boolean,
    onAuditoryFishingChange: (Boolean) -> Unit,
    speechPolicy: SpeechPolicy,
    onSpeechPolicyChange: (SpeechPolicy) -> Unit,
    selectionDebounceMillis: Long,
    onSelectionDebounceChange: (Long) -> Unit,
    onSelectionDebounceChangeFinished: () -> Unit,
    selectionHighlightMillis: Long,
    onSelectionHighlightChange: (Long) -> Unit,
    onSelectionHighlightChangeFinished: () -> Unit,
    selectKeyBinding: String,
    onSelectKeyBindingChange: (String) -> Unit,
    restModeKeyBinding: String,
    onRestModeKeyBindingChange: (String) -> Unit,
    pointerEmphasisStyle: PointerEmphasisStyle,
    onPointerEmphasisStyleChange: (PointerEmphasisStyle) -> Unit,
    pointerEmphasisScale: Float,
    onPointerEmphasisScaleChange: (Float) -> Unit,
    onPointerEmphasisScaleChangeFinished: () -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.ui_settings_interaction)) {
        SettingsChoiceChips(
            title = stringResource(R.string.ui_settings_select_key),
            description = stringResource(R.string.ui_settings_select_key_desc),
            selected = selectKeyBinding,
            options = listOf("", "Space", "Enter", "F8", "F9"),
            label = { it.ifBlank { stringResource(R.string.common_off) } },
            onSelect = onSelectKeyBindingChange,
        )
        SettingsGroupDivider()
        SettingsChoiceChips(
            title = stringResource(R.string.ui_settings_rest_key),
            description = stringResource(R.string.ui_settings_rest_key_desc),
            selected = restModeKeyBinding,
            options = listOf("", "Space", "Enter", "F8", "F9"),
            label = { it.ifBlank { stringResource(R.string.common_off) } },
            onSelect = onRestModeKeyBindingChange,
        )
    }

    SettingsGroup(title = stringResource(R.string.ui_settings_touch_timing)) {
        SettingsSlider(
            title = stringResource(R.string.ui_settings_hold_to_select_title),
            description = stringResource(R.string.ui_settings_hold_to_select_desc),
            value = holdToSelectMillis.toFloat(),
            onValueChange = { onHoldToSelectChange(it.toLong()) },
            onValueChangeFinished = onHoldToSelectChangeFinished,
            valueRange = 0f..2000f,
            steps = 19,
            valueLabel = "${holdToSelectMillis.toInt()} ms"
        )
        SettingsGroupDivider()
        SettingsSlider(
            title = stringResource(R.string.ui_settings_dwell_to_select_title),
            description = stringResource(R.string.ui_settings_dwell_to_select_desc),
            value = dwellToSelectMillis.toFloat(),
            onValueChange = { onDwellToSelectChange(it.toLong()) },
            onValueChangeFinished = onDwellToSelectChangeFinished,
            valueRange = 0f..5000f,
            steps = 19,
            valueLabel = "${dwellToSelectMillis.toInt()} ms"
        )
        SettingsGroupDivider()
        SettingsSlider(
            title = stringResource(R.string.ui_settings_selection_debounce_title),
            description = stringResource(R.string.ui_settings_selection_debounce_desc),
            value = selectionDebounceMillis.toFloat(),
            onValueChange = { onSelectionDebounceChange(it.toLong()) },
            onValueChangeFinished = onSelectionDebounceChangeFinished,
            valueRange = 0f..1000f,
            steps = 19,
            valueLabel = "${selectionDebounceMillis.toInt()} ms"
        )
    }

    SettingsGroup(title = stringResource(R.string.ui_settings_feedback)) {
        SettingsSwitch(
            checked = selectionSoundEnabled,
            onCheckedChange = onSelectionSoundChange,
            title = stringResource(R.string.ui_settings_selection_sound_title)
        )
        SettingsGroupDivider()
        SettingsChoiceChips(
            title = stringResource(R.string.ui_settings_speech_policy_title),
            description = stringResource(R.string.ui_settings_speech_policy_desc),
            selected = speechPolicy,
            options = SpeechPolicy.entries,
            label = { policy ->
                stringResource(
                    when (policy) {
                        SpeechPolicy.Immediate -> R.string.ui_settings_speech_policy_immediate
                        SpeechPolicy.SentenceOnly -> R.string.ui_settings_speech_policy_sentence_only
                    }
                )
            },
            onSelect = onSpeechPolicyChange
        )
        SettingsGroupDivider()
        SettingsSwitch(
            checked = auditoryFishingEnabled,
            onCheckedChange = onAuditoryFishingChange,
            title = stringResource(R.string.ui_settings_auditory_fishing_title),
            description = stringResource(R.string.ui_settings_auditory_fishing_desc)
        )
        SettingsGroupDivider()
        SettingsSlider(
            title = stringResource(R.string.ui_settings_selection_highlight_title),
            description = stringResource(R.string.ui_settings_selection_highlight_desc),
            value = selectionHighlightMillis.toFloat(),
            onValueChange = { onSelectionHighlightChange(it.toLong()) },
            onValueChangeFinished = onSelectionHighlightChangeFinished,
            valueRange = 0f..2000f,
            steps = 19,
            valueLabel = "${selectionHighlightMillis.toInt()} ms"
        )
    }

    SettingsGroup(title = stringResource(R.string.ui_settings_pointer_emphasis)) {
        SettingsChoiceChips(
            title = stringResource(R.string.ui_settings_pointer_style),
            description = stringResource(R.string.ui_settings_pointer_style_desc),
            selected = pointerEmphasisStyle,
            options = PointerEmphasisStyle.entries,
            label = { it.name },
            onSelect = onPointerEmphasisStyleChange,
        )
        SettingsGroupDivider()
        SettingsSlider(
            title = stringResource(R.string.ui_settings_pointer_size),
            description = stringResource(R.string.ui_settings_pointer_size_desc),
            value = pointerEmphasisScale,
            onValueChange = onPointerEmphasisScaleChange,
            onValueChangeFinished = onPointerEmphasisScaleChangeFinished,
            valueRange = 1f..3f,
            steps = 7,
            valueLabel = "%.1f×".format(pointerEmphasisScale),
        )
    }
}

// ─── Privacy Tab ─────────────────────────────────────────────────────────────

@Composable
private fun BackupSettingsGroup() {
    val koin = getKoin()
    val backupManager = remember(koin) { koin.getOrNull<CompleteBackupManager>() }
    val backupFilePicker = remember(koin) { koin.getOrNull<FilePicker>() }
    val backupShareService = remember(koin) { koin.getOrNull<ShareService>() }
    val backupScope = rememberCoroutineScope()
    var backupStatus by remember { mutableStateOf<String?>(null) }
    var backupWorking by remember { mutableStateOf(false) }
    var pendingRestorePath by remember { mutableStateOf<String?>(null) }
    val exported = stringResource(R.string.backup_exported)
    val restored = stringResource(R.string.backup_restored)
    val cancelled = stringResource(R.string.backup_cancelled)
    val backupPickerFailed = stringResource(R.string.backup_picker_failed)
    val backupCreateFailed = stringResource(R.string.backup_create_failed)

    SettingsGroup(title = stringResource(R.string.backup_title)) {
        Text(
            stringResource(R.string.backup_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !backupWorking && backupManager != null && backupShareService != null,
                onClick = {
                    backupWorking = true
                    backupScope.launch {
                        try {
                            val bytes = checkNotNull(backupManager).exportBackup()
                            backupStatus = if (checkNotNull(backupShareService).shareFile("wingmate-${Clock.System.now().toEpochMilliseconds()}.wingmate-backup", bytes)) {
                                exported
                            } else {
                                cancelled
                            }
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (_: Exception) {
                            backupStatus = backupCreateFailed
                        } finally {
                            backupWorking = false
                        }
                    }
                }
            ) { Text(stringResource(R.string.backup_create)) }
            OutlinedButton(
                enabled = !backupWorking && backupManager != null && backupFilePicker != null,
                onClick = {
                    backupScope.launch {
                        backupStatus = null
                        try {
                            pendingRestorePath = backupFilePicker?.pickFile(
                                title = "Restore Wingmate backup",
                                extensions = listOf("wingmate-backup", "zip")
                            )
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (_: Exception) {
                            backupStatus = backupPickerFailed
                        }
                    }
                }
            ) { Text(stringResource(R.string.backup_restore)) }
        }
        backupStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }

    val restorePath = pendingRestorePath
    if (restorePath != null) {
        AlertDialog(
            onDismissRequest = { pendingRestorePath = null },
            title = { Text(stringResource(R.string.backup_replace_title)) },
            text = { Text(stringResource(R.string.backup_replace_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestorePath = null
                    backupWorking = true
                    backupScope.launch {
                        backupStatus = when (val result = backupManager?.restoreBackup(restorePath)) {
                            is BackupRestoreResult.Success -> restored
                            is BackupRestoreResult.Failure -> result.message
                            null -> "Backup restore unavailable"
                        }
                        backupWorking = false
                    }
                }) { Text(stringResource(R.string.backup_replace_action)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestorePath = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

@Composable
private fun PrivacySection(
    historyVisible: Boolean,
    onHistoryVisibleChange: (Boolean) -> Unit,
    boardSets: List<ObfBoardSet>,
    onBoardSetSentenceCachingChange: (ObfBoardSet, Boolean) -> Unit,
    usageLoggingEnabled: Boolean,
    onUsageLoggingChange: (Boolean) -> Unit,
    featureUsageReportingEnabled: Boolean,
    onFeatureReportingChange: (Boolean) -> Unit
) {
    SettingsGroup(title = stringResource(R.string.ui_settings_privacy_local_data_title)) {
        SettingsSwitch(
            checked = historyVisible,
            onCheckedChange = onHistoryVisibleChange,
            title = stringResource(R.string.ui_settings_history_visible_title),
            description = stringResource(R.string.ui_settings_history_visible_desc)
        )
    }


    SettingsGroup(title = stringResource(R.string.ui_settings_board_cache_title)) {
        Text(
            stringResource(R.string.ui_settings_board_cache_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (boardSets.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.ui_settings_board_cache_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            boardSets.forEach { boardSet ->
                SettingsGroupDivider()
                SettingsSwitch(
                    checked = boardSet.cacheWholeSentences,
                    onCheckedChange = { onBoardSetSentenceCachingChange(boardSet, it) },
                    title = boardSet.name
                )
            }
        }
    }

    SettingsGroup(title = stringResource(R.string.ui_settings_privacy_collection_title)) {
        SettingsSwitch(
            checked = featureUsageReportingEnabled,
            onCheckedChange = onFeatureReportingChange,
            title = stringResource(R.string.ui_settings_feature_reporting_title),
            description = stringResource(R.string.ui_settings_feature_reporting_desc)
        )
        SettingsGroupDivider()
        SettingsSwitch(
            checked = usageLoggingEnabled,
            onCheckedChange = onUsageLoggingChange,
            title = stringResource(R.string.ui_settings_usage_logging_title),
            description = stringResource(R.string.ui_settings_usage_logging_desc)
        )
    }

}

// ─── General Tab ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeneralSection(
    onBackToWelcome: (() -> Unit)? = null,
    startupMode: StartupMode,
    startupBoardSetId: String?,
    availableBoardSets: List<ObfBoardSet>,
    onStartupModeChange: (StartupMode) -> Unit,
    onStartupBoardSetChange: (String?) -> Unit,
    partnerWindowEnabled: Boolean,
    partnerDeviceConnected: Boolean,
    onPartnerWindowChange: (Boolean) -> Unit,
    arasaacAvailable: Boolean,
    cachedArasaacSymbols: Int,
    arasaacProgress: ArasaacDownloadProgress?,
    arasaacDownloadError: Boolean,
    arasaacFailedCount: Int,
    onDownloadArasaac: () -> Unit
) {
    val editingAccessController = getKoin().getOrNull<EditingAccessController>()
    val editingAccessState by editingAccessController?.state?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(io.github.jdreioe.wingmate.application.EditingAccessState(supported = false)) }
    var editingAccessDialog by remember { mutableStateOf<EditingAccessDialogMode?>(null) }
    LaunchedEffect(editingAccessController) { editingAccessController?.refresh() }

    BackupSettingsGroup()

    SettingsGroup(title = stringResource(R.string.editing_access_title)) {
        Text(
            stringResource(R.string.editing_access_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        if (!editingAccessState.supported) {
            Text(stringResource(R.string.editing_access_unavailable), color = MaterialTheme.colorScheme.error)
        } else if (!editingAccessState.enabled) {
            OutlinedButton(
                onClick = { editingAccessDialog = EditingAccessDialogMode.Configure },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.editing_access_enable)) }
        } else {
            if (!editingAccessState.unlocked) {
                OutlinedButton(
                    onClick = { editingAccessDialog = EditingAccessDialogMode.Unlock },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.editing_access_unlock_title)) }
            }
            OutlinedButton(
                onClick = { editingAccessDialog = EditingAccessDialogMode.Configure },
                enabled = editingAccessState.unlocked,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.editing_access_change)) }
            OutlinedButton(
                onClick = { editingAccessDialog = EditingAccessDialogMode.Disable },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.editing_access_disable)) }
            OutlinedButton(
                onClick = { editingAccessController?.lock() },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.editing_access_lock_now)) }
        }
    }

    SettingsGroup(title = stringResource(R.string.ui_settings_startup_mode_title)) {
        Text(
            stringResource(R.string.ui_settings_startup_mode_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = startupMode == StartupMode.Keyboard,
                onClick = { onStartupModeChange(StartupMode.Keyboard) },
                label = { Text(stringResource(R.string.ui_settings_startup_mode_keyboard)) },
                leadingIcon = { Icon(Icons.Filled.Keyboard, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = startupMode == StartupMode.Screens,
                onClick = { onStartupModeChange(StartupMode.Screens) },
                label = { Text(stringResource(R.string.ui_settings_startup_mode_screens)) },
                leadingIcon = { Icon(Icons.Filled.GridView, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.weight(1f)
            )
        }
        if (startupMode == StartupMode.Screens) {
            var targetExpanded by remember { mutableStateOf(false) }
            val selectedName = availableBoardSets
                .firstOrNull { it.id == startupBoardSetId }
                ?.name
                ?: stringResource(R.string.ui_settings_startup_screen_library)

            Spacer(modifier = Modifier.height(12.dp))
            ExposedDropdownMenuBox(
                expanded = targetExpanded,
                onExpandedChange = { targetExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.ui_settings_startup_screen_title)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = targetExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                )
                ExposedDropdownMenu(
                    expanded = targetExpanded,
                    onDismissRequest = { targetExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ui_settings_startup_screen_library)) },
                        onClick = { onStartupBoardSetChange(null); targetExpanded = false }
                    )
                    availableBoardSets.forEach { boardSet ->
                        DropdownMenuItem(
                            text = { Text(boardSet.name) },
                            onClick = { onStartupBoardSetChange(boardSet.id); targetExpanded = false }
                        )
                    }
                }
            }
        }
    }

    SettingsGroup(title = stringResource(R.string.ui_settings_symbols_title)) {
        Text(
            stringResource(R.string.ui_settings_symbols_download_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        val activeProgress = arasaacProgress
        OutlinedButton(
            onClick = onDownloadArasaac,
            enabled = arasaacAvailable && activeProgress == null
        ) {
            if (activeProgress != null) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (activeProgress != null) R.string.ui_settings_symbols_downloading else R.string.ui_settings_symbols_download))
        }
        if (activeProgress != null && activeProgress.total > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(progress = { activeProgress.completed.toFloat() / activeProgress.total }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            when {
                !arasaacAvailable -> stringResource(R.string.ui_settings_symbols_unavailable)
                arasaacDownloadError -> pluralStringResource(
                    R.plurals.ui_settings_symbols_failed,
                    arasaacFailedCount,
                    arasaacFailedCount,
                )
                cachedArasaacSymbols > 0 -> pluralStringResource(
                    R.plurals.ui_settings_symbols_cached,
                    cachedArasaacSymbols,
                    cachedArasaacSymbols,
                )
                else -> stringResource(R.string.ui_settings_symbols_download_title)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (arasaacDownloadError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (onBackToWelcome != null) {
        SettingsGroup(title = stringResource(R.string.phrase_screen_welcome_screen)) {
            OutlinedButton(
                onClick = onBackToWelcome,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Home, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.phrase_screen_welcome_screen))
            }
        }
    }

    if (partnerDeviceConnected) {
        SettingsGroup(title = stringResource(R.string.ui_settings_partner_window_group)) {
            SettingsSwitch(
                checked = partnerWindowEnabled,
                onCheckedChange = onPartnerWindowChange,
                title = stringResource(R.string.ui_settings_partner_window_title),
                description = stringResource(R.string.ui_settings_partner_window_desc)
            )
        }
    }

    val dialogMode = editingAccessDialog
    if (editingAccessController != null && dialogMode != null) {
        EditingAccessDialog(
            controller = editingAccessController,
            mode = dialogMode,
            onDismiss = { editingAccessDialog = null },
            onSuccess = { editingAccessDialog = null }
        )
    }
}

// ─── Voice Selection Page ────────────────────────────────────────────────────

@Composable
internal fun VoiceSelectionPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onVoiceSelected: (() -> Unit)? = null
) {
    val koin = getKoin()
    val useCase = koinInject<VoiceUseCase>()
    val featureUsageReporter = koinInject<FeatureUsageReporter>()
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
    var voiceSearch by remember { mutableStateOf("") }
    var genderFilter by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val systemVoiceProvider = remember(koin) { koin.getOrNull<io.github.jdreioe.wingmate.infrastructure.SystemVoiceProvider>() }

    val voiceLoadFailed = stringResource(R.string.voice_load_failed)
    val voiceSaveFailed = stringResource(R.string.voice_save_failed)

    LaunchedEffect(retryKey) {
        loading = true
        error = null
        try {
            val settings = checkNotNull(settingsUseCase) { "Settings are unavailable" }
                .let { withContext(Dispatchers.Default) { it.get() } }
            ttsEngine = settings.ttsEngine
            if (ttsEngine == TtsEngine.SYSTEM) {
                val allSystemVoices = systemVoiceProvider?.getSystemVoices() ?: listOf(
                    Voice(name = "system-default", displayName = "System Default", primaryLanguage = "en-US", gender = "Unknown")
                )
                systemVoices = allSystemVoices
                availableLanguages = allSystemVoices.mapNotNull { it.primaryLanguage }.distinct().sorted()
                selected = useCase.selected()
            } else {
                var cloudRefreshFailed = false
                val fromCloud = try {
                    withContext(Dispatchers.Default) { useCase.refreshFromAzure() }
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
                availableLanguages = allVoices
                    .flatMap { voice -> listOfNotNull(voice.primaryLanguage) + (voice.supportedLanguages ?: emptyList()) }
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
        voiceSearch.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

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
        activeLanguageFilteredVoices.mapNotNull { it.gender?.trim()?.takeIf { gender -> gender.isNotEmpty() } }.distinct().sorted()
    }

    LaunchedEffect(availableGenders, genderFilter) {
        if (genderFilter != null && !availableGenders.contains(genderFilter)) {
            genderFilter = null
        }
    }

    val filteredSystemVoices = remember(languageFilteredSystemVoices, queryTerms, genderFilter) {
        languageFilteredSystemVoices.filter { voice -> matchesVoiceFilters(voice = voice, queryTerms = queryTerms, genderFilter = genderFilter) }
    }

    val filteredAzureVoices = remember(languageFilteredAzureVoices, queryTerms, genderFilter) {
        languageFilteredAzureVoices.filter { voice -> matchesVoiceFilters(voice = voice, queryTerms = queryTerms, genderFilter = genderFilter) }
    }

    val visibleVoiceCount = if (ttsEngine == TtsEngine.SYSTEM) filteredSystemVoices.size else filteredAzureVoices.size
    val totalVoiceCount = if (ttsEngine == TtsEngine.SYSTEM) systemVoices.size else voices.size

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
            languages = availableLanguages,
            selectedLanguage = selectedLanguage,
            genders = availableGenders,
            selectedGender = genderFilter,
            onLanguageSelected = { language ->
                selectedLanguage = language
                featureUsageReporter.reportEvent(
                    FeatureUsageEvents.VOICE_FILTER_APPLIED,
                    "filter" to "language",
                    "value" to if (language == null) "all" else "selected"
                )
            },
            onGenderSelected = { gender ->
                genderFilter = gender
                featureUsageReporter.reportEvent(
                    FeatureUsageEvents.VOICE_FILTER_APPLIED,
                    "filter" to "gender",
                    "value" to if (gender == null) "all" else "selected"
                )
            }
        )

        Text(
            pluralStringResource(
                R.plurals.voice_showing_count,
                totalVoiceCount,
                visibleVoiceCount,
                totalVoiceCount,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        operationError?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        if (loading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (error != null) {
            Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { retryKey++ }) {
                Text(stringResource(R.string.common_retry))
            }
        } else {
            val filteredVoices = if (ttsEngine == TtsEngine.SYSTEM) filteredSystemVoices else filteredAzureVoices
            val titleRes = if (ttsEngine == TtsEngine.SYSTEM) {
                if (selectedLanguage != null) R.string.voice_system_title_with_lang else R.string.voice_system_title
            } else {
                if (selectedLanguage != null) R.string.voice_azure_title_with_lang else R.string.voice_azure_title
            }
            val emptyRes = if (ttsEngine == TtsEngine.SYSTEM) R.string.voice_no_system_match else R.string.voice_no_azure_match

            SettingsGroup(title = stringResource(titleRes, selectedLanguage ?: "")) {
                if (filteredVoices.isEmpty()) {
                    Text(
                        stringResource(emptyRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    filteredVoices.forEachIndexed { index, v ->
                        VoiceRow(
                            voice = v,
                            isSelected = selected?.name == v.name,
                            showSettings = ttsEngine != TtsEngine.SYSTEM,
                            onSelect = {
                                scope.launch {
                                    operationError = null
                                    try {
                                        useCase.select(v)
                                        val primary = if (ttsEngine == TtsEngine.SYSTEM) (v.primaryLanguage ?: "") else v.selectedLanguage.ifBlank { v.primaryLanguage ?: "" }
                                        if (primary.isNotBlank() && settingsUseCase != null) {
                                            val current = settingsUseCase.get()
                                            settingsUseCase.update(current.copy(primaryLanguage = primary))
                                        }
                                        selected = v
                                        onVoiceSelected?.invoke() ?: onBack()
                                    } catch (failure: CancellationException) {
                                        throw failure
                                    } catch (_: Exception) {
                                        operationError = voiceSaveFailed
                                    }
                                }
                            },
                            onSettings = {
                                editingVoice = v
                                showVoiceSettings = true
                            }
                        )
                        if (index < filteredVoices.lastIndex) {
                            SettingsGroupDivider()
                        }
                    }
                }
            }
        }
    }

    if (showVoiceSettings && editingVoice != null) {
        VoiceSettingsDialog(
            show = true,
            voice = editingVoice!!,
            onDismiss = { showVoiceSettings = false },
            onSave = { updated ->
                scope.launch {
                    operationError = null
                    try {
                        useCase.select(updated)
                        val primary = updated.selectedLanguage.ifBlank { updated.primaryLanguage ?: "" }
                        if (primary.isNotBlank() && settingsUseCase != null) {
                            val current = settingsUseCase.get()
                            settingsUseCase.update(current.copy(primaryLanguage = primary))
                        }
                        showVoiceSettings = false
                        voices = (useCase.refreshFromAzure() + useCase.list()).distinctBy { it.name }
                        selected = useCase.selected()
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: Exception) {
                        operationError = voiceSaveFailed
                    }
                }
            }
        )
    }
}

@Composable
private fun VoiceRow(
    voice: Voice,
    isSelected: Boolean,
    showSettings: Boolean,
    onSelect: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = voice.displayName ?: voice.name ?: stringResource(R.string.common_unknown), style = MaterialTheme.typography.bodyLarge)
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
    if (genders.isNotEmpty()) {
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

// ─── Language Selection Page ─────────────────────────────────────────────────

@Composable
internal fun LanguageSelectionPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onContinue: (() -> Unit)? = null
) {
    val voiceUseCase = koinInject<VoiceUseCase>()
    val settingsUseCase = koinInject<SettingsUseCase>()
    val featureUsageReporter = koinInject<FeatureUsageReporter>()
    val scope = rememberCoroutineScope()
    val allLabel = stringResource(R.string.language_all)
    val noLanguagesAvailableLabel = stringResource(R.string.language_no_available)
    val noLanguagesMatchLabel = stringResource(R.string.language_no_match)

    var available by remember { mutableStateOf<List<String>>(emptyList()) }
    var filter by remember { mutableStateOf("") }
    var primary by remember { mutableStateOf("en-US") }
    var secondary by remember { mutableStateOf("") }
    var selectedVoiceIsMultilingual by remember { mutableStateOf(false) }
    var useSecondaryLanguage by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var operationError by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }
    val languageLoadFailed = stringResource(R.string.language_load_failed)
    val languageSaveFailed = stringResource(R.string.language_save_failed)

    LaunchedEffect(retryKey) {
        loading = true
        loadError = null
        try {
            val settings = settingsUseCase.get()
            val sel = voiceUseCase.selected()
            primary = settings.primaryLanguage
            secondary = settings.secondaryLanguage
            selectedVoiceIsMultilingual = sel?.supportedLanguages
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.distinct()
                ?.size
                ?.let { it > 1 }
                ?: false
            useSecondaryLanguage = selectedVoiceIsMultilingual &&
                settings.secondaryLanguage.isNotBlank() &&
                settings.secondaryLanguage != settings.primaryLanguage
            available = (sel?.supportedLanguages ?: emptyList())
                .ifEmpty { listOf(settings.primaryLanguage, settings.secondaryLanguage, "en-US").filter { it.isNotBlank() } }
                .distinct()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            loadError = languageLoadFailed
        } finally {
            loading = false
        }
    }

    if (loading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    loadError?.let { message ->
        Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(message, color = MaterialTheme.colorScheme.error)
            Button(onClick = { retryKey++ }) { Text(stringResource(R.string.common_retry)) }
        }
        return
    }

    val normalizedAvailable = remember(available) {
        available.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
    }

    val queryTerms = remember(filter) {
        filter.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    val filteredLanguages = remember(normalizedAvailable, queryTerms) {
        normalizedAvailable.filter { lang ->
            val codePart = languageCodePart(lang)
            val regionPart = regionCodePart(lang)
            val matchesSearch = queryTerms.all { term ->
                lang.contains(term, ignoreCase = true) ||
                    localizedLocaleDisplayName(lang).contains(term, ignoreCase = true) ||
                    codePart.contains(term, ignoreCase = true) ||
                    (regionPart?.contains(term, ignoreCase = true) == true)
            }
            matchesSearch
        }
    }

    fun updateLanguage(target: String, value: String) {
        scope.launch {
            operationError = null
            var previous: Settings? = null
            try {
                val current = settingsUseCase.get()
                previous = current
                val updated = if (target == "primary") current.copy(primaryLanguage = value) else current.copy(secondaryLanguage = value)
                settingsUseCase.update(updated)
                featureUsageReporter.reportEvent(
                    FeatureUsageEvents.LANGUAGE_UPDATED,
                    "target" to target,
                    "value" to value
                )
                if (target == "primary") {
                    voiceUseCase.selected()?.let { voiceUseCase.select(it.copy(selectedLanguage = value)) }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                val persisted = try {
                    settingsUseCase.get()
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    previous
                }
                persisted?.let {
                    primary = it.primaryLanguage
                    secondary = it.secondaryLanguage
                    useSecondaryLanguage = selectedVoiceIsMultilingual &&
                        it.secondaryLanguage.isNotBlank() &&
                        it.secondaryLanguage != it.primaryLanguage
                }
                operationError = languageSaveFailed
            }
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(top = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        operationError?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
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
                selected = primary,
                emptyLabel = if (normalizedAvailable.isEmpty()) noLanguagesAvailableLabel else noLanguagesMatchLabel,
                onSelect = { sel -> primary = sel; updateLanguage("primary", sel) }
            )
        }

        if (selectedVoiceIsMultilingual) {
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
                        checked = useSecondaryLanguage,
                        onCheckedChange = { enabled ->
                            useSecondaryLanguage = enabled
                            featureUsageReporter.reportEvent(
                                FeatureUsageEvents.SECONDARY_LANGUAGE_TOGGLED,
                                "enabled" to enabled.toString(),
                                "source" to "language_selection"
                            )
                            if (enabled) {
                                val initial = normalizedAvailable.firstOrNull { it != primary }
                                    ?: normalizedAvailable.firstOrNull()
                                    ?: primary
                                secondary = initial
                                updateLanguage("secondary", initial)
                            } else {
                                secondary = ""
                                updateLanguage("secondary", "")
                            }
                        }
                    )
                }
                if (useSecondaryLanguage) {
                    Spacer(Modifier.height(8.dp))
                    LanguageList(
                        available = filteredLanguages,
                        selected = secondary,
                        emptyLabel = if (normalizedAvailable.isEmpty()) noLanguagesAvailableLabel else noLanguagesMatchLabel,
                        onSelect = { sel -> secondary = sel; updateLanguage("secondary", sel) }
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

// ─── Reusable Components ─────────────────────────────────────────────────────

@Composable
internal fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
            )
            content()
        }
    }
}

@Composable
internal fun SettingsGroupDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    )
}

@Composable
internal fun SettingsNavRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SettingsPreferenceRow(
    title: String,
    subtitle: String,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(16.dp))
        content()
    }
}

@Composable
internal fun SettingsSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    description: String? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingsSlider(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: String,
    description: String? = null
) {
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(valueLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (description != null) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ScaleSlider(label: String, value: Float, onValueChange: (Float) -> Unit) {
    val scope = rememberCoroutineScope()
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("${(value * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value,
            onValueChange = { newValue ->
                val stepped = (newValue * 10).toInt() / 10f
                onValueChange(stepped)
            },
            valueRange = 0.5f..2.0f,
            steps = 14,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
