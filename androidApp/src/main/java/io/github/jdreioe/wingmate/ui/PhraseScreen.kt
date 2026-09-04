package io.github.jdreioe.wingmate.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.*
import io.github.jdreioe.wingmate.application.FeatureUsageEvents
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.application.reportEvent
import io.github.jdreioe.wingmate.application.PhraseBloc
import io.github.jdreioe.wingmate.application.PhraseEvent
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.application.EditingAccessController
import io.github.jdreioe.wingmate.domain.CategoryItem
import io.github.jdreioe.wingmate.domain.CommunicationAction
import io.github.jdreioe.wingmate.domain.CommunicationPlaybackStatus
import io.github.jdreioe.wingmate.domain.CommunicationSession
import io.github.jdreioe.wingmate.domain.Message
import io.github.jdreioe.wingmate.domain.MessagePart
import io.github.jdreioe.wingmate.domain.MessagePartSource
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.fromScreenButton
import io.github.jdreioe.wingmate.domain.fromTextDiff
import io.github.jdreioe.wingmate.domain.phraseSubtree
import io.github.jdreioe.wingmate.domain.toScreenButtons
import io.github.jdreioe.wingmate.domain.PredictionResult
import io.github.jdreioe.wingmate.domain.TextEditingPolicy
import io.github.jdreioe.wingmate.domain.TextPredictionService
import io.github.jdreioe.wingmate.domain.TextSpan
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.FlowPreview
import androidx.compose.ui.res.stringResource
import org.koin.compose.getKoin
import org.koin.compose.koinInject

import com.hojmoseit.wingmate.R
internal fun supportsMathMode(ttsEngine: TtsEngine): Boolean =
    ttsEngine == TtsEngine.AZURE_USER_RESOURCE || ttsEngine == TtsEngine.AZURE_MANAGED

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalComposeUiApi::class,
    FlowPreview::class
)
@Composable
fun PhraseScreen(
    onBackToWelcome: (() -> Unit)? = null,
    onOpenBoardSetManager: (() -> Unit)? = null,
    onEditTypingScreen: (() -> Unit)? = null,
    initialBoardId: String? = null
) {
    val koin = getKoin()
    val phraseScreenScope = rememberCoroutineScope()
    val bloc = koinInject<PhraseBloc>()
    val featureUsageReporter = koinInject<FeatureUsageReporter>()
    val state by bloc.state.collectAsStateWithLifecycle()

    // Ensure initial list loads on first composition
    LaunchedEffect(bloc) {
        bloc.dispatch(PhraseEvent.Load)
    }

    // Load settings for UI scaling using reactive state manager
    val settings by rememberReactiveSettings()

    val communicationSession = koinInject<CommunicationSession>()
    val communicationState by communicationSession.state.collectAsStateWithLifecycle()
    val saidRepo = koinInject<io.github.jdreioe.wingmate.domain.SaidTextRepository>()
    val voiceUseCase = koinInject<VoiceUseCase>()
    val aacLogger = koinInject<io.github.jdreioe.wingmate.domain.AacLogger>()
    val boardRepo = koinInject<io.github.jdreioe.wingmate.domain.BoardRepository>()
    val editingAccessController = remember(koin) { koin.getOrNull<EditingAccessController>() }
    val obfParser = koinInject<io.github.jdreioe.wingmate.infrastructure.ObfParser>()

    val releaseBuild = isReleaseBuild()
    val predictionsEnabled = !releaseBuild
    val predictionService = remember(koin, predictionsEnabled) {
        if (predictionsEnabled) koin.getOrNull<TextPredictionService>() else null
    }
    val dictionaryLoader = remember(koin, predictionsEnabled) {
        if (predictionsEnabled) koin.getOrNull<io.github.jdreioe.wingmate.infrastructure.DictionaryLoader>() else null
    }
    val updateService = remember(koin) { koin.getOrNull<io.github.jdreioe.wingmate.domain.UpdateService>() }
    val filePicker = remember(koin) { koin.getOrNull<io.github.jdreioe.wingmate.platform.FilePicker>() }
    val phraseRepo = remember(koin) { koin.getOrNull<io.github.jdreioe.wingmate.domain.PhraseRepository>() }
    val audioClipboard = remember(koin) { koin.getOrNull<io.github.jdreioe.wingmate.platform.AudioClipboard>() }
    val shareService = remember(koin) { koin.getOrNull<io.github.jdreioe.wingmate.platform.ShareService>() }
    val enableObfObzImport = !releaseBuild
    var categoriesLoadedOnce by remember { mutableStateOf(false) }
    var categoriesLoadFailed by remember { mutableStateOf(false) }

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showVoiceSelection by remember { mutableStateOf(false) }
    var showUiLanguageDialog by remember { mutableStateOf(false) }
    var showSettingsExportDialog by remember { mutableStateOf(false) }
    var showSsmlDialog by remember { mutableStateOf(false) }
    var showTypingMutationUnlock by remember { mutableStateOf(false) }
    var pendingTypingMutation by remember { mutableStateOf<(() -> Unit)?>(null) }
    var appBarMenuExpanded by remember { mutableStateOf(false) }
    val showFullscreen by io.github.jdreioe.wingmate.presentation.DisplayWindowBus.show.collectAsStateWithLifecycle()
    val selectBoardDialogTitle = stringResource(R.string.phrase_screen_select_board_title)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Load persisted primary language for display in top e
        // Use the reactive settings as key to ensure this updates when settings change
        val primaryLanguageState = produceState(initialValue = settings.primaryLanguage, key1 = settings.primaryLanguage) {
            value = settings.primaryLanguage
        }
        val hasUsableSecondaryLanguage = produceState(
            initialValue = false,
            key1 = settings.secondaryLanguage,
            key2 = settings.primaryLanguage
        ) {
            val voice = runCatching { voiceUseCase.selected() }.getOrNull()
            val supported = voice?.supportedLanguages
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.distinct()
                .orEmpty()
            value = settings.secondaryLanguage.isNotBlank() &&
                settings.secondaryLanguage != settings.primaryLanguage &&
                supported.size > 1 &&
                settings.secondaryLanguage in supported
        }

            // Derived input: text comes from session, cursor is local UI state (Q3=a, Q8=a)
            var cursor by remember { mutableStateOf(TextRange(communicationState.activeMessage.displayText.length)) }
            var mathMode by remember { mutableStateOf(false) }
            LaunchedEffect(settings.ttsEngine) {
                if (!supportsMathMode(settings.ttsEngine)) {
                    mathMode = false
                }
            }
            val secondaryLanguageRanges = communicationState.activeMessage.languageSpans
                .filter { it.languageTag == settings.secondaryLanguage }
                .map { TextRange(it.range.start, it.range.endExclusive) }
            val textFieldFocusRequester = remember { FocusRequester() }
            val refocusInput = remember(textFieldFocusRequester) {
                { textFieldFocusRequester.requestFocus() }
            }
            val syncDisplayText = remember(showFullscreen) {
                { text: String ->
                    if (showFullscreen) {
                        io.github.jdreioe.wingmate.presentation.DisplayTextBus.set(text)
                    }
                }
            }
            LaunchedEffect(communicationState.activeMessage.displayText) {
                val text = communicationState.activeMessage.displayText
                if (cursor.start > text.length || cursor.end > text.length) {
                    cursor = TextRange(text.length)
                }
                syncDisplayText(text)
            }
            val input = TextFieldValue(
                text = communicationState.activeMessage.displayText,
                selection = cursor,
            )
            var predictions by remember { mutableStateOf(PredictionResult()) }

            val isSpeechPaused = communicationState.playbackStatus == CommunicationPlaybackStatus.Paused

            // selected voice / available languages for language selection
            val selectedVoiceState = produceState<io.github.jdreioe.wingmate.domain.Voice?>(
                initialValue = null,
                voiceUseCase,
                settings.primaryLanguage,
                settings.secondaryLanguage,
                showSettingsDialog,
            ) {
                value = runCatching { voiceUseCase.selected() }.getOrNull()
            }
            val uiScope = rememberCoroutineScope()
            val snackbarHostState = remember { SnackbarHostState() }
            val deletedMessage = stringResource(R.string.phrase_deleted)
            val undoLabel = stringResource(R.string.action_undo)
            val typingContentUnavailableMessage = stringResource(R.string.typing_screen_content_unavailable)
            val requestTypingMutation: ((() -> Unit) -> Unit) = { mutation ->
                if (
                    !categoriesLoadedOnce ||
                    categoriesLoadFailed ||
                    state.error != null
                ) {
                    phraseScreenScope.launch {
                        snackbarHostState.showSnackbar(typingContentUnavailableMessage)
                    }
                } else phraseScreenScope.launch {
                    if (editingAccessController?.requiresUnlock() == true) {
                        pendingTypingMutation = mutation
                        showTypingMutationUnlock = true
                    } else {
                        mutation()
                    }
                }
            }

            /**
             * Delete a phrase (and any sub-items) with a snackbar undo. The removed
             * subtree is captured up front so Undo re-adds the exact same nodes with
             * their original ids — repositories preserve caller-supplied ids on add.
             */
            fun deleteWithUndo(phraseId: String?) {
                if (phraseId.isNullOrBlank()) return
                val all = bloc.state.value.items
                val removed = phraseSubtree(all, phraseId)
                if (removed.isEmpty()) return
                bloc.dispatch(PhraseEvent.Delete(phraseId))
                uiScope.launch {
                    val result = snackbarHostState.showSnackbar(
                        message = deletedMessage,
                        actionLabel = undoLabel,
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        // Parent-first order so restored sub-items find their parent id.
                        removed.forEach { bloc.dispatch(PhraseEvent.Add(it)) }
                    }
                }
            }
            var historyItems by remember { mutableStateOf<List<io.github.jdreioe.wingmate.domain.SaidText>>(emptyList()) }
            
            // OBF Board State
            var currentBoard by remember { mutableStateOf<ObfBoard?>(null) }
            // Map of all boards (ID -> Board) for linking support in OBZ files
            var boardsMap by remember { mutableStateOf<Map<String, ObfBoard>>(emptyMap()) }
            // Navigation stack for going back to previous boards
            var boardStack by remember { mutableStateOf<List<ObfBoard>>(emptyList()) }
            // Extracted images from OBZ (path -> bytes)
            var extractedImages by remember { mutableStateOf<Map<String, ByteArray>>(emptyMap()) }
            
            // Legacy imported boards render their symbols from the shared Message too.
            val selectedObfButtons: List<Pair<ObfButton, ImageBitmap?>> =
                communicationState.activeMessage.parts.mapIndexed { index, part ->
                    val source = part.source as? MessagePartSource.ScreenButton
                    val original = source
                        ?.let { boardsMap[it.pageId] }
                        ?.buttons
                        ?.firstOrNull { it.id == source.buttonId }
                    val button = original ?: ObfButton(
                        id = source?.buttonId ?: "legacy-message-part-$index",
                        label = part.displayText.trimStart(),
                        vocalization = part.spokenText.trimStart(),
                        locale = part.languageTag,
                    ).withMathMode(part.mathMode)
                    button to null
                }

            PlatformBackHandler(enabled = currentBoard != null) {
                when {
                    boardStack.isNotEmpty() -> {
                        currentBoard = boardStack.last()
                        boardStack = boardStack.dropLast(1)
                    }
                    currentBoard != null -> {
                        currentBoard = null
                        boardStack = emptyList()
                    }
                }
            }

            LaunchedEffect(initialBoardId, boardRepo) {
                if (initialBoardId.isNullOrBlank()) return@LaunchedEffect
                val board = withContext(Dispatchers.IO) { boardRepo.getBoard(initialBoardId) }
                if (board != null) {
                    currentBoard = board
                    boardsMap = mapOf(board.id to board)
                    boardStack = emptyList()
                    extractedImages = emptyMap()
                }
            }

            // Track model version to re-trigger predictions when training finishes
            var predictionModelVersion by remember { mutableStateOf(0) }

            // Load history on start so the History category appears if there are existing items
            // Also train the prediction model on the history
            LaunchedEffect(saidRepo, primaryLanguageState.value) {
                try {
                    val list = saidRepo.list()
                    historyItems = list.filter { it.visibleInHistory }.sortedByDescending { it.date ?: it.createdAt ?: 0L }

                    if (!predictionsEnabled) return@LaunchedEffect
                    
                    // Train prediction model: first load base language dictionary, then user history
                    val ngramService = predictionService as? io.github.jdreioe.wingmate.infrastructure.SimpleNGramPredictionService
                    if (ngramService != null) {
                        if (dictionaryLoader != null) {
                            val dictWords = try {
                                dictionaryLoader.loadDictionary(primaryLanguageState.value)
                            } catch (failure: kotlinx.coroutines.CancellationException) {
                                throw failure
                            } catch (_: Exception) {
                                emptyList()
                            }
                            if (dictWords.isNotEmpty()) {
                                ngramService.setBaseLanguage(dictWords)
                                // History trained on TOP of dictionary, so don't clear
                                ngramService.train(list, clear = false)
                            } else {
                                // Unsupported/unavailable dictionaries fall back to private local history.
                                ngramService.train(list)
                            }
                        } else {
                            ngramService.train(list)
                        }
                        
                        predictionModelVersion++ // Trigger update
                    } else if (predictionService != null) {
                        predictionService.train(list)
                        predictionModelVersion++
                    }
                } catch (failure: kotlinx.coroutines.CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    // Preserve the currently visible history if a refresh fails.
                }
            }

            var observedSpeechRequestId by remember { mutableStateOf<Long?>(null) }
            LaunchedEffect(communicationState.currentSpeechRequestId, saidRepo) {
                val currentRequestId = communicationState.currentSpeechRequestId
                if (currentRequestId != null) {
                    observedSpeechRequestId = currentRequestId
                } else if (observedSpeechRequestId != null) {
                    observedSpeechRequestId = null
                    runCatching { saidRepo.list() }
                        .onSuccess { items ->
                            historyItems = items
                                .filter { it.visibleInHistory }
                                .sortedByDescending { it.date ?: it.createdAt ?: 0L }
                        }
                }
            }
            
            // Update predictions as user types or model retrains.
            // Debounce + minimum token length avoids running n-gram inference on every keypress.
            LaunchedEffect(predictionService, predictionModelVersion) {
                if (!predictionsEnabled) {
                    predictions = PredictionResult()
                    return@LaunchedEffect
                }
                if (predictionService == null || !predictionService.isTrained()) {
                    predictions = PredictionResult()
                    return@LaunchedEffect
                }

                snapshotFlow { input.text }
                    .debounce(250)
                    .distinctUntilChanged()
                    .collectLatest { currentText ->
                        val activeTokenLength = currentText
                            .trimEnd()
                            .substringAfterLast(' ', "")
                            .length

                        val shouldPredict = currentText.isNotBlank() &&
                            (currentText.lastOrNull() == ' ' || activeTokenLength >= 2)

                        // Clear only when input is fully empty; keep last suggestions
                        // while typing a short token so the bar doesn't blink.
                        if (currentText.isBlank()) {
                            predictions = PredictionResult()
                            return@collectLatest
                        }
                        if (!shouldPredict) {
                            return@collectLatest
                        }

                        predictions = predictionService.predict(currentText, maxWords = 5, maxLetters = 4)
                    }
            }

            val openBoardSets: () -> Unit = {
                featureUsageReporter.reportEvent(
                    FeatureUsageEvents.SCREEN_VIEW,
                    "screen" to "boardsets"
                )
                onOpenBoardSetManager?.invoke()
            }
            val toggleFullscreen = {
                io.github.jdreioe.wingmate.presentation.DisplayTextBus.set(input.text)
                if (showFullscreen) io.github.jdreioe.wingmate.presentation.DisplayWindowBus.close()
                else io.github.jdreioe.wingmate.presentation.DisplayWindowBus.open()
                featureUsageReporter.reportEvent(
                    FeatureUsageEvents.FULLSCREEN_TOGGLE,
                    "enabled" to (!showFullscreen).toString()
                )
            }
            val openSettings = {
                showSettingsDialog = true
                featureUsageReporter.reportEvent(
                    FeatureUsageEvents.SETTINGS_UPDATED,
                    "action" to "open_app_settings"
                )
            }
            // Transport actions shared by the Message bar and Action strip.
            val playInput: () -> Unit = {
                if (input.text.isBlank()) {
                    refocusInput()
                } else {
                    featureUsageReporter.reportEvent(
                        FeatureUsageEvents.PLAYBACK_PLAY,
                        "source" to "input",
                        "has_secondary_ranges" to secondaryLanguageRanges.isNotEmpty().toString()
                    )
                    communicationSession.accept(
                        CommunicationAction.SpeakActive(
                            voice = selectedVoiceState.value?.copy(mathMode = mathMode),
                        )
                    )
                    refocusInput()
                }
            }
            val pauseSpeech: () -> Unit = {
                featureUsageReporter.reportEvent(
                    FeatureUsageEvents.PLAYBACK_PAUSE,
                    "source" to "input"
                )
                communicationSession.accept(CommunicationAction.Pause)
                refocusInput()
            }
            val stopSpeech: () -> Unit = {
                featureUsageReporter.reportEvent(
                    FeatureUsageEvents.PLAYBACK_STOP,
                    "source" to "input"
                )
                communicationSession.accept(CommunicationAction.Stop)
                refocusInput()
            }
            val resumeSpeech: () -> Unit = {
                featureUsageReporter.reportEvent(
                    FeatureUsageEvents.PLAYBACK_RESUME,
                    "source" to "input"
                )
                communicationSession.accept(CommunicationAction.Resume)
                refocusInput()
            }
            // Selection-dependent actions shared by every Message control surface.
            val toggleSecondarySelection: (() -> Unit)? = if (hasUsableSecondaryLanguage.value) {
                {
                    val normalizedSelection = normalizeRange(input.selection, input.text.length)
                    val selectionHasLength = normalizedSelection.spanLength() > 0
                    val alreadySecondary = selectionHasLength &&
                        isRangeFullySecondary(normalizedSelection, secondaryLanguageRanges)
                    if (!selectionHasLength) {
                        refocusInput()
                    } else {
                        communicationSession.accept(
                            CommunicationAction.ToggleLanguage(
                                range = TextSpan(normalizedSelection.start, normalizedSelection.end),
                                languageTag = settings.secondaryLanguage,
                            )
                        )
                        featureUsageReporter.reportEvent(
                            FeatureUsageEvents.PLAYBACK_SECONDARY_TOGGLE,
                            "enabled" to (!alreadySecondary).toString()
                        )
                        refocusInput()
                    }
                }
            } else null
            val toggleThatThought: () -> Unit = {
                val wasHoldingMessage = communicationState.heldMessage != null
                communicationSession.accept(CommunicationAction.SwapHeldMessage)
                // cursor reset; text derives from new snapshot synchronously
                cursor = TextRange(communicationSession.state.value.activeMessage.displayText.length)
                featureUsageReporter.reportEvent(
                    FeatureUsageEvents.PLAYBACK_ON_THAT_THOUGHT,
                    "action" to if (wasHoldingMessage) "resume" else "pin"
                )
                syncDisplayText(communicationSession.state.value.activeMessage.displayText)
                refocusInput()
            }
            fun replaceInputText(newText: String, cursorPos: Int) {
                communicationSession.accept(
                    Message.fromTextDiff(
                        currentText = communicationSession.state.value.activeMessage.displayText,
                        newText = newText,
                        mathMode = mathMode,
                    )
                )
                cursor = TextRange(cursorPos.coerceIn(0, newText.length))
                syncDisplayText(newText)
            }
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val useOverflowMenu = maxWidth <= 720.dp
                        TopAppBar(
                            title = { Text("Wingmate", style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = MaterialTheme.typography.titleLarge.fontSize * settings.fontSizeScale
                            )) },
                            actions = {
                                if (useOverflowMenu) {
                                    Box {
                                        IconButton(onClick = { appBarMenuExpanded = true }) {
                                            Icon(
                                                imageVector = Icons.Filled.MoreVert,
                                                contentDescription = stringResource(R.string.common_more_actions)
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = appBarMenuExpanded,
                                            onDismissRequest = { appBarMenuExpanded = false }
                                        ) {
                                            if (supportsMathMode(settings.ttsEngine)) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.speech_math_mode)) },
                                                    leadingIcon = {
                                                        Icon(
                                                            imageVector = Icons.Filled.Calculate,
                                                            contentDescription = null,
                                                            tint = if (mathMode) {
                                                                MaterialTheme.colorScheme.primary
                                                            } else {
                                                                MaterialTheme.colorScheme.onSurfaceVariant
                                                            }
                                                        )
                                                    },
                                                    onClick = {
                                                        mathMode = !mathMode
                                                        appBarMenuExpanded = false
                                                    }
                                                )
                                            }
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.mode_switch_to_screens)) },
                                                leadingIcon = {
                                                    Icon(Icons.Filled.GridView, contentDescription = null)
                                                },
                                                enabled = onOpenBoardSetManager != null,
                                                onClick = {
                                                    appBarMenuExpanded = false
                                                    openBoardSets()
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.phrase_screen_toggle_fullscreen_cd)) },
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = if (showFullscreen) {
                                                            Icons.Filled.FullscreenExit
                                                        } else {
                                                            Icons.Filled.Fullscreen
                                                        },
                                                        contentDescription = null
                                                    )
                                                },
                                                onClick = {
                                                    appBarMenuExpanded = false
                                                    toggleFullscreen()
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.phrase_screen_app_settings)) },
                                                leadingIcon = {
                                                    Icon(Icons.Filled.Settings, contentDescription = null)
                                                },
                                                onClick = {
                                                    appBarMenuExpanded = false
                                                    openSettings()
                                                }
                                            )
                                        }
                                    }
                                } else {
                                    if (supportsMathMode(settings.ttsEngine)) {
                                        IconToggleButton(
                                            checked = mathMode,
                                            onCheckedChange = { mathMode = it }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Calculate,
                                                contentDescription = stringResource(R.string.speech_math_mode_description),
                                                tint = if (mathMode) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                }
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = openBoardSets,
                                        enabled = onOpenBoardSetManager != null
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.GridView,
                                            contentDescription = stringResource(R.string.mode_switch_to_screens)
                                        )
                                    }

                                    IconButton(onClick = toggleFullscreen) {
                                        Icon(
                                            imageVector = if (showFullscreen) {
                                                Icons.Filled.FullscreenExit
                                            } else {
                                                Icons.Filled.Fullscreen
                                            },
                                            contentDescription = stringResource(R.string.phrase_screen_toggle_fullscreen_cd)
                                        )
                                    }

                                    IconButton(onClick = openSettings) {
                                        Icon(
                                            imageVector = Icons.Filled.Settings,
                                            contentDescription = stringResource(R.string.phrase_screen_app_settings)
                                        )
                                    }
                                }
                            }
                        )
                    }
                },
                bottomBar = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .platformImePadding()
                            .navigationBarsPadding()
                            .padding(16.dp)
                    ) {
                        val normalizedSelection = normalizeRange(input.selection, input.text.length)
                        val selectionHasLength = normalizedSelection.spanLength() > 0
                        val selectionAlreadySecondary = selectionHasLength &&
                            isRangeFullySecondary(normalizedSelection, secondaryLanguageRanges)
                        PlaybackControls(
                            onPlay = playInput,
                            onPause = pauseSpeech,
                            onStop = stopSpeech,
                            onResume = resumeSpeech,
                            isPaused = isSpeechPaused,
                            onPlaySecondary = toggleSecondarySelection,
                            onThatThought = toggleThatThought,
                            isSecondarySelectionActive = selectionAlreadySecondary,
                            isSecondaryActionEnabled = selectionHasLength,
                            isOnThatThoughtActive = communicationState.heldMessage != null,
                        )
                    }
                },
            ) { innerPadding ->
                BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                    val isWide = maxWidth >= 900.dp
                    Row(Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
                    if (state.loading) Text(stringResource(R.string.phrase_screen_loading), style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                    ))
                    state.error?.let {
                        Column {
                            Text(
                                stringResource(R.string.phrase_screen_error, it),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                                ),
                            )
                            Button(onClick = { bloc.dispatch(PhraseEvent.Load) }) {
                                Text(stringResource(R.string.common_retry))
                            }
                        }
                    }

                    // Dynamically resolve CategoryUseCase; it might be registered after initial composition (platform overrides)
                    val categoryUseCaseState = remember { mutableStateOf<io.github.jdreioe.wingmate.application.CategoryUseCase?>(null) }
                    LaunchedEffect(Unit) {
                        // Retry until available (or stop after some attempts if desired)
                        repeat(30) {
                            if (categoryUseCaseState.value != null) return@LaunchedEffect
                            categoryUseCaseState.value = koin.getOrNull<io.github.jdreioe.wingmate.application.CategoryUseCase>()
                            if (categoryUseCaseState.value != null) return@LaunchedEffect
                            delay(250)
                        }
                        if (categoryUseCaseState.value == null) categoriesLoadFailed = true
                    }
                    var categories by remember { mutableStateOf<List<CategoryItem>>(emptyList()) }
                    var categoryLoadRevision by remember { mutableIntStateOf(0) }
                    val coroutineScope = rememberCoroutineScope()

                    // load initial categories
                    LaunchedEffect(categoryUseCaseState.value, categoryLoadRevision) {
                        val uc = categoryUseCaseState.value ?: return@LaunchedEffect
                        runCatching { uc.list() }
                            .onSuccess {
                                categories = it
                                categoriesLoadedOnce = true
                                categoriesLoadFailed = false
                            }
                            .onFailure { categoriesLoadFailed = true }
                    }

                    if (categoriesLoadFailed) {
                        RepositoryFailurePanel(
                            onRetry = { categoryLoadRevision++ },
                        )
                    }

                    // Category selector with dialog
                    var selectedPage by remember { mutableStateOf<TypingPageSelection>(TypingPageSelection.AllPhrases) }
                    val selectedCategory = (selectedPage as? TypingPageSelection.Category)?.category
                    var showAddCategoryDialog by remember { mutableStateOf(false) }
                    var confirmDeleteCategory by remember { mutableStateOf<CategoryItem?>(null) }

                    val secondaryHighlightColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)
                    
                    val ssmlRanges = remember(input.text) {
                        Regex("\\[(\\d+(\\.\\d+)?)s\\]").findAll(input.text).map {
                            androidx.compose.ui.text.TextRange(it.range.first, it.range.last + 1)
                        }.toList()
                    }
                    val ssmlHighlightColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)

                    SecondaryLanguageTextField(
                        value = input,
                        onValueChange = { newValue ->
                            communicationSession.accept(
                                Message.fromTextDiff(
                                    currentText = communicationSession.state.value.activeMessage.displayText,
                                    newText = newValue.text,
                                    mathMode = mathMode,
                                )
                            )
                            cursor = newValue.selection
                            syncDisplayText(newValue.text)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(
                                min = (120.dp * settings.inputFieldScale),
                                max = (180.dp * settings.inputFieldScale),
                            ),
                        focusRequester = textFieldFocusRequester,
                        highlightRanges = secondaryLanguageRanges,
                        highlightColor = secondaryHighlightColor,
                        ssmlRanges = ssmlRanges,
                        ssmlColor = ssmlHighlightColor,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        minLines = 4,
                        maxLines = 6,
                        placeholder = {
                            Text(
                                stringResource(R.string.phrase_screen_enter_text_placeholder),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    )
                    

                    // Typing vocabulary shown below the Message bar.
                    var showEditDialog by remember { mutableStateOf(false) }
                    var showAddPhraseDialog by remember { mutableStateOf(false) }
                    var editingPhrase by remember { mutableStateOf<Phrase?>(null) }
                    // Show only actual phrase items (not category markers), filtered by selected category
                    val isHistory = settings.historyVisible && selectedPage == TypingPageSelection.History
                    val selectedCategoryId = selectedCategory?.id
                    val visiblePhrases by remember(isHistory, historyItems, state.items, selectedCategoryId) {
                        derivedStateOf {
                            if (isHistory) {
                                // Map history items to ephemeral Phrase objects to reuse the grid UI; hide Add tile for this view
                                historyItems.mapIndexed { idx, s ->
                                    val stableHistoryId = s.id?.toString() ?: (s.date ?: s.createdAt ?: idx.toLong()).toString()
                                    Phrase(
                                        id = "history_$stableHistoryId",
                                        text = s.saidText ?: "",
                                        // History cards must represent what was said, not the voice that said it.
                                        name = null,
                                        backgroundColor = null,
                                        parentId = null,
                                        createdAt = s.date ?: s.createdAt ?: 0L,
                                        recordingPath = s.audioFilePath
                                    )
                                }
                            } else {
                                state.items.filter { selectedCategoryId == null || it.parentId == selectedCategoryId }
                            }
                        }
                    }
                    // #119: unified phrase playback for the grid's explicit play affordance and
                    // immediate-policy insertion. Plays the recording when present, else TTS.
                    fun speakPhraseFromGrid(phrase: Phrase) {
                        val textToSpeak = phrase.name?.ifBlank { null } ?: phrase.text
                        communicationSession.accept(
                            CommunicationAction.SpeakPart(
                                part = MessagePart(
                                    displayText = phrase.text,
                                    spokenText = textToSpeak,
                                    source = io.github.jdreioe.wingmate.domain.MessagePartSource.Phrase(phrase.id),
                                    recordingPath = phrase.recordingPath,
                                ),
                                voice = selectedVoiceState.value,
                            )
                        )
                        featureUsageReporter.reportEvent(
                            FeatureUsageEvents.PHRASE_PLAYED,
                            "source" to "grid",
                            "used_recording" to (phrase.recordingPath != null).toString()
                        )
                    }
                    val playPhraseFromGrid: (Phrase) -> Unit = { phrase ->
                        // Classic Folder Navigation: if item has a linked board, entering it updates the view
                        if (phrase.linkedBoardId != null) {
                            uiScope.launch {
                                selectedPage = TypingPageSelection.Category(
                                    io.github.jdreioe.wingmate.domain.CategoryItem(
                                        id = phrase.id,
                                        name = phrase.text,
                                        isFolder = true,
                                    )
                                )
                            }
                        } else {
                            speakPhraseFromGrid(phrase)
                        }
                    }
                    val insertPhraseFromGrid: (Phrase) -> Unit = { phrase ->
                        if (phrase.linkedBoardId != null) {
                            playPhraseFromGrid(phrase)
                        } else {
                            val cursorPos = cursor.start.coerceIn(0, input.text.length)
                            val currentMessage = communicationSession.state.value.activeMessage
                            val updatedMessage = currentMessage.insertPhrase(cursorPos, phrase)
                            communicationSession.accept(
                                CommunicationAction.ReplaceMessage(updatedMessage)
                            )
                            cursor = TextRange(cursorPos + phrase.text.length)
                            syncDisplayText(updatedMessage.displayText)
                            featureUsageReporter.reportEvent(
                                FeatureUsageEvents.PHRASE_INSERTED,
                                "source" to if (isHistory) "history" else "grid",
                            )
                            if (settings.speechPolicy == io.github.jdreioe.wingmate.domain.SpeechPolicy.Immediate) {
                                speakPhraseFromGrid(phrase)
                            }
                        }
                    }

                    // On narrow screens, show predictions while typing and the SSML entry otherwise.
                    val isKeyboardVisible = WindowInsets.ime.asPaddingValues().calculateBottomPadding() > 0.dp
                    if (predictionsEnabled && !isWide && isKeyboardVisible && (predictions.words.isNotEmpty() || predictions.letters.isNotEmpty())) {
                         PredictionBar(
                            predictions = predictions,
                            onWordSelected = { word ->
                                val fv = input
                                val updated = completePredictedWord(fv, word)
                                replaceInputText(updated.text, updated.selection.start)
                            },
                            onLetterSelected = { letter ->
                                val fv = input
                                val updated = insertPredictedText(fv, letter.toString())
                                replaceInputText(updated.text, updated.selection.start)
                            },
                            fontSizeScale = settings.fontSizeScale,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    } else if (!isWide) {
                        OutlinedButton(
                            onClick = { showSsmlDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                        ) {
                            Text(
                                stringResource(R.string.phrase_screen_ssml_controls),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    if (categoryUseCaseState.value == null) {
                        Text(stringResource(R.string.phrase_screen_loading), style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = MaterialTheme.typography.labelSmall.fontSize * settings.fontSizeScale
                        ), color = MaterialTheme.colorScheme.outline)
                    }

                    // Category chips
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 0.dp)
                    ) {
                        // "All" chip to show all phrases
                        item {
                            FilterChip(
                                selected = selectedPage == TypingPageSelection.AllPhrases,
                                onClick = { selectedPage = TypingPageSelection.AllPhrases },
                                label = { Text(stringResource(R.string.category_all), style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                                )) }
                            )
                        }
                        
                        // Category chips
                        itemsIndexed(categories, key = { _, category -> category.id }) { index, category ->
                            var showCategoryMenu by remember { mutableStateOf(false) }
                            Box {
                                FilterChip(
                                    selected = selectedCategory?.id == category.id,
                                    onClick = {
                                        if (selectedCategory?.id == category.id) {
                                            requestTypingMutation { showCategoryMenu = true }
                                        } else {
                                            selectedPage = TypingPageSelection.Category(category)
                                        }
                                    },
                                    label = { Text(category.name ?: stringResource(R.string.category_all), style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                                    )) },
                                    modifier = Modifier
                                        .then(
                                            if (isDesktop()) {
                                                Modifier.pointerInput(Unit) {
                                                    awaitPointerEventScope {
                                                        while (true) {
                                                            val event = awaitPointerEvent()
                                                            if (event.type == PointerEventType.Press &&
                                                                event.buttons.isSecondaryPressed) {
                                                                requestTypingMutation { showCategoryMenu = true }
                                                            }
                                                        }
                                                    }
                                                }
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .combinedClickable(
                                            onClick = {
                                                if (selectedCategory?.id == category.id) {
                                                    requestTypingMutation { showCategoryMenu = true }
                                                } else {
                                                    selectedPage = TypingPageSelection.Category(category)
                                                }
                                            },
                                            onLongClick = { requestTypingMutation { showCategoryMenu = true } }
                                        )
                                )
                                if (showCategoryMenu) {
                                    ModalBottomSheet(onDismissRequest = { showCategoryMenu = false }) {
                                        Column(modifier = Modifier.padding(bottom = 24.dp)) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.category_move_left), style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                                    )) }, enabled = index > 0, onClick = {
                                        showCategoryMenu = false
                                        val uc = categoryUseCaseState.value
                                        if (index > 0 && uc != null) {
                                            coroutineScope.launch(Dispatchers.IO) {
                                                runCatching {
                                                    uc.move(index, index - 1)
                                                    uc.list()
                                                }.onSuccess { updated ->
                                                    coroutineScope.launch { categories = updated }
                                                }.onFailure {
                                                    coroutineScope.launch { categoriesLoadFailed = true }
                                                }
                                            }
                                        }
                                    })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.category_move_right), style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                                    )) }, enabled = index < categories.lastIndex, onClick = {
                                        showCategoryMenu = false
                                        val uc = categoryUseCaseState.value
                                        if (index < categories.lastIndex && uc != null) {
                                            coroutineScope.launch(Dispatchers.IO) {
                                                runCatching {
                                                    uc.move(index, index + 1)
                                                    uc.list()
                                                }.onSuccess { updated ->
                                                    coroutineScope.launch { categories = updated }
                                                }.onFailure {
                                                    coroutineScope.launch { categoriesLoadFailed = true }
                                                }
                                            }
                                        }
                                    })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.category_delete_with_phrases), style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                                    )) }, onClick = {
                                        showCategoryMenu = false
                                        // Confirm dialog
                                        confirmDeleteCategory = category
                                    })
                                        }
                                    }
                                }
                            }
                        }
                        // History chip: appears only when there are items; placed immediately after user categories
                        if (settings.historyVisible && historyItems.isNotEmpty()) {
                            item {
                                FilterChip(
                                    selected = selectedPage == TypingPageSelection.History,
                                    onClick = { selectedPage = TypingPageSelection.History },
                                    label = { Text(stringResource(R.string.category_history), style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                                    )) }
                                )
                            }
                        }

                        // Add category chip
                        item {
                            FilterChip(
                                selected = false,
                                onClick = { requestTypingMutation { showAddCategoryDialog = true } },
                                label = { 
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Filled.Add,
                                            contentDescription = stringResource(R.string.category_add_cd),
                                            modifier = Modifier.size((16.dp * settings.playbackIconScale))
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(stringResource(R.string.common_add), style = MaterialTheme.typography.bodyLarge.copy(
                                            fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                                        ))
                                    }
                                }
                            )
                        }

                        // Note: History chip is added above, before the Add chip
                    }

                    // Refresh history from repo when switching to History
                    LaunchedEffect(selectedPage) {
                        if (settings.historyVisible && selectedPage == TypingPageSelection.History) {
                            try {
                                val list = saidRepo.list()
                                historyItems = list.filter { it.visibleInHistory }.sortedByDescending { it.date ?: it.createdAt ?: 0L }
                            } catch (_: Throwable) {}
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Add category dialog
                    if (showAddCategoryDialog) {
                        var categoryName by remember { mutableStateOf("") }
                        AlertDialog(
                            onDismissRequest = { showAddCategoryDialog = false },
                            title = { Text(stringResource(R.string.category_add_title), style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = MaterialTheme.typography.titleLarge.fontSize * settings.fontSizeScale
                            )) },
                            text = {
                                val showKeyboard = Modifier.showKeyboardOnFocus()
                                OutlinedTextField(
                                    value = categoryName,
                                    onValueChange = { categoryName = it },
                                    placeholder = { Text(stringResource(R.string.category_name_label), style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                                    )) },
                                    singleLine = true,
                                    modifier = Modifier.then(showKeyboard)
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        val name = categoryName.trim()
                                        if (name.isNotBlank() && !categories.any { it.name.equals(name, ignoreCase = true) }) {
                                            val ucImmediate = categoryUseCaseState.value ?: koin.getOrNull<io.github.jdreioe.wingmate.application.CategoryUseCase>()?.also { categoryUseCaseState.value = it }
                                            // Always create an ephemeral chip so user sees immediate feedback
                                            val temp = io.github.jdreioe.wingmate.domain.CategoryItem(id = "temp_${name}_${System.currentTimeMillis()}", name = name, selectedLanguage = primaryLanguageState.value)
                                            categories = categories + temp
                                            selectedPage = TypingPageSelection.Category(temp)
                                            coroutineScope.launch(Dispatchers.IO) {
                                                // Wait for a real use case if not yet available
                                                var uc = ucImmediate
                                                var attempts = 0
                                                while (uc == null && attempts < 40) { // up to ~10s
                                                    kotlinx.coroutines.delay(250)
                                                    uc = categoryUseCaseState.value ?: koin.getOrNull<io.github.jdreioe.wingmate.application.CategoryUseCase>()?.also { categoryUseCaseState.value = it }
                                                    attempts++
                                                }
                                                if (uc != null) {
                                                    try {
                                                        val added = uc.add(temp.copy(id = ""))
                                                        val newList = uc.list()
                                                        coroutineScope.launch {
                                                            categories = newList
                                                            selectedPage = TypingPageSelection.Category(
                                                                newList.find { it.id == added.id } ?: added
                                                            )
                                                        }
                                                    } catch (t: Throwable) {
                                                        // Roll back ephemeral on failure
                                                        coroutineScope.launch {
                                                            categories = categories.filterNot { it.id == temp.id }
                                                            categoriesLoadFailed = true
                                                        }
                                                    }
                                                } else {
                                                    // Could not persist; mark temp visually by leaving it (user session only)
                                                }
                                            }
                                        }
                                        showAddCategoryDialog = false
                                        categoryName = ""
                                    }
                                ) {
                                    Text(stringResource(R.string.common_add), style = MaterialTheme.typography.labelLarge.copy(
                                        fontSize = MaterialTheme.typography.labelLarge.fontSize * settings.fontSizeScale
                                    ))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { 
                                    showAddCategoryDialog = false
                                    categoryName = ""
                                }) {
                                    Text(stringResource(R.string.common_cancel), style = MaterialTheme.typography.labelLarge.copy(
                                        fontSize = MaterialTheme.typography.labelLarge.fontSize * settings.fontSizeScale
                                    ))
                                }
                            }
                        )
                    }

                    // Confirm delete category cascade
                    if (confirmDeleteCategory != null) {
                        AlertDialog(
                            onDismissRequest = { confirmDeleteCategory = null },
                            title = { Text(stringResource(R.string.category_delete_title), style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = MaterialTheme.typography.titleLarge.fontSize * settings.fontSizeScale
                            )) },
                            text = { Text(stringResource(R.string.category_delete_message, confirmDeleteCategory?.name.orEmpty()), style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                            )) },
                            confirmButton = {
                                TextButton(onClick = {
                                    val cat = confirmDeleteCategory
                                    confirmDeleteCategory = null
                                    if (cat != null) {
                                        val uc = categoryUseCaseState.value
                                        if (uc != null) {
                                            coroutineScope.launch(Dispatchers.IO) {
                                                // Delete phrases under this category (PhraseRepo)
                                                val phraseRepository = phraseRepo ?: run {
                                                    coroutineScope.launch { categoriesLoadFailed = true }
                                                    return@launch
                                                }
                                                val allPhrases = runCatching {
                                                    phraseRepository.getAll()
                                                }.getOrElse {
                                                    coroutineScope.launch { categoriesLoadFailed = true }
                                                    return@launch
                                                }
                                                val toDelete = allPhrases.filter { it.parentId == cat.id }
                                                val updated = runCatching {
                                                    toDelete.forEach { phraseRepository.delete(it.id) }
                                                    uc.delete(cat.id)
                                                    uc.list()
                                                }.getOrElse {
                                                    coroutineScope.launch { categoriesLoadFailed = true }
                                                    return@launch
                                                }
                                                coroutineScope.launch {
                                                    categories = updated
                                                    if (selectedCategory?.id == cat.id) {
                                                        selectedPage = TypingPageSelection.AllPhrases
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge.copy(
                                    fontSize = MaterialTheme.typography.labelLarge.fontSize * settings.fontSizeScale
                                )) }
                            },
                            dismissButton = { TextButton(onClick = { confirmDeleteCategory = null }) { Text(stringResource(R.string.common_cancel), style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = MaterialTheme.typography.labelLarge.fontSize * settings.fontSizeScale
                            )) } }
                        )
                    }

                    PhraseGrid(
                        phrases = visiblePhrases,
                        onInsert = insertPhraseFromGrid,
                        onPlay = playPhraseFromGrid,
                        onPlaySecondary = if (hasUsableSecondaryLanguage.value) {
                            { phrase ->
                                val textToSpeak = phrase.name?.ifBlank { null } ?: phrase.text
                                communicationSession.accept(
                                    CommunicationAction.SpeakPart(
                                        part = MessagePart(
                                            displayText = phrase.text,
                                            spokenText = textToSpeak,
                                            source = MessagePartSource.Phrase(phrase.id),
                                            recordingPath = phrase.recordingPath,
                                        ),
                                        voice = selectedVoiceState.value?.copy(
                                            selectedLanguage = settings.secondaryLanguage,
                                        ),
                                    )
                                )
                                featureUsageReporter.reportEvent(
                                    FeatureUsageEvents.PHRASE_PLAYED_SECONDARY,
                                    "source" to "grid",
                                    "used_recording" to (phrase.recordingPath != null).toString(),
                                )
                            }
                        } else null,
                        onLongPress = { phrase ->
                            if (!isHistory) {
                                editingPhrase = phrase
                                requestTypingMutation { showEditDialog = true }
                            }
                        },
                        onMove = { from, to -> bloc.dispatch(PhraseEvent.Move(from, to)) },
                        onAddPhrase = {
                            requestTypingMutation { showAddPhraseDialog = true }
                        },
                        onDeletePhrase = { phrase ->
                            requestTypingMutation { deleteWithUndo(phrase.id) }
                        },
                        categories = categories,
                        defaultCategoryId = selectedCategory?.id,
                        showAddTile = !isHistory,
                        readOnly = isHistory,
                        phraseFontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale,
                        onCopyAudio = { filePath ->
                            runCatching { audioClipboard?.copyAudioFile(filePath) }
                        },
                    )

                    if (showEditDialog && editingPhrase != null) {
                        val editedPhraseIndex = state.items.indexOfFirst { it.id == editingPhrase?.id }
                        val previousPhraseIndex = if (editedPhraseIndex > 0) {
                            (editedPhraseIndex - 1 downTo 0).firstOrNull {
                                state.items[it].parentId == editingPhrase?.parentId
                            }
                        } else null
                        val nextPhraseIndex = if (editedPhraseIndex in state.items.indices) {
                            (editedPhraseIndex + 1 until state.items.size).firstOrNull {
                                state.items[it].parentId == editingPhrase?.parentId
                            }
                        } else null
                        AddPhraseDialog(
                            onDismiss = { showEditDialog = false; editingPhrase = null },
                            categories = categories,
                            initialPhrase = editingPhrase,
                            onSave = { p -> bloc.dispatch(PhraseEvent.Edit(p)); showEditDialog = false; editingPhrase = null },
                            onDelete = { id -> deleteWithUndo(id); showEditDialog = false; editingPhrase = null },
                            onMoveEarlier = previousPhraseIndex?.let { target ->
                                { bloc.dispatch(PhraseEvent.Move(editedPhraseIndex, target)) }
                            },
                            onMoveLater = nextPhraseIndex?.let { target ->
                                { bloc.dispatch(PhraseEvent.Move(editedPhraseIndex, target)) }
                            },
                        )
                    }
                    if (showAddPhraseDialog) {
                        AddPhraseDialog(
                            onDismiss = { showAddPhraseDialog = false },
                            categories = categories,
                            defaultCategoryId = selectedCategory?.id,
                            onSave = { phrase ->
                                bloc.dispatch(PhraseEvent.Add(phrase))
                                showAddPhraseDialog = false
                            },
                        )
                    }
                }

                if (currentBoard != null) {
                    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Top bar with board name and navigation buttons
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Left side: Back button (if stacked) and board name
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (boardStack.isNotEmpty()) {
                                        IconButton(onClick = { 
                                            currentBoard = boardStack.last()
                                            boardStack = boardStack.dropLast(1)
                                        }) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                                        }
                                    }
                                    Text(currentBoard?.name ?: stringResource(R.string.board_legacy_fallback), style = MaterialTheme.typography.titleMedium)
                                }
                                // Right side: Erase and Home buttons
                                Row {
                                    IconButton(onClick = { 
                                        communicationSession.accept(CommunicationAction.Clear)
                                        cursor = TextRange(0)
                                        syncDisplayText("")
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.board_legacy_erase))
                                    }
                                    IconButton(onClick = { 
                                        currentBoard = null
                                        boardsMap = emptyMap()
                                        boardStack = emptyList()
                                    }) {
                                        Icon(Icons.Default.Home, contentDescription = stringResource(R.string.board_legacy_home))
                                    }
                                }
                            }
                            
                            // Textfield showing accumulated text; hidden when the board's
                            // own message bar is editable (one bar total).
                            val boardShowKeyboard = Modifier.showKeyboardOnFocus()
                            if (!settings.boardMessageBarEditable) {
                            OutlinedTextField(
                                value = input,
                                onValueChange = { newValue ->
                                    communicationSession.accept(
                                        Message.fromTextDiff(
                                            currentText = communicationSession.state.value.activeMessage.displayText,
                                            newText = newValue.text,
                                            mathMode = mathMode,
                                        )
                                    )
                                    cursor = newValue.selection
                                    syncDisplayText(newValue.text)
                                },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).then(boardShowKeyboard),
                                placeholder = { Text(stringResource(R.string.board_legacy_build_sentence)) },
                                trailingIcon = {
                                    if (input.text.isNotEmpty()) {
                                        IconButton(onClick = {
                                            communicationSession.accept(
                                                CommunicationAction.SpeakActive(
                                                    selectedVoiceState.value?.copy(mathMode = mathMode)
                                                )
                                            )
                                        }) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.board_legacy_speak))
                                        }
                                    }
                                },
                                singleLine = false,
                                maxLines = 3
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            }

                            // Board grid
                            ObfBoardView(
                                board = currentBoard!!,
                                messageBarEditable = settings.boardMessageBarEditable,
                                onSentenceChanged = { text ->
                                    communicationSession.accept(
                                        Message.fromTextDiff(
                                            currentText = communicationSession.state.value.activeMessage.displayText,
                                            newText = text,
                                            mathMode = mathMode,
                                        )
                                    )
                                    cursor = TextRange(text.length)
                                    syncDisplayText(text)
                                },
                                extractedImages = extractedImages,
                                selectedButtons = selectedObfButtons,
                                messageText = input.text,
                                onButtonClick = { button ->
                                    // Check if this is a linking button
                                    val loadBoard = button.loadBoard
                                    if (loadBoard != null) {
                                        // Try to find the linked board by ID or path
                                        val linkedBoard = loadBoard.id?.let { boardsMap[it] }
                                            ?: loadBoard.path?.let { path -> 
                                                boardsMap.values.find { it.id == path.removeSuffix(".obf") }
                                            }
                                        if (linkedBoard != null) {
                                            boardStack = boardStack + currentBoard!!
                                            currentBoard = linkedBoard
                                        }
                                    } else {
                                        val board = currentBoard!!
                                        val part = MessagePart.fromScreenButton(
                                            screenId = "legacy-obf",
                                            board = board,
                                            button = button,
                                            primaryLanguage = settings.primaryLanguage,
                                        )
                                        if (part != null) {
                                            communicationSession.accept(
                                                CommunicationAction.AppendPart(part, board.spellingMode)
                                            )
                                            communicationSession.accept(
                                                CommunicationAction.SpeakPart(
                                                    part = part,
                                                    voice = selectedVoiceState.value,
                                                )
                                            )
                                            cursor = TextRange(communicationSession.state.value.activeMessage.displayText.length)
                                            syncDisplayText(communicationSession.state.value.activeMessage.displayText)
                                        }
                                    }
                                },
                                onSpeakSentence = {
                                    if (input.text.isNotBlank()) {
                                        aacLogger.logSentenceSpeak(input.text)
                                        communicationSession.accept(
                                            CommunicationAction.SpeakActive(
                                                selectedVoiceState.value?.copy(mathMode = mathMode)
                                            )
                                        )
                                    }
                                },
                                onDeleteLast = {
                                    communicationSession.accept(
                                        CommunicationAction.RemoveLastPart(currentBoard!!.spellingMode)
                                    )
                                    cursor = TextRange(communicationSession.state.value.activeMessage.displayText.length)
                                    syncDisplayText(communicationSession.state.value.activeMessage.displayText)
                                },
                                onClearSentence = {
                                    communicationSession.accept(CommunicationAction.Clear)
                                    cursor = TextRange(0)
                                    syncDisplayText("")
                                },
                                modifier = Modifier.weight(1f).fillMaxWidth()
                            )
                        }
                    }
                }
                if (isWide) {
                    SsmlSidebar(
                        modifier = Modifier.width(320.dp).fillMaxHeight().padding(12.dp),
                        inputText = input.text,
                        inputSelection = input.selection,
                        onInsertSsml = { ssmlMarkup ->
                            val selection = normalizeRange(input.selection, input.text.length)
                            val newText = input.text.replaceRange(
                                selection.start,
                                selection.end,
                                ssmlMarkup,
                            )
                            replaceInputText(newText, selection.start + ssmlMarkup.length)
                        },
                    )
                }
                    }
                }
            }

            if (showSettingsDialog) {
                SettingsScreen(onDismiss = { showSettingsDialog = false }, onSaved = { showSettingsDialog = false }, onBackToWelcome = onBackToWelcome)
            }
            if (showSsmlDialog) {
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = { showSsmlDialog = false },
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(0.95f)
                            .fillMaxHeight(0.85f),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(R.string.phrase_screen_ssml_controls),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                                IconButton(onClick = { showSsmlDialog = false }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        stringResource(R.string.common_close),
                                    )
                                }
                            }
                            HorizontalDivider()
                            SsmlSidebar(
                                modifier = Modifier.weight(1f),
                                inputText = input.text,
                                inputSelection = input.selection,
                                onInsertSsml = { ssmlMarkup ->
                                    val selection = normalizeRange(input.selection, input.text.length)
                                    val newText = input.text.replaceRange(
                                        selection.start,
                                        selection.end,
                                        ssmlMarkup,
                                    )
                                    replaceInputText(newText, selection.start + ssmlMarkup.length)
                                },
                            )
                        }
                    }
                }
            }
            if (showTypingMutationUnlock && editingAccessController != null) {
                EditingAccessDialog(
                    controller = editingAccessController,
                    mode = EditingAccessDialogMode.Unlock,
                    onDismiss = {
                        showTypingMutationUnlock = false
                        pendingTypingMutation = null
                    },
                    onSuccess = {
                        showTypingMutationUnlock = false
                        pendingTypingMutation?.invoke()
                        pendingTypingMutation = null
                    },
                )
            }
            if (showVoiceSelection) {
                VoiceSelectionDialog(show = true, onDismiss = { showVoiceSelection = false })
            }
            if (showUiLanguageDialog) {
                UiLanguageDialog(
                    show = true,
                    onDismiss = { showUiLanguageDialog = false },
                    openPrimaryMenuInitially = true
                )
            }
            if (showSettingsExportDialog) {
                SettingsExportDialog(
                    onDismiss = { showSettingsExportDialog = false }
                )
            }
    }
}

@Composable
private fun SecondaryLanguageTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    highlightRanges: List<TextRange> = emptyList(),
    highlightColor: Color,
    ssmlRanges: List<TextRange> = emptyList(),
    ssmlColor: Color = MaterialTheme.colorScheme.primaryContainer,
    textStyle: TextStyle,
    placeholder: (@Composable () -> Unit)? = null,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
) {
    val annotated: AnnotatedString = remember(value.text, highlightRanges, highlightColor, ssmlRanges, ssmlColor) {
        buildAnnotatedString {
            append(value.text)
            highlightRanges.sortedBy { it.start }.forEach { range ->
                val start = range.start.coerceIn(0, value.text.length)
                val end = range.end.coerceIn(0, value.text.length)
                if (start < end) {
                    addStyle(SpanStyle(background = highlightColor), start, end)
                }
            }
            ssmlRanges.sortedBy { it.start }.forEach { range ->
                val start = range.start.coerceIn(0, value.text.length)
                val end = range.end.coerceIn(0, value.text.length)
                if (start < end) {
                    addStyle(SpanStyle(background = ssmlColor, fontWeight = FontWeight.Bold), start, end)
                }
            }
        }
    }

    // Wrap the plain TextFieldValue with our annotated string for display
    val styledValue = value.copy(annotatedString = annotated)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            if (value.text.isEmpty()) {
                placeholder?.invoke()
            }

            val showKeyboardMod = Modifier.showKeyboardOnFocus()
            val inputModifier = if (focusRequester != null) {
                Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .then(showKeyboardMod)
            } else {
                Modifier.fillMaxWidth().then(showKeyboardMod)
            }

            BasicTextField(
                value = styledValue,
                onValueChange = {
                    onValueChange(it.copy(annotatedString = AnnotatedString(it.text)))
                },
                textStyle = textStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = inputModifier,
                minLines = minLines,
                maxLines = maxLines,
            )
        }
    }
}

private fun normalizeRange(range: TextRange, maxLength: Int): TextRange {
    return TextEditingPolicy.normalize(range.toTextSpan(), maxLength).toTextRange()
}

private fun TextRange.spanLength(): Int = (end - start).coerceAtLeast(0)

private fun isRangeFullySecondary(selection: TextRange, ranges: List<TextRange>): Boolean {
    val textLength = maxOf(selection.end, ranges.maxOfOrNull { it.end } ?: 0)
    return TextEditingPolicy.isFullyCovered(selection.toTextSpan(), ranges.map { it.toTextSpan() }, textLength)
}

private fun TextRange.toTextSpan(): TextSpan = TextSpan(start, end)

private fun TextSpan.toTextRange(): TextRange = TextRange(start, endExclusive)

private fun completePredictedWord(value: TextFieldValue, suggestion: String): TextFieldValue {
    val result = TextEditingPolicy.completeWord(value.text, value.selection.start, suggestion)
    return TextFieldValue(result.text, selection = TextRange(result.cursor))
}

private fun insertPredictedText(value: TextFieldValue, text: String): TextFieldValue {
    val result = TextEditingPolicy.insert(value.text, value.selection.start, text)
    return TextFieldValue(result.text, selection = TextRange(result.cursor))
}

@Composable
private fun RepositoryFailurePanel(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.typing_screen_load_failed),
            color = MaterialTheme.colorScheme.error,
        )
        Button(onClick = onRetry) {
            Text(stringResource(R.string.common_retry))
        }
    }
}
