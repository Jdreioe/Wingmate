package io.github.jdreioe.wingmate.ui

import androidx.activity.compose.BackHandler
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
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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
import io.github.jdreioe.wingmate.application.BoardSetUseCase
import io.github.jdreioe.wingmate.application.BackupRestoreResult
import io.github.jdreioe.wingmate.application.CompleteBackupManager
import io.github.jdreioe.wingmate.application.EditingAccessController
import io.github.jdreioe.wingmate.application.EditingAccessState
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.application.reportEvent
import io.github.jdreioe.wingmate.application.SettingsStateManager
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.SpeechPolicy
import io.github.jdreioe.wingmate.domain.StartupMode
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.GoogleVoiceModel
import io.github.jdreioe.wingmate.domain.resolvedGoogleModel
import io.github.jdreioe.wingmate.domain.PointerEmphasisStyle
import io.github.jdreioe.wingmate.domain.WordTypeColorScheme
import io.github.jdreioe.wingmate.domain.obf.BoardActivationBehavior
import io.github.jdreioe.wingmate.domain.obf.BoardReturnBehavior
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.infrastructure.ArasaacDownloadProgress
import io.github.jdreioe.wingmate.infrastructure.ArasaacSymbolDownloadService
import io.github.jdreioe.wingmate.infrastructure.ImageCacher
import io.github.jdreioe.wingmate.platform.FilePicker
import io.github.jdreioe.wingmate.platform.ShareService
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.tooling.preview.Preview
import org.koin.compose.getKoin

import com.hojmoseit.wingmate.R

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
    val backupManager = remember(koin) { koin.getOrNull<CompleteBackupManager>() }
    val backupFilePicker = remember(koin) { koin.getOrNull<FilePicker>() }
    val backupShareService = remember(koin) { koin.getOrNull<ShareService>() }
    val editingAccessController = remember(koin) { koin.getOrNull<EditingAccessController>() }

    val operations = remember(
        configRepo,
        settingsUseCase,
        settingsStateManager,
        boardSetUseCase,
        voiceUseCase,
        speechService,
        pronunciationRepo,
        featureUsageReporter,
        arasaacDownloader,
        backupManager,
        backupFilePicker,
        backupShareService,
        editingAccessController,
    ) {
        DefaultSettingsOperations(
            configRepo = configRepo,
            settingsUseCase = settingsUseCase,
            settingsStateManager = settingsStateManager,
            boardSetUseCase = boardSetUseCase,
            voiceUseCase = voiceUseCase,
            speechService = speechService,
            pronunciationRepo = pronunciationRepo,
            featureUsageReporter = featureUsageReporter,
            arasaacDownloader = arasaacDownloader,
            backupManager = backupManager,
            filePicker = backupFilePicker,
            shareService = backupShareService,
            editingAccessController = editingAccessController,
        )
    }
    val factory = remember(operations) {
        viewModelFactory {
            initializer {
                SettingsViewModel(
                    savedStateHandle = createSavedStateHandle(),
                    operations = operations,
                )
            }
        }
    }
    val viewModel: SettingsViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val editingAccessState by (
        operations.editingAccessState?.collectAsStateWithLifecycle()
            ?: remember { mutableStateOf(EditingAccessState(supported = false)) }
        )
    LaunchedEffect(viewModel) { viewModel.onAction(SettingsAction.Initialize) }
    LaunchedEffect(viewModel, onSaved, onDismiss) {
        viewModel.events.collect { event ->
            when (event) {
                SettingsEvent.Close -> {
                    onSaved?.invoke()
                    onDismiss()
                }
            }
        }
    }

    // When the settings screen opens it is layered on top of the previous screen, which may
    // still hold focus on a text field. Prevent the software keyboard from popping up by
    // dropping focus and hiding the IME as soon as settings is shown.
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SettingsContent(
            state = state,
            editingAccessState = editingAccessState,
            editingAccessAvailable = editingAccessController != null,
            onBackToWelcome = onBackToWelcome,
            onAction = viewModel::onAction,
            onGuessPronunciation = viewModel::guessPronunciation,
        )

        val dialogMode = state.editingAccessDialog
        if (editingAccessController != null && dialogMode != null) {
            EditingAccessDialog(
                controller = editingAccessController,
                mode = dialogMode,
                onDismiss = { viewModel.onAction(SettingsAction.EditingAccessDialogDismissed) },
                onSuccess = { viewModel.onAction(SettingsAction.EditingAccessDialogDismissed) }
            )
        }
    }
}

/**
 * Stateless settings shell. It renders whatever [state] supplies and reports user intent
 * through [onAction], so loading, failure, and route rendering can be exercised without
 * the persistence-heavy root.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    editingAccessState: EditingAccessState,
    editingAccessAvailable: Boolean,
    onBackToWelcome: (() -> Unit)?,
    onAction: (SettingsAction) -> Unit,
    onGuessPronunciation: suspend (String) -> String?,
) {
    BackHandler(enabled = true, onBack = { onAction(SettingsAction.BackClicked) })

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(settingsRouteTitle(state.route))
                },
                navigationIcon = {
                    IconButton(onClick = { onAction(SettingsAction.BackClicked) }) {
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
                    when {
                        state.isLoading -> Box(
                            Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                        state.loadFailed -> Column(
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                stringResource(R.string.settings_load_failed),
                                color = MaterialTheme.colorScheme.error
                            )
                            Button(onClick = { onAction(SettingsAction.RetryLoad) }) {
                                Text(stringResource(R.string.common_retry))
                            }
                        }
                        else -> Column(modifier = Modifier.fillMaxSize()) {
                            if (state.saveFailed) {
                                SaveFailureBanner(onRetry = {
                                    onAction(SettingsAction.RetrySave)
                                })
                            }
                            Box(modifier = Modifier.fillMaxSize()) {
                                SettingsRouteContent(
                                    state = state,
                                    editingAccessState = editingAccessState,
                                    editingAccessAvailable = editingAccessAvailable,
                                    onBackToWelcome = onBackToWelcome,
                                    onAction = onAction,
                                    onGuessPronunciation = onGuessPronunciation,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }

                    val restorePath = state.pendingRestorePath
                    if (restorePath != null) {
                        AlertDialog(
                            onDismissRequest = { onAction(SettingsAction.RestoreDismissed) },
                            title = { Text(stringResource(R.string.backup_replace_title)) },
                            text = { Text(stringResource(R.string.backup_replace_warning)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    onAction(SettingsAction.RestoreConfirmed)
                                }) { Text(stringResource(R.string.backup_replace_action)) }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    onAction(SettingsAction.RestoreDismissed)
                                }) { Text(stringResource(R.string.common_cancel)) }
                            }
                        )
                    }
                }
            }
}


@Preview(showBackground = true, widthDp = 600, heightDp = 800)
@Composable
private fun SettingsContentPreview() {
    AppTheme {
        SettingsContent(
            state = SettingsUiState(
                route = SettingsRoute.Category(SettingsTab.Display),
                isLoading = false,
                settings = Settings(showLabels = true, showSymbols = true),
            ),
            editingAccessState = EditingAccessState(supported = false),
            editingAccessAvailable = false,
            onBackToWelcome = null,
            onAction = {},
            onGuessPronunciation = { null },
        )
    }
}

@Composable
private fun SaveFailureBanner(onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.settings_save_failed),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.common_retry))
            }
        }
    }
}

@Composable
private fun settingsRouteTitle(route: SettingsRoute): String = when (route) {
    SettingsRoute.Home -> stringResource(R.string.ui_settings_title)
    SettingsRoute.PronunciationDictionary -> stringResource(R.string.dictionary_title)
    is SettingsRoute.Category -> settingsCategoryTitle(route.tab)
    SettingsRoute.VoiceSelection -> stringResource(R.string.voice_select_title)
    SettingsRoute.LanguageSelection -> stringResource(R.string.language_dialog_title)
    SettingsRoute.GoogleSetup -> stringResource(R.string.google_setup_title)
    // F0 setup renders its own full-screen layout; the bar keeps a stable title.
    SettingsRoute.F0Setup -> stringResource(R.string.ui_settings_speech_title)
}

@Composable
private fun SettingsRouteContent(
    state: SettingsUiState,
    editingAccessState: EditingAccessState,
    editingAccessAvailable: Boolean,
    onBackToWelcome: (() -> Unit)?,
    onAction: (SettingsAction) -> Unit,
    onGuessPronunciation: suspend (String) -> String?,
    modifier: Modifier = Modifier
) {
    val settings = state.settings
    when (val route = state.route) {
        SettingsRoute.PronunciationDictionary -> DictionaryScreen(
            entries = state.dictionaryEntries,
            showTopBar = false,
            onAddEntry = { word, phoneme, alphabet ->
                onAction(SettingsAction.DictionaryEntryAdded(word, phoneme, alphabet))
            },
            onDeleteEntry = { entry -> onAction(SettingsAction.DictionaryEntryDeleted(entry.word)) },
            onTestEntry = { word, phoneme, alphabet ->
                onAction(SettingsAction.DictionaryTestRequested(word, phoneme, alphabet))
            },
            onGuessPronunciation = onGuessPronunciation,
            onBack = { onAction(SettingsAction.BackClicked) },
            modifier = modifier
        )
        SettingsRoute.Home -> SettingsHomePage(
            onSelectCategory = { onAction(SettingsAction.CategorySelected(it)) },
            onOpenPronunciation = { onAction(SettingsAction.PronunciationOpened) },
            modifier = modifier
        )
        SettingsRoute.VoiceSelection -> VoiceSelectionPage(
            onBack = { onAction(SettingsAction.BackClicked) }
        )
        SettingsRoute.LanguageSelection -> LanguageSelectionPage(
            onBack = { onAction(SettingsAction.BackClicked) }
        )
        SettingsRoute.F0Setup -> F0SetupScreen(
            onDone = { onAction(SettingsAction.F0SetupCompleted) },
            onBack = { onAction(SettingsAction.BackClicked) }
        )
        SettingsRoute.GoogleSetup -> GoogleTtsSetupScreen(
            onDone = { onAction(SettingsAction.GoogleSetupCompleted) },
            onBack = { onAction(SettingsAction.BackClicked) },
            showNavigation = false,
        )
        is SettingsRoute.Category -> CategoryContent(
            tab = route.tab,
            state = state,
            settings = settings,
            editingAccessState = editingAccessState,
            editingAccessAvailable = editingAccessAvailable,
            onBackToWelcome = onBackToWelcome,
            onAction = onAction,
            modifier = modifier
        )
    }
}

@Composable
private fun CategoryContent(
    tab: SettingsTab,
    state: SettingsUiState,
    settings: Settings,
    editingAccessState: EditingAccessState,
    editingAccessAvailable: Boolean,
    onBackToWelcome: (() -> Unit)?,
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        when (tab) {
            SettingsTab.Speech -> SpeechSection(
                ttsEngine = settings.ttsEngine,
                onTtsEngineChange = { engine -> onAction(SettingsAction.TtsEngineSelected(engine)) },
                endpoint = state.azureEndpoint,
                onEndpointChange = { onAction(SettingsAction.AzureEndpointChanged(it)) },
                subscriptionKey = state.azureSubscriptionKey,
                onSubscriptionKeyChange = { onAction(SettingsAction.AzureSubscriptionKeyChanged(it)) },
                endpointError = if (state.azureEndpointInvalid) {
                    stringResource(R.string.azure_setup_error_endpoint)
                } else null,
                credentialConfigured = state.azureCredentialConfigured,
                replacingCredentials = state.replacingAzureCredentials,
                onReplaceCredentials = { onAction(SettingsAction.ReplaceAzureCredentialsClicked) },
                googleCredentialConfigured = state.googleCredentialConfigured,
                onOpenGoogleSetup = { onAction(SettingsAction.GoogleSetupOpened) },
                onClearGoogleCredentials = { onAction(SettingsAction.GoogleCredentialsCleared) },
                onOpenVoiceSelection = { onAction(SettingsAction.VoiceSelectionOpened) },
                onOpenLanguageSelection = { onAction(SettingsAction.LanguageSelectionOpened) },
                onOpenF0Setup = { onAction(SettingsAction.F0SetupOpened) }
            )
            SettingsTab.Display -> DisplaySection(
                fontSizeScale = settings.fontSizeScale,
                onFontSizeScaleChange = { onAction(SettingsAction.FontSizeScaleChanged(it)) },
                playbackIconScale = settings.playbackIconScale,
                onPlaybackIconScaleChange = { onAction(SettingsAction.PlaybackIconScaleChanged(it)) },
                categoryChipScale = settings.categoryChipScale,
                onCategoryChipScaleChange = { onAction(SettingsAction.CategoryChipScaleChanged(it)) },
                buttonScale = settings.buttonScale,
                onButtonScaleChange = { onAction(SettingsAction.ButtonScaleChanged(it)) },
                inputFieldScale = settings.inputFieldScale,
                onInputFieldScaleChange = { onAction(SettingsAction.InputFieldScaleChanged(it)) },
                showLabels = settings.showLabels,
                onShowLabelsChange = { checked -> onAction(SettingsAction.ShowLabelsChanged(checked)) },
                showSymbols = settings.showSymbols,
                onShowSymbolsChange = { checked -> onAction(SettingsAction.ShowSymbolsChanged(checked)) },
                labelAtTop = settings.labelAtTop,
                onLabelAtTopChange = { checked -> onAction(SettingsAction.LabelAtTopChanged(checked)) },
                boardShowMessageBar = settings.boardShowMessageBar,
                onBoardShowMessageBarChange = { checked ->
                    onAction(SettingsAction.BoardShowMessageBarChanged(checked))
                },
                boardShowSpeakButton = settings.boardShowSpeakButton,
                onBoardShowSpeakButtonChange = { checked ->
                    onAction(SettingsAction.BoardShowSpeakButtonChanged(checked))
                },
                boardMessageBarEditable = settings.boardMessageBarEditable,
                onBoardMessageBarEditableChange = { checked ->
                    onAction(SettingsAction.BoardMessageBarEditableChanged(checked))
                },
                boardActivationBehavior = settings.boardActivationBehavior,
                onBoardActivationBehaviorChange = { behavior ->
                    onAction(SettingsAction.BoardActivationBehaviorChanged(behavior))
                },
                boardReturnBehavior = settings.boardReturnBehavior,
                onBoardReturnBehaviorChange = { behavior ->
                    onAction(SettingsAction.BoardReturnBehaviorChanged(behavior))
                },
                gridColumns = settings.gridColumns,
                onGridColumnsChange = { onAction(SettingsAction.GridColumnsChanged(it)) },
                onGridColumnsChangeFinished = { onAction(SettingsAction.GridColumnsChangeFinished) },
                highContrastMode = settings.highContrastMode,
                onHighContrastModeChange = { checked ->
                    onAction(SettingsAction.HighContrastModeChanged(checked))
                },
                wordTypeColorScheme = settings.wordTypeColorScheme,
                onWordTypeColorSchemeChange = { scheme ->
                    onAction(SettingsAction.WordTypeColorsChanged(scheme == WordTypeColorScheme.Fitzgerald))
                }
            )
            SettingsTab.Accessibility -> AccessibilitySection(
                holdToSelectMillis = settings.holdToSelectMillis,
                onHoldToSelectChange = { onAction(SettingsAction.HoldToSelectChanged(it)) },
                onHoldToSelectChangeFinished = { onAction(SettingsAction.HoldToSelectChangeFinished) },
                dwellToSelectMillis = settings.dwellToSelectMillis,
                onDwellToSelectChange = { onAction(SettingsAction.DwellToSelectChanged(it)) },
                onDwellToSelectChangeFinished = { onAction(SettingsAction.DwellToSelectChangeFinished) },
                selectionSoundEnabled = settings.selectionSoundEnabled,
                onSelectionSoundChange = { checked -> onAction(SettingsAction.SelectionSoundChanged(checked)) },
                auditoryFishingEnabled = settings.auditoryFishingEnabled,
                onAuditoryFishingChange = { checked ->
                    onAction(SettingsAction.AuditoryFishingChanged(checked))
                },
                speechPolicy = settings.speechPolicy,
                onSpeechPolicyChange = { policy -> onAction(SettingsAction.SpeechPolicyChanged(policy)) },
                selectionDebounceMillis = settings.selectionDebounceMillis,
                onSelectionDebounceChange = { onAction(SettingsAction.SelectionDebounceChanged(it)) },
                onSelectionDebounceChangeFinished = { onAction(SettingsAction.SelectionDebounceChangeFinished) },
                dwellRearmDelayMillis = settings.dwellRearmDelayMillis,
                onDwellRearmDelayChange = { onAction(SettingsAction.DwellRearmDelayChanged(it)) },
                onDwellRearmDelayChangeFinished = { onAction(SettingsAction.DwellRearmDelayChangeFinished) },
                selectionHighlightMillis = settings.selectionHighlightMillis,
                onSelectionHighlightChange = { onAction(SettingsAction.SelectionHighlightChanged(it)) },
                onSelectionHighlightChangeFinished = { onAction(SettingsAction.SelectionHighlightChangeFinished) },
                selectKeyBinding = settings.selectKeyBinding,
                onSelectKeyBindingChange = { onAction(SettingsAction.SelectKeyBindingChanged(it)) },
                restModeKeyBinding = settings.restModeKeyBinding,
                onRestModeKeyBindingChange = { onAction(SettingsAction.RestModeKeyBindingChanged(it)) },
                pointerEmphasisStyle = settings.pointerEmphasisStyle,
                onPointerEmphasisStyleChange = { value ->
                    onAction(SettingsAction.PointerEmphasisStyleChanged(value))
                },
                pointerEmphasisScale = settings.pointerEmphasisScale,
                onPointerEmphasisScaleChange = { onAction(SettingsAction.PointerEmphasisScaleChanged(it)) },
                onPointerEmphasisScaleChangeFinished = {
                    onAction(SettingsAction.PointerEmphasisScaleChangeFinished)
                }
            )
            SettingsTab.Privacy -> PrivacySection(
                historyVisible = settings.historyVisible,
                onHistoryVisibleChange = { checked ->
                    onAction(SettingsAction.HistoryVisibleChanged(checked))
                },
                boardSets = state.boardSets,
                onBoardSetSentenceCachingChange = { boardSet, enabled ->
                    onAction(SettingsAction.BoardSetSentenceCachingChanged(boardSet.id, enabled))
                },
                usageLoggingEnabled = settings.usageLoggingEnabled,
                onUsageLoggingChange = { checked -> onAction(SettingsAction.UsageLoggingChanged(checked)) },
                featureUsageReportingEnabled = settings.featureUsageReportingEnabled,
                onFeatureReportingChange = { checked ->
                    onAction(SettingsAction.FeatureReportingChanged(checked))
                }
            )
            SettingsTab.General -> GeneralSection(
                onBackToWelcome = onBackToWelcome,
                startupMode = settings.startupMode,
                startupBoardSetId = settings.startupBoardSetId,
                availableBoardSets = state.boardSets,
                arasaacAvailable = state.arasaacAvailable,
                cachedArasaacSymbols = state.cachedArasaacSymbols,
                arasaacProgress = state.arasaacProgress,
                arasaacDownloadError = state.arasaacDownloadFailed,
                arasaacFailedCount = state.arasaacFailedCount,
                editingAccessState = editingAccessState.takeIf { editingAccessAvailable },
                backupWorking = state.backupWorking,
                backupStatusMessage = state.backupStatus?.let { message ->
                    when (message) {
                        is SettingsMessage.Resource -> stringResource(message.id)
                        is SettingsMessage.Dynamic -> message.value
                    }
                },
                onStartupModeChange = { mode -> onAction(SettingsAction.StartupModeChanged(mode)) },
                onStartupBoardSetChange = { boardSetId ->
                    onAction(SettingsAction.StartupBoardSetChanged(boardSetId))
                },
                onDownloadArasaac = { onAction(SettingsAction.DownloadArasaacClicked) },
                onCreateBackup = { onAction(SettingsAction.CreateBackupClicked) },
                onRestoreBackup = { onAction(SettingsAction.RestoreBackupClicked) },
                onEditingAccessConfigure = { onAction(SettingsAction.EditingAccessConfigureClicked) },
                onEditingAccessUnlock = { onAction(SettingsAction.EditingAccessUnlockClicked) },
                onEditingAccessDisable = { onAction(SettingsAction.EditingAccessDisableClicked) },
                onEditingAccessLockNow = { onAction(SettingsAction.EditingAccessLockNowClicked) }
            )
        }
        Spacer(Modifier.height(16.dp))
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
                "language"
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
                stringResource(R.string.phrase_screen_welcome_screen),
                stringResource(R.string.backup_title),
                stringResource(R.string.backup_create),
                stringResource(R.string.backup_restore),
                "startup", "arasaac", "offline",
                "welcome", "boards", "screens", "backup", "restore"
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
    endpointError: String?,
    credentialConfigured: Boolean,
    replacingCredentials: Boolean,
    onReplaceCredentials: () -> Unit,
    googleCredentialConfigured: Boolean,
    onOpenGoogleSetup: () -> Unit,
    onClearGoogleCredentials: () -> Unit,
    onOpenVoiceSelection: () -> Unit = {},
    onOpenLanguageSelection: () -> Unit = {},
    onOpenF0Setup: () -> Unit = {}
) {
    SettingsGroup(title = stringResource(R.string.ui_settings_tts_engine_group)) {
        SettingsPreferenceRow(
            title = stringResource(R.string.ui_settings_speech_engine),
            subtitle = stringResource(
                when (ttsEngine) {
                    TtsEngine.SYSTEM -> R.string.ui_settings_system_tts
                    TtsEngine.GOOGLE_CLOUD -> R.string.ui_settings_google_tts
                    TtsEngine.AZURE_USER_RESOURCE, TtsEngine.AZURE_MANAGED -> R.string.ui_settings_azure_tts
                }
            )
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = ttsEngine == TtsEngine.AZURE_USER_RESOURCE || ttsEngine == TtsEngine.AZURE_MANAGED,
                    onClick = { onTtsEngineChange(TtsEngine.AZURE_USER_RESOURCE) },
                    label = { Text(stringResource(R.string.ui_settings_azure)) }
                )
                FilterChip(
                    selected = ttsEngine == TtsEngine.GOOGLE_CLOUD,
                    onClick = { onTtsEngineChange(TtsEngine.GOOGLE_CLOUD) },
                    label = { Text(stringResource(R.string.ui_settings_google)) },
                )
                FilterChip(
                    selected = ttsEngine == TtsEngine.SYSTEM,
                    onClick = { onTtsEngineChange(TtsEngine.SYSTEM) },
                    label = { Text(stringResource(R.string.ui_settings_system)) }
                )
            }
        }
        if (ttsEngine == TtsEngine.AZURE_USER_RESOURCE || ttsEngine == TtsEngine.AZURE_MANAGED) {
            SettingsGroupDivider()
            AzureCredentialEditor(
                credentialConfigured = credentialConfigured,
                replacingCredentials = replacingCredentials,
                endpoint = endpoint,
                onEndpointChange = onEndpointChange,
                subscriptionKey = subscriptionKey,
                onSubscriptionKeyChange = onSubscriptionKeyChange,
                endpointError = endpointError,
                onReplaceCredentials = onReplaceCredentials,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        }

        if (ttsEngine == TtsEngine.GOOGLE_CLOUD) {
            SettingsGroupDivider()
            Column(
                modifier = Modifier.padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (googleCredentialConfigured) {
                    Text(stringResource(R.string.google_tts_configured))
                    OutlinedButton(onClick = onOpenGoogleSetup) {
                        Text(stringResource(R.string.google_tts_replace_key))
                    }
                    TextButton(onClick = onClearGoogleCredentials) {
                        Text(stringResource(R.string.google_tts_clear_key))
                    }
                } else {
                    Text(stringResource(R.string.google_tts_api_key_help))
                    Button(onClick = onOpenGoogleSetup, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.google_setup_guided_title))
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        if (ttsEngine != TtsEngine.GOOGLE_CLOUD) {
            OutlinedButton(
                onClick = onOpenF0Setup,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.ui_settings_azure_free_tier))
            }
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
    boardShowSpeakButton: Boolean,
    onBoardShowSpeakButtonChange: (Boolean) -> Unit,
    boardMessageBarEditable: Boolean,
    onBoardMessageBarEditableChange: (Boolean) -> Unit,
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
        SettingsSwitch(
            checked = boardShowSpeakButton,
            onCheckedChange = onBoardShowSpeakButtonChange,
            title = stringResource(R.string.board_settings_speak_button),
            description = stringResource(R.string.board_settings_global_speak_button_desc)
        )
        SettingsGroupDivider()
        SettingsSwitch(
            checked = boardMessageBarEditable,
            onCheckedChange = onBoardMessageBarEditableChange,
            title = stringResource(R.string.board_settings_message_bar_editable),
            description = stringResource(R.string.board_settings_global_message_bar_editable_desc)
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
    dwellRearmDelayMillis: Long,
    onDwellRearmDelayChange: (Long) -> Unit,
    onDwellRearmDelayChangeFinished: () -> Unit,
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
        SettingsGroupDivider()
        SettingsSlider(
            title = stringResource(R.string.ui_settings_dwell_rearm_title),
            description = stringResource(R.string.ui_settings_dwell_rearm_desc),
            value = dwellRearmDelayMillis.toFloat(),
            onValueChange = { onDwellRearmDelayChange(it.toLong()) },
            onValueChangeFinished = onDwellRearmDelayChangeFinished,
            valueRange = 0f..500f,
            steps = 9,
            valueLabel = "${dwellRearmDelayMillis.toInt()} ms"
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
private fun BackupSettingsGroup(
    working: Boolean,
    statusMessage: String?,
    onCreateBackup: () -> Unit,
    onRestoreBackup: () -> Unit
) {
    SettingsGroup(title = stringResource(R.string.backup_title)) {
        Text(
            stringResource(R.string.backup_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !working,
                onClick = onCreateBackup,
            ) { Text(stringResource(R.string.backup_create)) }
            OutlinedButton(
                enabled = !working,
                onClick = onRestoreBackup,
            ) { Text(stringResource(R.string.backup_restore)) }
        }
        statusMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
    arasaacAvailable: Boolean,
    cachedArasaacSymbols: Int,
    arasaacProgress: ArasaacDownloadProgress?,
    arasaacDownloadError: Boolean,
    arasaacFailedCount: Int,
    editingAccessState: EditingAccessState?,
    backupWorking: Boolean,
    backupStatusMessage: String?,
    onStartupModeChange: (StartupMode) -> Unit,
    onStartupBoardSetChange: (String?) -> Unit,
    onDownloadArasaac: () -> Unit,
    onCreateBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onEditingAccessConfigure: () -> Unit,
    onEditingAccessUnlock: () -> Unit,
    onEditingAccessDisable: () -> Unit,
    onEditingAccessLockNow: () -> Unit
) {
    BackupSettingsGroup(
        working = backupWorking,
        statusMessage = backupStatusMessage,
        onCreateBackup = onCreateBackup,
        onRestoreBackup = onRestoreBackup
    )

    SettingsGroup(title = stringResource(R.string.editing_access_title)) {
        Text(
            stringResource(R.string.editing_access_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        val access = editingAccessState
        if (access == null) {
            Text(stringResource(R.string.editing_access_unavailable), color = MaterialTheme.colorScheme.error)
        } else if (!access.supported) {
            Text(stringResource(R.string.editing_access_unavailable), color = MaterialTheme.colorScheme.error)
        } else if (!access.enabled) {
            OutlinedButton(
                onClick = onEditingAccessConfigure,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.editing_access_enable)) }
        } else {
            if (!access.unlocked) {
                OutlinedButton(
                    onClick = onEditingAccessUnlock,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.editing_access_unlock_title)) }
            }
            OutlinedButton(
                onClick = onEditingAccessConfigure,
                enabled = access.unlocked,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.editing_access_change)) }
            OutlinedButton(
                onClick = onEditingAccessDisable,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.editing_access_disable)) }
            OutlinedButton(
                onClick = onEditingAccessLockNow,
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
}

// ─── Voice Selection Page ────────────────────────────────────────────────────

@Composable
private fun rememberVoiceSelectionOperations(): VoiceSelectionOperations {
    val koin = getKoin()
    return remember(koin) {
        DefaultVoiceSelectionOperations(
            voiceUseCase = koin.get(),
            settingsUseCase = koin.get(),
            systemVoiceProvider = koin.getOrNull(),
            featureUsageReporter = koin.get(),
        )
    }
}

/** Voice picker shared by Settings and Welcome; [onVoiceSelected] defaults to [onBack]. */
@Composable
internal fun VoiceSelectionPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onVoiceSelected: (() -> Unit)? = null
) {
    val operations = rememberVoiceSelectionOperations()
    val viewModel: VoiceSelectionViewModel = viewModel(
        factory = remember(operations) { viewModelFactory { initializer { VoiceSelectionViewModel(operations) } } }
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.onAction(VoiceSelectionAction.Load) }
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
                    TtsEngine.GOOGLE_CLOUD -> if (selectedLanguage != null) R.string.voice_google_title_with_lang else R.string.voice_google_title
                    else -> if (selectedLanguage != null) R.string.voice_azure_title_with_lang else R.string.voice_azure_title
                }
                val emptyRes = when (ttsEngine) {
                    TtsEngine.SYSTEM -> R.string.voice_no_system_match
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
                        filteredVoices.forEachIndexed { index, v ->
                            VoiceRow(
                                voice = v,
                                isSelected = state.selected?.name == v.name,
                                showSettings = ttsEngine != TtsEngine.SYSTEM,
                                onSelect = { onAction(VoiceSelectionAction.VoiceSelected(v, selectedLanguage)) },
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
