package io.github.jdreioe.wingmate.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.jdreioe.wingmate.application.RemoteScreenActions
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageKind
import io.github.jdreioe.wingmate.domain.obf.SyncConnection
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.application.BoardSetUseCase
import io.github.jdreioe.wingmate.application.CompleteBackupManager
import io.github.jdreioe.wingmate.application.EditingAccessController
import io.github.jdreioe.wingmate.application.EditingAccessState
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.application.SettingsStateManager
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.WordTypeColorScheme
import io.github.jdreioe.wingmate.infrastructure.ArasaacSymbolDownloadService
import io.github.jdreioe.wingmate.infrastructure.ImageCacher
import io.github.jdreioe.wingmate.platform.FilePicker
import io.github.jdreioe.wingmate.platform.ShareService
import org.koin.compose.getKoin

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
    // Wide windows keep the categories beside the open page. A category is then one level
    // deep, so Back from it leaves Settings rather than showing the list that is already there.
    val twoPane = currentWindowWidth() == WindowWidth.Expanded
    val onBack = {
        val route = state.route
        val topLevel = route == SettingsRoute.Home || route is SettingsRoute.Category ||
            route == SettingsRoute.PronunciationDictionary
        onAction(if (twoPane && topLevel) SettingsAction.CloseClicked else SettingsAction.BackClicked)
    }
    BackHandler(enabled = true, onBack = onBack)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            val navigationIcon: @Composable () -> Unit = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_close))
                }
            }
            val barColors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
            )
            // Two panes: the title belongs to the list, so it starts above it; each page has its own heading.
            if (twoPane) {
                TopAppBar(
                    title = { Text(stringResource(R.string.ui_settings_title)) },
                    navigationIcon = navigationIcon,
                    colors = barColors,
                )
            } else {
                CenterAlignedTopAppBar(
                    title = { Text(settingsRouteTitle(state.route)) },
                    navigationIcon = navigationIcon,
                    colors = barColors,
                )
            }
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
                    if (twoPane) {
                        SettingsTwoPane(
                            state = state,
                            editingAccessState = editingAccessState,
                            editingAccessAvailable = editingAccessAvailable,
                            onBackToWelcome = onBackToWelcome,
                            onAction = onAction,
                            onGuessPronunciation = onGuessPronunciation,
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                            SettingsRouteContent(
                                state = state,
                                editingAccessState = editingAccessState,
                                editingAccessAvailable = editingAccessAvailable,
                                onBackToWelcome = onBackToWelcome,
                                onAction = onAction,
                                onGuessPronunciation = onGuessPronunciation,
                                // A medium window centres one readable column.
                                modifier = Modifier.widthIn(max = SettingsDetailMaxWidth).fillMaxSize()
                            )
                        }
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

/** How wide a Settings page grows: past this, a switch drifts too far from its label. */
private val SettingsDetailMaxWidth = 760.dp

/**
 * Settings on a wide window: the category list stays on the left and the open page fills
 * the right. Opening Settings shows Speech, so the right side is never empty. Speech's own
 * pages (voice, language, providers) open in the right pane with their own Back.
 */
@Composable
private fun SettingsTwoPane(
    state: SettingsUiState,
    editingAccessState: EditingAccessState,
    editingAccessAvailable: Boolean,
    onBackToWelcome: (() -> Unit)?,
    onAction: (SettingsAction) -> Unit,
    onGuessPronunciation: suspend (String) -> String?,
) {
    val detailRoute = state.route.takeUnless { it == SettingsRoute.Home } ?: SettingsRoute.Category(SettingsTab.Speech)
    val selectedTab = when (detailRoute) {
        is SettingsRoute.Category -> detailRoute.tab
        SettingsRoute.PronunciationDictionary -> null
        // The voice, language and provider pages belong to Speech.
        else -> SettingsTab.Speech
    }
    Row(Modifier.fillMaxSize()) {
        SettingsHomePage(
            onSelectCategory = { onAction(SettingsAction.CategorySelected(it)) },
            onOpenPronunciation = { onAction(SettingsAction.PronunciationOpened) },
            selectedTab = selectedTab,
            pronunciationSelected = detailRoute == SettingsRoute.PronunciationDictionary,
            modifier = Modifier.width(380.dp).fillMaxHeight(),
        )
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = SettingsDetailMaxWidth).fillMaxSize()) {
                Text(
                    settingsRouteTitle(detailRoute),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
                )
                SettingsRouteContent(
                    state = state.copy(route = detailRoute),
                    editingAccessState = editingAccessState,
                    editingAccessAvailable = editingAccessAvailable,
                    onBackToWelcome = onBackToWelcome,
                    onAction = onAction,
                    onGuessPronunciation = onGuessPronunciation,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
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
    SettingsRoute.VoiceTest -> stringResource(R.string.welcome_test_title)
    SettingsRoute.LanguageSelection -> stringResource(R.string.language_dialog_title)
    SettingsRoute.ElevenLabsSetup -> "ElevenLabs"
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
            onBack = { onAction(SettingsAction.BackClicked) },
            onVoiceSelected = { onAction(SettingsAction.VoiceChosen) },
        )
        SettingsRoute.VoiceTest -> {
            var sentence by rememberSaveable { mutableStateOf<String?>(null) }
            TestVoiceScreen(
                sentence = sentence,
                onSentenceChange = { sentence = it },
                onNext = { onAction(SettingsAction.VoiceTestDone) },
                onBack = { onAction(SettingsAction.BackClicked) },
                primaryLabel = stringResource(R.string.common_done),
                standalone = false,
            )
        }
        SettingsRoute.LanguageSelection -> LanguageSelectionPage(
            onBack = { onAction(SettingsAction.BackClicked) }
        )
        SettingsRoute.F0Setup -> F0SetupScreen(
            onDone = { onAction(SettingsAction.F0SetupCompleted) },
            onBack = { onAction(SettingsAction.BackClicked) }
        )
        SettingsRoute.ElevenLabsSetup -> ElevenLabsSetupScreen(
            onDone = { onAction(SettingsAction.ElevenLabsSetupCompleted) },
            onBack = { onAction(SettingsAction.BackClicked) },
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
                googleCredentialConfigured = state.googleCredentialConfigured,
                onOpenElevenLabsSetup = { onAction(SettingsAction.ElevenLabsSetupOpened) },
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
                selectOnTouchDown = settings.selectOnTouchDown,
                onSelectOnTouchDownChange = { onAction(SettingsAction.SelectOnTouchDownChanged(it)) },
                selectOnTouchExit = settings.selectOnTouchExit,
                onSelectOnTouchExitChange = { onAction(SettingsAction.SelectOnTouchExitChanged(it)) },
                swipeBetweenPages = settings.swipeBetweenPages,
                onSwipeBetweenPagesChange = { onAction(SettingsAction.SwipeBetweenPagesChanged(it)) },
                dwellToSelectMillis = settings.dwellToSelectMillis,
                onDwellToSelectChange = { onAction(SettingsAction.DwellToSelectChanged(it)) },
                onDwellToSelectChangeFinished = { onAction(SettingsAction.DwellToSelectChangeFinished) },
                selectionSoundEnabled = settings.selectionSoundEnabled,
                onSelectionSoundChange = { checked -> onAction(SettingsAction.SelectionSoundChanged(checked)) },
                auditoryFishingEnabled = settings.auditoryFishingEnabled,
                onAuditoryFishingChange = { checked ->
                    onAction(SettingsAction.AuditoryFishingChanged(checked))
                },
                routeAuditoryPreviewToHeadphones = settings.routeAuditoryPreviewToHeadphones,
                onRouteAuditoryPreviewToHeadphonesChange = { checked ->
                    onAction(SettingsAction.RouteAuditoryPreviewToHeadphonesChanged(checked))
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
            SettingsTab.Sync -> SyncSettingsSection(
                onRequestUnlock = { onAction(SettingsAction.EditingAccessUnlockClicked) },
            )
            SettingsTab.Privacy -> PrivacySection(
                historyVisible = settings.historyVisible,
                onHistoryVisibleChange = { checked ->
                    onAction(SettingsAction.HistoryVisibleChanged(checked))
                },
                likelyNextPageEnabled = settings.likelyNextPageEnabled,
                onLikelyNextPageChange = { checked ->
                    onAction(SettingsAction.LikelyNextPageChanged(checked))
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
internal fun settingsCategoryTitle(tab: SettingsTab): String = when (tab) {
    SettingsTab.Speech -> stringResource(R.string.ui_settings_speech_title)
    SettingsTab.Display -> stringResource(R.string.ui_settings_display_title)
    SettingsTab.Accessibility -> stringResource(R.string.ui_settings_accessibility_title)
    SettingsTab.Sync -> stringResource(R.string.ui_settings_sync_title)
    SettingsTab.Privacy -> stringResource(R.string.ui_settings_privacy_title)
    SettingsTab.General -> stringResource(R.string.ui_settings_general_title)
}

@Composable
private fun SyncSettingsSection(
    onRequestUnlock: () -> Unit,
) {
    val koin = getKoin()
    val actions = remember(koin) { koin.getOrNull<RemoteScreenActions>() }
    val scope = rememberCoroutineScope()
    var connections by remember { mutableStateOf<List<SyncConnection>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val cloudProviders = remember(actions) { actions?.cloudDriveProviders.orEmpty() }

    suspend fun reload() {
        connections = actions?.listSyncConnections().orEmpty()
    }

    LaunchedEffect(actions) { reload() }

    fun runAction(block: suspend RemoteScreenActions.() -> io.github.jdreioe.wingmate.application.RemoteScreenResult) {
        val current = actions ?: return
        busy = true
        status = null
        scope.launch {
            try {
                val res = current.block()
                if (res.status == "locked") {
                    onRequestUnlock()
                }
                status = res.message
                reload()
            } finally {
                busy = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.settings_sync_folder_footer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (connections.isEmpty()) {
            Text(
                stringResource(R.string.settings_sync_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            connections.forEach { conn ->
                val title = when (conn.kind) {
                    RemoteStorageKind.GOOGLE_DRIVE -> stringResource(R.string.remote_screen_provider_google_drive)
                    RemoteStorageKind.ONEDRIVE -> stringResource(R.string.remote_screen_provider_onedrive)
                    RemoteStorageKind.LINKED_FILE -> conn.accountLabel.ifBlank { stringResource(R.string.settings_sync_folder_label) }
                    else -> conn.folderName
                }
                val folderText = stringResource(R.string.settings_sync_wingmate_folder, conn.folderName)
                val subtitle = if (conn.kind != RemoteStorageKind.LINKED_FILE && conn.accountLabel.isNotBlank()) {
                    "${conn.accountLabel} · $folderText"
                } else {
                    folderText
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            if (conn.kind == RemoteStorageKind.LINKED_FILE) Icons.Filled.Folder else Icons.Filled.Cloud,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleSmall)
                            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(
                            enabled = !busy,
                            onClick = { runAction { disconnectSyncConnection(conn.id) } },
                        ) {
                            Text(stringResource(R.string.remote_screen_disconnect))
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if ("google_drive" in cloudProviders && connections.none { it.kind == RemoteStorageKind.GOOGLE_DRIVE }) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = { runAction { connectCloudSync("google_drive") } },
                ) {
                    Text(stringResource(R.string.remote_screen_provider_google_drive))
                }
            }
            if ("onedrive" in cloudProviders && connections.none { it.kind == RemoteStorageKind.ONEDRIVE }) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = { runAction { connectCloudSync("onedrive") } },
                ) {
                    Text(stringResource(R.string.remote_screen_provider_onedrive))
                }
            }
            if (actions?.isLinkedFileSupported == true) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = { runAction { connectFolderSync() } },
                ) {
                    Text(stringResource(R.string.settings_sync_add_folder))
                }
            }
        }

        Button(
            enabled = !busy,
            onClick = { runAction { syncNowAll() } },
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            } else {
                Icon(Icons.Filled.Sync, contentDescription = null)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(R.string.remote_screen_sync_now))
        }

        status?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
