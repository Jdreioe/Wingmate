package io.github.jdreioe.wingmate.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.application.FeatureUsageEvents
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.application.reportEvent
import io.github.jdreioe.wingmate.application.BoardSetUseCase
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.SettingsStateManager
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository
import io.github.jdreioe.wingmate.domain.PronunciationEntry
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.StartupMode
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.infrastructure.ObfParser
import io.github.jdreioe.wingmate.infrastructure.ArasaacDownloadProgress
import io.github.jdreioe.wingmate.infrastructure.ArasaacSymbolDownloadService
import io.github.jdreioe.wingmate.infrastructure.ImageCacher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import wingmatekmp.composeapp.generated.resources.*

private enum class SettingsTab { Speech, Display, Accessibility, General }

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
    onBaseBoardsRestored: (() -> Unit)? = null,
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
    val obfParser = remember(koin) { koin.getOrNull<ObfParser>() }
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
    var gridColumns by remember { mutableStateOf(3) }
    var highContrastMode by remember { mutableStateOf(false) }

    // --- Accessibility section state ---
    var holdToSelectMillis by remember { mutableStateOf(0L) }
    var dwellToSelectMillis by remember { mutableStateOf(0L) }
    var selectionSoundEnabled by remember { mutableStateOf(false) }
    var auditoryFishingEnabled by remember { mutableStateOf(false) }
    var usageLoggingEnabled by remember { mutableStateOf(false) }

    // --- General section state ---
    var featureUsageReportingEnabled by remember { mutableStateOf(false) }
    var partnerWindowEnabled by remember { mutableStateOf(false) }
    var startupMode by remember { mutableStateOf(StartupMode.Keyboard) }
    var startupBoardSetId by remember { mutableStateOf<String?>(null) }
    var availableBoardSets by remember { mutableStateOf<List<ObfBoardSet>>(emptyList()) }
    var restoringBaseBoards by remember { mutableStateOf(false) }
    var baseBoardsStatus by remember { mutableStateOf<String?>(null) }
    var cachedArasaacSymbols by remember { mutableStateOf(0) }
    var arasaacProgress by remember { mutableStateOf<ArasaacDownloadProgress?>(null) }
    var arasaacDownloadError by remember { mutableStateOf(false) }
    var arasaacFailedCount by remember { mutableStateOf(0) }

    var showPronunciationDictionary by remember { mutableStateOf(false) }
    var dictionaryEntries by remember { mutableStateOf<List<PronunciationEntry>>(emptyList()) }

    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val settingsUpdateMutex = remember { Mutex() }
    val baseBoardsRestoredMessage = stringResource(Res.string.ui_settings_base_boards_restored)
    val baseBoardsErrorMessage = stringResource(Res.string.ui_settings_base_boards_error)

    // Partner window device detection (desktop-only)
    val partnerDeviceConnected by PartnerWindowAvailability.deviceConnected.collectAsStateWithLifecycle()

    // Helper to update settings reactively
    fun updateSettings(update: (Settings) -> Settings) {
        scope.launch {
            settingsUpdateMutex.withLock {
                if (settingsStateManager != null) {
                    settingsStateManager.updateSettings(update)
                } else {
                    settingsUseCase?.let { useCase ->
                        withContext(Dispatchers.Default) {
                            val current = runCatching { useCase.get() }.getOrNull() ?: Settings()
                            useCase.update(update(current))
                        }
                    }
                }
            }
        }
    }

    // Load all settings on first composition
    LaunchedEffect(Unit) {
        val cfg = withContext(Dispatchers.Default) { configRepo?.getSpeechConfig() }
        cfg?.let {
            endpoint = it.endpoint
            subscriptionKey = it.subscriptionKey
        }

        val s = withContext(Dispatchers.Default) {
            runCatching { settingsUseCase?.get() }.getOrNull() ?: Settings()
        }
        ttsEngine = s.ttsEngine
        virtualMic = s.virtualMicEnabled
        featureUsageReportingEnabled = s.featureUsageReportingEnabled
        partnerWindowEnabled = s.partnerWindowEnabled
        startupMode = s.startupMode
        startupBoardSetId = s.startupBoardSetId
        availableBoardSets = withContext(Dispatchers.Default) {
            runCatching { boardSetUseCase?.listBoardSets().orEmpty() }.getOrDefault(emptyList())
        }
        showLabels = s.showLabels
        showSymbols = s.showSymbols
        labelAtTop = s.labelAtTop
        holdToSelectMillis = s.holdToSelectMillis
        gridColumns = s.gridColumns
        highContrastMode = s.highContrastMode
        dwellToSelectMillis = s.dwellToSelectMillis
        selectionSoundEnabled = s.selectionSoundEnabled
        auditoryFishingEnabled = s.auditoryFishingEnabled
        usageLoggingEnabled = s.usageLoggingEnabled
        fontSizeScale = s.fontSizeScale
        playbackIconScale = s.playbackIconScale
        categoryChipScale = s.categoryChipScale
        buttonScale = s.buttonScale
        inputFieldScale = s.inputFieldScale
        featureUsageReporter?.setEnabled(s.featureUsageReportingEnabled)
        cachedArasaacSymbols = runCatching { arasaacDownloader?.cachedCount() ?: 0 }.getOrDefault(0)
        loading = false
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
    LaunchedEffect(endpoint, subscriptionKey, loading) {
        if (!loading) {
            delay(400)
            runCatching {
                configRepo?.saveSpeechConfig(
                    SpeechServiceConfig(endpoint = endpoint, subscriptionKey = subscriptionKey)
                )
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
                            showPronunciationDictionary -> stringResource(Res.string.dictionary_title)
                            speechSubPage is SettingsSpeechSubPage.VoiceSelection -> stringResource(Res.string.voice_select_title)
                            speechSubPage is SettingsSpeechSubPage.LanguageSelection -> stringResource(Res.string.language_dialog_title)
                            selectedTab != null -> settingsCategoryTitle(selectedTab!!)
                            else -> stringResource(Res.string.ui_settings_title)
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = ::handleBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.common_close))
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
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
                                    val voice = runCatching { voiceUseCase?.selected() }.getOrNull()
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
                                val voice = runCatching { voiceUseCase?.selected() }.getOrNull()
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
                                    onSelectCategory = { selectedTab = it },
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
                                            onDone = { speechSubPage = null },
                                            onManualByok = { speechSubPage = null },
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
                                virtualMic = virtualMic,
                                onVirtualMicChange = { checked ->
                                    virtualMic = checked
                                    updateSettings { it.copy(virtualMicEnabled = checked) }
                                },
                                onOpenVoiceSelection = { speechSubPage = SettingsSpeechSubPage.VoiceSelection },
                                onOpenLanguageSelection = { speechSubPage = SettingsSpeechSubPage.LanguageSelection },
                                onOpenF0Setup = { speechSubPage = SettingsSpeechSubPage.F0Setup }
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
                                        onShowLabelsChange = { checked -> showLabels = checked; updateSettings { it.copy(showLabels = checked) } },
                                        showSymbols = showSymbols,
                                        onShowSymbolsChange = { checked -> showSymbols = checked; updateSettings { it.copy(showSymbols = checked) } },
                                        labelAtTop = labelAtTop,
                                        onLabelAtTopChange = { checked -> labelAtTop = checked; updateSettings { it.copy(labelAtTop = checked) } },
                                        gridColumns = gridColumns,
                                        onGridColumnsChange = { gridColumns = it },
                                        onGridColumnsChangeFinished = { updateSettings { it.copy(gridColumns = gridColumns) } },
                                        highContrastMode = highContrastMode,
                                        onHighContrastModeChange = { checked -> highContrastMode = checked; updateSettings { it.copy(highContrastMode = checked) } }
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
                                        usageLoggingEnabled = usageLoggingEnabled,
                                        onUsageLoggingChange = { checked -> usageLoggingEnabled = checked; updateSettings { it.copy(usageLoggingEnabled = checked) } }
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
                                        featureUsageReportingEnabled = featureUsageReportingEnabled,
                                        onFeatureReportingChange = { checked ->
                                            featureUsageReportingEnabled = checked
                                            updateSettings { it.copy(featureUsageReportingEnabled = checked) }
                                            featureUsageReporter?.setEnabled(checked)
                                            featureUsageReporter?.reportEvent(
                                                FeatureUsageEvents.ANALYTICS_CONSENT_CHANGED,
                                                "enabled" to checked.toString(),
                                                "source" to "settings_screen"
                                            )
                                        },
                                        partnerWindowEnabled = partnerWindowEnabled,
                                        partnerDeviceConnected = partnerDeviceConnected,
                                        onPartnerWindowChange = { checked -> partnerWindowEnabled = checked; updateSettings { it.copy(partnerWindowEnabled = checked) } },
                                        restoringBaseBoards = restoringBaseBoards,
                                        baseBoardsStatus = baseBoardsStatus,
                                        onRestoreBaseBoards = {
                                            if (!restoringBaseBoards) {
                                                scope.launch {
                                                    restoringBaseBoards = true
                                                    baseBoardsStatus = null
                                                    val restored = runCatching {
                                                        val parser = obfParser ?: error(baseBoardsErrorMessage)
                                                        val useCase = boardSetUseCase ?: error(baseBoardsErrorMessage)
                                                        restoreStarterBoards(systemLanguageTag(), parser, useCase)
                                                            ?: error(baseBoardsErrorMessage)
                                                    }
                                                    baseBoardsStatus = restored.fold(
                                                        onSuccess = {
                                                            onBaseBoardsRestored?.invoke()
                                                            baseBoardsRestoredMessage
                                                        },
                                                        onFailure = { it.message ?: baseBoardsErrorMessage }
                                                    )
                                                    availableBoardSets = runCatching {
                                                        boardSetUseCase?.listBoardSets().orEmpty()
                                                    }.getOrDefault(availableBoardSets)
                                                    restoringBaseBoards = false
                                                }
                                            }
                                        },
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
    SettingsTab.Speech -> stringResource(Res.string.ui_settings_speech_title)
    SettingsTab.Display -> stringResource(Res.string.ui_settings_display_title)
    SettingsTab.Accessibility -> stringResource(Res.string.ui_settings_accessibility_title)
    SettingsTab.General -> stringResource(Res.string.ui_settings_general_title)
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
    val speechTitle = stringResource(Res.string.ui_settings_speech_title)
    val displayTitle = stringResource(Res.string.ui_settings_display_title)
    val accessibilityTitle = stringResource(Res.string.ui_settings_accessibility_title)
    val generalTitle = stringResource(Res.string.ui_settings_general_title)

    val categories = listOf(
        SettingsCategoryItem(
            tab = SettingsTab.Speech,
            title = speechTitle,
            subtitle = stringResource(Res.string.ui_settings_speech_desc),
            icon = Icons.Filled.RecordVoiceOver,
            iconContainerColor = Color(0xFF78D6F7),
            iconColor = Color(0xFF004E65),
            keywords = listOf(
                "tts", "azure", "system tts", "engine", "endpoint", "subscription",
                "region", "key", "voice", stringResource(Res.string.phrase_screen_voice_settings),
                stringResource(Res.string.voice_select_title), stringResource(Res.string.common_language),
                stringResource(Res.string.ui_settings_virtual_mic_title),
                stringResource(Res.string.ui_settings_virtual_mic_desc),
                "microphone", "zoom", "meet", "language"
            )
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = displayTitle,
            subtitle = stringResource(Res.string.ui_settings_display_desc),
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf(
                "grid", "layout", "scaling", "ui scaling", "font", "font size",
                "playback", "icons", "category", "chips", "buttons", "input",
                "fields", stringResource(Res.string.ui_settings_show_labels_title),
                stringResource(Res.string.ui_settings_show_symbols_title),
                stringResource(Res.string.ui_settings_label_at_top_title),
                stringResource(Res.string.ui_settings_grid_columns_title),
                stringResource(Res.string.ui_settings_high_contrast_title),
                "contrast", "symbols", "labels"
            )
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Accessibility,
            title = accessibilityTitle,
            subtitle = stringResource(Res.string.ui_settings_accessibility_desc),
            icon = Icons.Filled.Accessibility,
            iconContainerColor = Color(0xFFFFA8D8),
            iconColor = Color(0xFF700044),
            keywords = listOf(
                "touch", "timing", "feedback", "logging", "obl",
                stringResource(Res.string.ui_settings_hold_to_select_title),
                stringResource(Res.string.ui_settings_hold_to_select_desc),
                stringResource(Res.string.ui_settings_dwell_to_select_title),
                stringResource(Res.string.ui_settings_dwell_to_select_desc),
                stringResource(Res.string.ui_settings_selection_sound_title),
                stringResource(Res.string.ui_settings_auditory_fishing_title),
                stringResource(Res.string.ui_settings_auditory_fishing_desc),
                stringResource(Res.string.ui_settings_usage_logging_title),
                stringResource(Res.string.ui_settings_usage_logging_desc),
                "hold", "dwell", "hover", "sound", "whisper"
            )
        ),
        SettingsCategoryItem(
            tab = SettingsTab.General,
            title = generalTitle,
            subtitle = stringResource(Res.string.ui_settings_general_desc),
            icon = Icons.Filled.Storage,
            iconContainerColor = Color(0xFFA9D49A),
            iconColor = Color(0xFF1D4E18),
            keywords = listOf(
                stringResource(Res.string.ui_settings_startup_mode_title),
                stringResource(Res.string.ui_settings_startup_mode_desc),
                stringResource(Res.string.ui_settings_startup_mode_keyboard),
                stringResource(Res.string.ui_settings_startup_mode_screens),
                stringResource(Res.string.ui_settings_startup_screen_title),
                stringResource(Res.string.ui_settings_startup_screen_library),
                stringResource(Res.string.ui_settings_base_boards_title),
                stringResource(Res.string.ui_settings_base_boards_desc),
                stringResource(Res.string.ui_settings_base_boards_restore),
                stringResource(Res.string.ui_settings_symbols_title),
                stringResource(Res.string.ui_settings_symbols_download_title),
                stringResource(Res.string.ui_settings_symbols_download_desc),
                stringResource(Res.string.ui_settings_symbols_download),
                stringResource(Res.string.ui_settings_analytics_title),
                stringResource(Res.string.ui_settings_feature_reporting_title),
                stringResource(Res.string.ui_settings_feature_reporting_desc),
                stringResource(Res.string.ui_settings_partner_window_title),
                stringResource(Res.string.ui_settings_partner_window_desc),
                stringResource(Res.string.phrase_screen_welcome_screen),
                "startup", "privacy", "analytics", "arasaac", "offline",
                "partner", "welcome", "restore", "boards", "screens"
            )
        )
    )
    val pronunciationItem = SettingsCategoryItem(
        tab = null,
        title = stringResource(Res.string.dictionary_title),
        subtitle = stringResource(Res.string.dictionary_description),
        icon = Icons.AutoMirrored.Filled.MenuBook,
        iconContainerColor = Color(0xFFB39DDB),
        iconColor = Color(0xFF4A148C),
        openPronunciation = true,
        keywords = listOf(
            "pronunciation", "dictionary", "phoneme", "ipa", "speech",
            stringResource(Res.string.dictionary_word_label),
            stringResource(Res.string.dictionary_phoneme_label),
            stringResource(Res.string.dictionary_add_entry_title)
        )
    )

    val subSettings = listOf(
        // Speech
        SettingsCategoryItem(
            tab = SettingsTab.Speech,
            title = "Text-to-Speech Engine",
            subtitle = speechTitle,
            icon = Icons.Filled.RecordVoiceOver,
            iconContainerColor = Color(0xFF78D6F7),
            iconColor = Color(0xFF004E65),
            keywords = listOf("tts", "azure", "system tts", "engine", "endpoint", "subscription", "region", "key")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Speech,
            title = stringResource(Res.string.phrase_screen_voice_settings),
            subtitle = speechTitle,
            icon = Icons.Filled.RecordVoiceOver,
            iconContainerColor = Color(0xFF78D6F7),
            iconColor = Color(0xFF004E65),
            keywords = listOf(
                stringResource(Res.string.voice_select_title),
                stringResource(Res.string.common_language),
                "voice", "language"
            )
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Speech,
            title = stringResource(Res.string.ui_settings_virtual_mic_title),
            subtitle = stringResource(Res.string.ui_settings_virtual_mic_desc),
            icon = Icons.Filled.RecordVoiceOver,
            iconContainerColor = Color(0xFF78D6F7),
            iconColor = Color(0xFF004E65),
            keywords = listOf("microphone", "zoom", "meet", "virtual")
        ),
        // Display
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(Res.string.ui_settings_show_labels_title),
            subtitle = displayTitle,
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("labels", "grid", "layout")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(Res.string.ui_settings_show_symbols_title),
            subtitle = displayTitle,
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("symbols", "images", "grid")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(Res.string.ui_settings_label_at_top_title),
            subtitle = displayTitle,
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000)
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(Res.string.ui_settings_grid_columns_title),
            subtitle = displayTitle,
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("grid", "columns", "layout")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(Res.string.ui_settings_high_contrast_title),
            subtitle = displayTitle,
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("contrast", "accessibility")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = "Font Size",
            subtitle = displayTitle,
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("ui scaling", "font", "text size", "scale")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = "Playback Icons",
            subtitle = displayTitle,
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("ui scaling", "icons", "scale")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = "Category Chips",
            subtitle = displayTitle,
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("ui scaling", "category", "chips", "scale")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = "Buttons",
            subtitle = displayTitle,
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("ui scaling", "button", "scale")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = "Input Fields",
            subtitle = displayTitle,
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("ui scaling", "input", "text field", "scale")
        ),
        // Accessibility
        SettingsCategoryItem(
            tab = SettingsTab.Accessibility,
            title = stringResource(Res.string.ui_settings_hold_to_select_title),
            subtitle = stringResource(Res.string.ui_settings_hold_to_select_desc),
            icon = Icons.Filled.Accessibility,
            iconContainerColor = Color(0xFFFFA8D8),
            iconColor = Color(0xFF700044),
            keywords = listOf("hold", "touch", "timing")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Accessibility,
            title = stringResource(Res.string.ui_settings_dwell_to_select_title),
            subtitle = stringResource(Res.string.ui_settings_dwell_to_select_desc),
            icon = Icons.Filled.Accessibility,
            iconContainerColor = Color(0xFFFFA8D8),
            iconColor = Color(0xFF700044),
            keywords = listOf("dwell", "hover", "timing")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Accessibility,
            title = stringResource(Res.string.ui_settings_selection_sound_title),
            subtitle = accessibilityTitle,
            icon = Icons.Filled.Accessibility,
            iconContainerColor = Color(0xFFFFA8D8),
            iconColor = Color(0xFF700044),
            keywords = listOf("sound", "feedback")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Accessibility,
            title = stringResource(Res.string.ui_settings_auditory_fishing_title),
            subtitle = stringResource(Res.string.ui_settings_auditory_fishing_desc),
            icon = Icons.Filled.Accessibility,
            iconContainerColor = Color(0xFFFFA8D8),
            iconColor = Color(0xFF700044),
            keywords = listOf("whisper", "audio", "feedback")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Accessibility,
            title = stringResource(Res.string.ui_settings_usage_logging_title),
            subtitle = stringResource(Res.string.ui_settings_usage_logging_desc),
            icon = Icons.Filled.Accessibility,
            iconContainerColor = Color(0xFFFFA8D8),
            iconColor = Color(0xFF700044),
            keywords = listOf("obl", "logging", "clinical")
        ),
        // General
        SettingsCategoryItem(
            tab = SettingsTab.General,
            title = stringResource(Res.string.ui_settings_startup_mode_title),
            subtitle = stringResource(Res.string.ui_settings_startup_mode_desc),
            icon = Icons.Filled.Storage,
            iconContainerColor = Color(0xFFA9D49A),
            iconColor = Color(0xFF1D4E18),
            keywords = listOf(
                stringResource(Res.string.ui_settings_startup_mode_keyboard),
                stringResource(Res.string.ui_settings_startup_mode_screens),
                stringResource(Res.string.ui_settings_startup_screen_title),
                "startup", "open"
            )
        ),
        SettingsCategoryItem(
            tab = SettingsTab.General,
            title = stringResource(Res.string.ui_settings_base_boards_title),
            subtitle = stringResource(Res.string.ui_settings_base_boards_desc),
            icon = Icons.Filled.Storage,
            iconContainerColor = Color(0xFFA9D49A),
            iconColor = Color(0xFF1D4E18),
            keywords = listOf(
                stringResource(Res.string.ui_settings_base_boards_restore),
                "starter", "restore", "boards"
            )
        ),
        SettingsCategoryItem(
            tab = SettingsTab.General,
            title = stringResource(Res.string.ui_settings_symbols_title),
            subtitle = stringResource(Res.string.ui_settings_symbols_download_desc),
            icon = Icons.Filled.Storage,
            iconContainerColor = Color(0xFFA9D49A),
            iconColor = Color(0xFF1D4E18),
            keywords = listOf(
                stringResource(Res.string.ui_settings_symbols_download_title),
                "arasaac", "offline", "download", "symbols"
            )
        ),
        SettingsCategoryItem(
            tab = SettingsTab.General,
            title = stringResource(Res.string.ui_settings_feature_reporting_title),
            subtitle = stringResource(Res.string.ui_settings_feature_reporting_desc),
            icon = Icons.Filled.Storage,
            iconContainerColor = Color(0xFFA9D49A),
            iconColor = Color(0xFF1D4E18),
            keywords = listOf(
                stringResource(Res.string.ui_settings_analytics_title),
                "analytics", "privacy", "telemetry"
            )
        ),
        SettingsCategoryItem(
            tab = SettingsTab.General,
            title = stringResource(Res.string.phrase_screen_welcome_screen),
            subtitle = generalTitle,
            icon = Icons.Filled.Storage,
            iconContainerColor = Color(0xFFA9D49A),
            iconColor = Color(0xFF1D4E18),
            keywords = listOf("welcome", "onboarding")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.General,
            title = stringResource(Res.string.ui_settings_partner_window_title),
            subtitle = stringResource(Res.string.ui_settings_partner_window_desc),
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
            placeholder = { Text(stringResource(Res.string.ui_settings_search)) },
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
                text = stringResource(Res.string.ui_settings_search_empty),
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
    virtualMic: Boolean,
    onVirtualMicChange: (Boolean) -> Unit,
    onOpenVoiceSelection: () -> Unit = {},
    onOpenLanguageSelection: () -> Unit = {},
    onOpenF0Setup: () -> Unit = {}
) {
    val showKeyboard = rememberShowKeyboardOnFocus()

    SettingsGroup(title = "Text-to-Speech Engine") {
        SettingsPreferenceRow(
            title = "Speech engine",
            subtitle = if (ttsEngine == TtsEngine.SYSTEM) "System TTS" else "Azure TTS"
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = ttsEngine != TtsEngine.SYSTEM,
                    onClick = { onTtsEngineChange(TtsEngine.AZURE_USER_RESOURCE) },
                    label = { Text("Azure") }
                )
                FilterChip(
                    selected = ttsEngine == TtsEngine.SYSTEM,
                    onClick = { onTtsEngineChange(TtsEngine.SYSTEM) },
                    label = { Text("System") }
                )
            }
        }
        if (ttsEngine != TtsEngine.SYSTEM) {
            SettingsGroupDivider()
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = onEndpointChange,
                    label = { Text("Region / Endpoint") },
                    placeholder = { Text("e.g., eastus") },
                    modifier = Modifier.fillMaxWidth().then(showKeyboard),
                    shape = MaterialTheme.shapes.large
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = subscriptionKey,
                    onValueChange = onSubscriptionKeyChange,
                    label = { Text("Subscription Key") },
                    modifier = Modifier.fillMaxWidth().then(showKeyboard),
                    shape = MaterialTheme.shapes.large
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onOpenF0Setup,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Set up automatically (Microsoft sign-in)")
        }
    }

    SettingsGroup(title = stringResource(Res.string.phrase_screen_voice_settings)) {
        SettingsNavRow(
            title = stringResource(Res.string.voice_select_title),
            subtitle = stringResource(Res.string.ui_settings_speech_desc),
            icon = Icons.Filled.RecordVoiceOver,
            onClick = onOpenVoiceSelection
        )
        SettingsGroupDivider()
        SettingsNavRow(
            title = stringResource(Res.string.common_language),
            subtitle = stringResource(Res.string.ui_settings_speech_desc),
            icon = Icons.Filled.Language,
            onClick = onOpenLanguageSelection
        )
        if (isDesktop()) {
            SettingsGroupDivider()
            SettingsSwitch(
                checked = virtualMic,
                onCheckedChange = onVirtualMicChange,
                title = stringResource(Res.string.ui_settings_virtual_mic_title),
                description = stringResource(Res.string.ui_settings_virtual_mic_desc)
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
    gridColumns: Int,
    onGridColumnsChange: (Int) -> Unit,
    onGridColumnsChangeFinished: () -> Unit,
    highContrastMode: Boolean,
    onHighContrastModeChange: (Boolean) -> Unit
) {
    SettingsGroup(title = "Grid Layout") {
        SettingsSwitch(
            checked = showLabels,
            onCheckedChange = onShowLabelsChange,
            title = stringResource(Res.string.ui_settings_show_labels_title)
        )
        SettingsGroupDivider()
        SettingsSwitch(
            checked = showSymbols,
            onCheckedChange = onShowSymbolsChange,
            title = stringResource(Res.string.ui_settings_show_symbols_title)
        )
        if (showLabels && showSymbols) {
            SettingsGroupDivider()
            SettingsSwitch(
                checked = labelAtTop,
                onCheckedChange = onLabelAtTopChange,
                title = stringResource(Res.string.ui_settings_label_at_top_title)
            )
        }
        SettingsGroupDivider()
        SettingsSlider(
            title = stringResource(Res.string.ui_settings_grid_columns_title),
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
            title = stringResource(Res.string.ui_settings_high_contrast_title)
        )
    }

    SettingsGroup(title = "UI Scaling") {
        ScaleSlider("Font Size", fontSizeScale, onFontSizeScaleChange)
        SettingsGroupDivider()
        ScaleSlider("Playback Icons", playbackIconScale, onPlaybackIconScaleChange)
        SettingsGroupDivider()
        ScaleSlider("Category Chips", categoryChipScale, onCategoryChipScaleChange)
        SettingsGroupDivider()
        ScaleSlider("Buttons", buttonScale, onButtonScaleChange)
        SettingsGroupDivider()
        ScaleSlider("Input Fields", inputFieldScale, onInputFieldScaleChange)
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
    usageLoggingEnabled: Boolean,
    onUsageLoggingChange: (Boolean) -> Unit
) {
    SettingsGroup(title = "Touch & Timing") {
        SettingsSlider(
            title = stringResource(Res.string.ui_settings_hold_to_select_title),
            description = stringResource(Res.string.ui_settings_hold_to_select_desc),
            value = holdToSelectMillis.toFloat(),
            onValueChange = { onHoldToSelectChange(it.toLong()) },
            onValueChangeFinished = onHoldToSelectChangeFinished,
            valueRange = 0f..2000f,
            steps = 19,
            valueLabel = "${holdToSelectMillis.toInt()} ms"
        )
        SettingsGroupDivider()
        SettingsSlider(
            title = stringResource(Res.string.ui_settings_dwell_to_select_title),
            description = stringResource(Res.string.ui_settings_dwell_to_select_desc),
            value = dwellToSelectMillis.toFloat(),
            onValueChange = { onDwellToSelectChange(it.toLong()) },
            onValueChangeFinished = onDwellToSelectChangeFinished,
            valueRange = 0f..5000f,
            steps = 19,
            valueLabel = "${dwellToSelectMillis.toInt()} ms"
        )
    }

    SettingsGroup(title = "Feedback & Logging") {
        SettingsSwitch(
            checked = selectionSoundEnabled,
            onCheckedChange = onSelectionSoundChange,
            title = stringResource(Res.string.ui_settings_selection_sound_title)
        )
        SettingsGroupDivider()
        SettingsSwitch(
            checked = auditoryFishingEnabled,
            onCheckedChange = onAuditoryFishingChange,
            title = stringResource(Res.string.ui_settings_auditory_fishing_title),
            description = stringResource(Res.string.ui_settings_auditory_fishing_desc)
        )
        SettingsGroupDivider()
        SettingsSwitch(
            checked = usageLoggingEnabled,
            onCheckedChange = onUsageLoggingChange,
            title = stringResource(Res.string.ui_settings_usage_logging_title),
            description = stringResource(Res.string.ui_settings_usage_logging_desc)
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
    featureUsageReportingEnabled: Boolean,
    onFeatureReportingChange: (Boolean) -> Unit,
    partnerWindowEnabled: Boolean,
    partnerDeviceConnected: Boolean,
    onPartnerWindowChange: (Boolean) -> Unit,
    restoringBaseBoards: Boolean,
    baseBoardsStatus: String?,
    onRestoreBaseBoards: () -> Unit,
    arasaacAvailable: Boolean,
    cachedArasaacSymbols: Int,
    arasaacProgress: ArasaacDownloadProgress?,
    arasaacDownloadError: Boolean,
    arasaacFailedCount: Int,
    onDownloadArasaac: () -> Unit
) {
    SettingsGroup(title = stringResource(Res.string.ui_settings_startup_mode_title)) {
        Text(
            stringResource(Res.string.ui_settings_startup_mode_desc),
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
                label = { Text(stringResource(Res.string.ui_settings_startup_mode_keyboard)) },
                leadingIcon = { Icon(Icons.Filled.Keyboard, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = startupMode == StartupMode.Screens,
                onClick = { onStartupModeChange(StartupMode.Screens) },
                label = { Text(stringResource(Res.string.ui_settings_startup_mode_screens)) },
                leadingIcon = { Icon(Icons.Filled.GridView, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.weight(1f)
            )
        }
        if (startupMode == StartupMode.Screens) {
            var targetExpanded by remember { mutableStateOf(false) }
            val selectedName = availableBoardSets
                .firstOrNull { it.id == startupBoardSetId }
                ?.name
                ?: stringResource(Res.string.ui_settings_startup_screen_library)

            Spacer(modifier = Modifier.height(12.dp))
            ExposedDropdownMenuBox(
                expanded = targetExpanded,
                onExpandedChange = { targetExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(Res.string.ui_settings_startup_screen_title)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = targetExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                )
                ExposedDropdownMenu(
                    expanded = targetExpanded,
                    onDismissRequest = { targetExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.ui_settings_startup_screen_library)) },
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

    SettingsGroup(title = stringResource(Res.string.ui_settings_base_boards_title)) {
        Text(
            stringResource(Res.string.ui_settings_base_boards_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = onRestoreBaseBoards,
            enabled = !restoringBaseBoards
        ) {
            if (restoringBaseBoards) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (restoringBaseBoards) Res.string.ui_settings_base_boards_restoring else Res.string.ui_settings_base_boards_restore))
        }
        baseBoardsStatus?.let { status ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    SettingsGroup(title = stringResource(Res.string.ui_settings_symbols_title)) {
        Text(
            stringResource(Res.string.ui_settings_symbols_download_desc),
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
            Text(stringResource(if (activeProgress != null) Res.string.ui_settings_symbols_downloading else Res.string.ui_settings_symbols_download))
        }
        if (activeProgress != null && activeProgress.total > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(progress = { activeProgress.completed.toFloat() / activeProgress.total }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            when {
                !arasaacAvailable -> stringResource(Res.string.ui_settings_symbols_unavailable)
                arasaacDownloadError -> stringResource(Res.string.ui_settings_symbols_failed, arasaacFailedCount)
                cachedArasaacSymbols > 0 -> stringResource(Res.string.ui_settings_symbols_cached, cachedArasaacSymbols)
                else -> stringResource(Res.string.ui_settings_symbols_download_title)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (arasaacDownloadError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    SettingsGroup(title = stringResource(Res.string.ui_settings_analytics_title)) {
        SettingsSwitch(
            checked = featureUsageReportingEnabled,
            onCheckedChange = onFeatureReportingChange,
            title = stringResource(Res.string.ui_settings_feature_reporting_title),
            description = stringResource(Res.string.ui_settings_feature_reporting_desc)
        )
    }

    if (onBackToWelcome != null) {
        SettingsGroup(title = stringResource(Res.string.phrase_screen_welcome_screen)) {
            OutlinedButton(
                onClick = onBackToWelcome,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Home, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.phrase_screen_welcome_screen))
            }
        }
    }

    if (partnerDeviceConnected) {
        SettingsGroup(title = "Partner Window") {
            SettingsSwitch(
                checked = partnerWindowEnabled,
                onCheckedChange = onPartnerWindowChange,
                title = stringResource(Res.string.ui_settings_partner_window_title),
                description = stringResource(Res.string.ui_settings_partner_window_desc)
            )
        }
    }
}

// ─── Voice Selection Page ────────────────────────────────────────────────────

@Composable
private fun VoiceSelectionPage(onBack: () -> Unit) {
    val koin = getKoin()
    val useCase = koinInject<VoiceUseCase>()
    val settingsUseCase = remember(koin) { koin.getOrNull<SettingsUseCase>() }
    var loading by remember { mutableStateOf(true) }
    var voices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
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
    val scope = rememberCoroutineScope()

    val systemVoiceProvider = remember(koin) { koin.getOrNull<io.github.jdreioe.wingmate.infrastructure.SystemVoiceProvider>() }

    LaunchedEffect(Unit) {
        if (settingsUseCase != null) {
            val settings = withContext(Dispatchers.Default) {
                runCatching { settingsUseCase.get() }.getOrNull()
            }
            ttsEngine = settings?.ttsEngine ?: TtsEngine.SYSTEM
        }
        loading = true
        try {
            if (ttsEngine == TtsEngine.SYSTEM) {
                val allSystemVoices = systemVoiceProvider?.getSystemVoices() ?: listOf(
                    Voice(name = "system-default", displayName = "System Default", primaryLanguage = "en-US", gender = "Unknown")
                )
                systemVoices = allSystemVoices
                availableLanguages = allSystemVoices.mapNotNull { it.primaryLanguage }.distinct().sorted()
                selected = try { useCase.selected() } catch (e: Exception) { null }
            } else {
                val fromCloud = withContext(Dispatchers.Default) { useCase.refreshFromAzure() }
                val local = withContext(Dispatchers.Default) { useCase.list() }
                val allVoices = (fromCloud + local).distinctBy { it.name }
                voices = allVoices
                availableLanguages = allVoices
                    .flatMap { voice -> listOfNotNull(voice.primaryLanguage) + (voice.supportedLanguages ?: emptyList()) }
                    .distinct()
                    .sorted()
                selected = useCase.selected()
            }
        } catch (t: Throwable) {
            error = t.message
        }
        loading = false
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
    val allLabel = stringResource(Res.string.language_all)
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

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(
            value = voiceSearch,
            onValueChange = { voiceSearch = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(Res.string.voice_search_placeholder)) },
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { showLanguageFilter = !showLanguageFilter },
                    modifier = Modifier.height(36.dp).fillMaxWidth(),
                    enabled = availableLanguages.isNotEmpty()
                ) {
                    Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(selectedLanguage ?: stringResource(Res.string.voice_all_languages), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                DropdownMenu(
                    expanded = showLanguageFilter,
                    onDismissRequest = { showLanguageFilter = false },
                    modifier = Modifier.widthIn(min = 220.dp, max = 420.dp).heightIn(max = 320.dp)
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.voice_all_languages)) },
                        onClick = { selectedLanguage = null; showLanguageFilter = false }
                    )
                    availableLanguages.forEach { language ->
                        DropdownMenuItem(
                            text = { Text(language) },
                            onClick = { selectedLanguage = language; showLanguageFilter = false }
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { showGenderFilter = !showGenderFilter },
                    modifier = Modifier.height(36.dp).fillMaxWidth()
                ) {
                    Text(stringResource(Res.string.voice_gender_label, genderFilter ?: allLabel), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                DropdownMenu(
                    expanded = showGenderFilter,
                    onDismissRequest = { showGenderFilter = false },
                    modifier = Modifier.widthIn(min = 180.dp, max = 320.dp).heightIn(max = 300.dp)
                ) {
                    DropdownMenuItem(
                        text = { Text(allLabel) },
                        onClick = { genderFilter = null; showGenderFilter = false }
                    )
                    availableGenders.forEach { gender ->
                        DropdownMenuItem(
                            text = { Text(gender) },
                            onClick = { genderFilter = gender; showGenderFilter = false }
                        )
                    }
                }
            }
        }

        Text(
            stringResource(Res.string.voice_showing_count, visibleVoiceCount, totalVoiceCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (loading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (error != null) {
            Text(stringResource(Res.string.voice_error, error ?: ""))
        } else {
            val filteredVoices = if (ttsEngine == TtsEngine.SYSTEM) filteredSystemVoices else filteredAzureVoices
            val titleRes = if (ttsEngine == TtsEngine.SYSTEM) {
                if (selectedLanguage != null) Res.string.voice_system_title_with_lang else Res.string.voice_system_title
            } else {
                if (selectedLanguage != null) Res.string.voice_azure_title_with_lang else Res.string.voice_azure_title
            }
            val emptyRes = if (ttsEngine == TtsEngine.SYSTEM) Res.string.voice_no_system_match else Res.string.voice_no_azure_match

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
                                    try {
                                        useCase.select(v)
                                        val primary = if (ttsEngine == TtsEngine.SYSTEM) (v.primaryLanguage ?: "") else (v.selectedLanguage?.ifBlank { v.primaryLanguage ?: "" } ?: "")
                                        if (primary.isNotBlank() && settingsUseCase != null) {
                                            val current = settingsUseCase.get()
                                            settingsUseCase.update(current.copy(primaryLanguage = primary))
                                        }
                                    } catch (t: Throwable) {
                                        println("Failed to select voice ${v.name}: $t")
                                    }
                                    onBack()
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
                    try {
                        useCase.select(updated)
                        val primary = updated.selectedLanguage?.ifBlank { updated.primaryLanguage ?: "" } ?: ""
                        if (primary.isNotBlank() && settingsUseCase != null) {
                            val current = settingsUseCase.get()
                            settingsUseCase.update(current.copy(primaryLanguage = primary))
                        }
                    } catch (t: Throwable) {
                        println("Failed to save updated voice: $t")
                    }
                    showVoiceSettings = false
                    try {
                        voices = (useCase.refreshFromAzure() + useCase.list()).distinctBy { it.name }
                        selected = useCase.selected()
                    } catch (_: Throwable) {}
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
            Text(text = voice.displayName ?: voice.name ?: "Unknown", style = MaterialTheme.typography.bodyLarge)
            Text(text = voice.primaryLanguage ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (isSelected) {
                Text(
                    stringResource(Res.string.voice_selected),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        if (showSettings) {
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onSettings) { Text(stringResource(Res.string.voice_settings)) }
        }
    }
}

// ─── Language Selection Page ─────────────────────────────────────────────────

@Composable
internal fun LanguageSelectionPage(onBack: () -> Unit) {
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

    LaunchedEffect(Unit) {
        val settings = runCatching { settingsUseCase.get() }.getOrNull() ?: Settings()
        primary = settings.primaryLanguage
        secondary = settings.secondaryLanguage
        val sel = runCatching { voiceUseCase.selected() }.getOrNull()
        available = (sel?.supportedLanguages ?: emptyList())
            .ifEmpty { listOf(settings.primaryLanguage, settings.secondaryLanguage, "en-US") }
            .distinct()
    }

    val normalizedAvailable = remember(available) {
        available.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
    }

    val languageCodeOptions = remember(normalizedAvailable) {
        normalizedAvailable.map { languageCodePart(it).uppercase() }.distinct().sorted()
    }

    val regionOptions = remember(normalizedAvailable) {
        normalizedAvailable.mapNotNull { regionCodePart(it)?.uppercase() }.distinct().sorted()
    }

    val queryTerms = remember(filter) {
        filter.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
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

    fun updateLanguage(target: String, value: String) {
        scope.launch {
            try {
                val current = runCatching { settingsUseCase.get() }.getOrNull() ?: Settings()
                val updated = if (target == "primary") current.copy(primaryLanguage = value) else current.copy(secondaryLanguage = value)
                settingsUseCase.update(updated)
                featureUsageReporter.reportEvent(
                    FeatureUsageEvents.LANGUAGE_UPDATED,
                    "target" to target,
                    "value" to value
                )
                if (target == "primary") {
                    try {
                        val vuse = runCatching { voiceUseCase.selected() }.getOrNull()
                        if (vuse != null) {
                            runCatching { voiceUseCase.select(vuse.copy(selectedLanguage = value)) }
                        }
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(Res.string.language_search_label)) },
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LanguageFilterChip(
                label = stringResource(Res.string.language_filter_code),
                selected = languageCodeFilter,
                allLabel = allLabel,
                options = languageCodeOptions,
                onSelect = { languageCodeFilter = it },
                modifier = Modifier.weight(1f)
            )
            LanguageFilterChip(
                label = stringResource(Res.string.language_filter_region),
                selected = regionFilter,
                allLabel = allLabel,
                options = regionOptions,
                onSelect = { regionFilter = it },
                modifier = Modifier.weight(1f)
            )
        }

        SettingsGroup(title = stringResource(Res.string.language_primary)) {
            LanguageList(
                available = filteredLanguages,
                selected = primary,
                emptyLabel = if (normalizedAvailable.isEmpty()) noLanguagesAvailableLabel else noLanguagesMatchLabel,
                onSelect = { sel -> primary = sel; updateLanguage("primary", sel) }
            )
        }

        SettingsGroup(title = stringResource(Res.string.language_secondary)) {
            LanguageList(
                available = filteredLanguages,
                selected = secondary,
                emptyLabel = if (normalizedAvailable.isEmpty()) noLanguagesAvailableLabel else noLanguagesMatchLabel,
                onSelect = { sel -> secondary = sel; updateLanguage("secondary", sel) }
            )
        }
    }
}

@Composable
private fun LanguageFilterChip(
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
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().height(36.dp)) {
                Text(selected ?: allLabel, style = MaterialTheme.typography.bodySmall)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(min = 160.dp, max = 280.dp).heightIn(max = 280.dp)
            ) {
                DropdownMenuItem(
                    text = { Text(allLabel) },
                    onClick = { onSelect(null); expanded = false }
                )
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = { onSelect(option); expanded = false }
                    )
                }
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
                Text(lang, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
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
private fun SettingsGroup(
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
private fun SettingsGroupDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    )
}

@Composable
private fun SettingsNavRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
private fun SettingsPreferenceRow(
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
private fun SettingsSwitch(
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
