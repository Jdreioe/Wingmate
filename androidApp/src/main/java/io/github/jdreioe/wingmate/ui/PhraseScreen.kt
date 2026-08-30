package io.github.jdreioe.wingmate.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import io.github.jdreioe.wingmate.application.TypingScreenUseCase
import io.github.jdreioe.wingmate.application.EditingAccessController
import io.github.jdreioe.wingmate.domain.CategoryItem
import io.github.jdreioe.wingmate.domain.Message
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.activatePhrase
import io.github.jdreioe.wingmate.domain.phraseSubtree
import io.github.jdreioe.wingmate.domain.PredictionResult
import io.github.jdreioe.wingmate.domain.SpeechPolicy
import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.SpeechTextProcessor
import io.github.jdreioe.wingmate.domain.TextEditingPolicy
import io.github.jdreioe.wingmate.domain.TextPredictionService
import io.github.jdreioe.wingmate.domain.TextSpan
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.withLanguageOverride
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.BoardActivationBehavior
import io.github.jdreioe.wingmate.domain.obf.BoardSetGraph
import io.github.jdreioe.wingmate.domain.obf.ObfButtonActionEffect
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
private data class ThoughtDraft(
    val input: TextFieldValue,
    val secondaryLanguageRanges: List<TextRange>,
)

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
    val context = LocalContext.current
    val density = LocalDensity.current
    val typingTrayPreferences = remember(context) {
        context.getSharedPreferences("typing-screen-ui", android.content.Context.MODE_PRIVATE)
    }
    var typingTrayHeight by remember {
        mutableStateOf(typingTrayPreferences.getFloat("tray-height-dp", 360f).dp)
    }
    val bloc = koinInject<PhraseBloc>()
    val featureUsageReporter = koinInject<FeatureUsageReporter>()
    val state by bloc.state.collectAsStateWithLifecycle()

    // Ensure initial list loads on first composition
    LaunchedEffect(bloc) {
        bloc.dispatch(PhraseEvent.Load)
    }

    // Load settings for UI scaling using reactive state manager
    val settings by rememberReactiveSettings()

    val speechService = koinInject<io.github.jdreioe.wingmate.domain.SpeechService>()
    val saidRepo = koinInject<io.github.jdreioe.wingmate.domain.SaidTextRepository>()
    val voiceUseCase = koinInject<VoiceUseCase>()
    val aacLogger = koinInject<io.github.jdreioe.wingmate.domain.AacLogger>()
    val boardRepo = koinInject<io.github.jdreioe.wingmate.domain.BoardRepository>()
    val typingScreenUseCase = koinInject<TypingScreenUseCase>()
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
    var typingTemplateRevision by remember { mutableIntStateOf(0) }
    var typingTemplateGraph by remember { mutableStateOf<BoardSetGraph?>(null) }
    var typingTemplateLoadFailed by remember { mutableStateOf(false) }
    var categoriesLoadedOnce by remember { mutableStateOf(false) }
    var categoriesLoadFailed by remember { mutableStateOf(false) }
    LaunchedEffect(settings.gridColumns, typingScreenUseCase, typingTemplateRevision) {
        runCatching { typingScreenUseCase.getOrCreate(settings.gridColumns) }
            .onSuccess {
                typingTemplateGraph = it
                typingTemplateLoadFailed = false
            }
            .onFailure { typingTemplateLoadFailed = true }
    }

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showVoiceSelection by remember { mutableStateOf(false) }
    var showUiLanguageDialog by remember { mutableStateOf(false) }
    var showSettingsExportDialog by remember { mutableStateOf(false) }
    var showTypingResetConfirmation by remember { mutableStateOf(false) }
    var showTypingResetUnlock by remember { mutableStateOf(false) }
    var showTypingMutationUnlock by remember { mutableStateOf(false) }
    var pendingTypingMutation by remember { mutableStateOf<(() -> Unit)?>(null) }
    var appBarMenuExpanded by remember { mutableStateOf(false) }
    var typingMenuExpanded by remember { mutableStateOf(false) }
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

            // Input state (hoisted so topBar History button can access it)
            var input by remember { mutableStateOf(TextFieldValue("")) }
            var message by remember { mutableStateOf(Message()) }
            LaunchedEffect(input.text) {
                if (message.displayText != input.text) message = message.edit(input.text)
            }
            var mathMode by remember { mutableStateOf(false) }
            LaunchedEffect(settings.ttsEngine) {
                if (!supportsMathMode(settings.ttsEngine)) {
                    mathMode = false
                }
            }
            var secondaryLanguageRanges by remember { mutableStateOf<List<TextRange>>(emptyList()) }
            var pinnedThoughtDraft by remember { mutableStateOf<ThoughtDraft?>(null) }
            var scratchThoughtDraft by remember { mutableStateOf<ThoughtDraft?>(null) }
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
            var predictions by remember { mutableStateOf(PredictionResult()) }

            // Speech service state tracking
            var isSpeechPaused by remember(speechService) { mutableStateOf(speechService.isPaused()) }

            // Polling this every 500ms caused avoidable background wakeups while typing.
            // Keep it infrequent and let control actions update state immediately.
            LaunchedEffect(speechService) {
                while (true) {
                    val paused = speechService.isPaused()
                    if (paused != isSpeechPaused) {
                        isSpeechPaused = paused
                    }
                    val pollDelay = if (speechService.isPlaying()) 1000L else 4000L
                    kotlinx.coroutines.delay(pollDelay)
                }
            }

            // selected voice / available languages for language selection
            val selectedVoiceState = produceState<io.github.jdreioe.wingmate.domain.Voice?>(initialValue = null, key1 = voiceUseCase) {
                value = runCatching { voiceUseCase.selected() }.getOrNull()
            }
            val uiScope = rememberCoroutineScope()
            val snackbarHostState = remember { SnackbarHostState() }
            val deletedMessage = stringResource(R.string.phrase_deleted)
            val undoLabel = stringResource(R.string.action_undo)
            val typingResetFailedMessage = stringResource(R.string.typing_screen_reset_failed)
            val typingContentUnavailableMessage = stringResource(R.string.typing_screen_content_unavailable)
            val requestTypingMutation: ((() -> Unit) -> Unit) = { mutation ->
                if (
                    typingTemplateGraph == null ||
                    typingTemplateLoadFailed ||
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
            
            // Selected buttons for Symbol Bar
            var selectedObfButtons by remember { mutableStateOf<List<Pair<ObfButton, ImageBitmap?>>>(emptyList()) }

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
            val requestTypingScreenReset = {
                phraseScreenScope.launch {
                    if (editingAccessController?.requiresUnlock() == true) {
                        showTypingResetUnlock = true
                    } else {
                        showTypingResetConfirmation = true
                    }
                }
                Unit
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
                    isSpeechPaused = false
                    uiScope.launch(Dispatchers.IO) {
                        try {
                            val selected = runCatching { voiceUseCase.selected() }.getOrNull()
                                .withLanguageOverride(settings.primaryLanguage)
                                ?.copy(mathMode = mathMode)
                            val secondaryLang = settings.secondaryLanguage.takeIf { hasUsableSecondaryLanguage.value }
                            val inputText = input.text

                            val hasSSML = inputText.contains("<") && inputText.contains(">")
                            val canUseRecordedMix = !mathMode && !hasSSML && secondaryLanguageRanges.isEmpty()
                            val playedRecording = if (canUseRecordedMix) {
                                runCatching {
                                    trySpeakUsingRecordedPhrases(
                                        inputText = inputText,
                                        phrases = state.items,
                                        speechService = speechService,
                                        voice = selected
                                    )
                                }.getOrDefault(false)
                            } else {
                                false
                            }

                            if (!playedRecording) {
                                // When SSML is present, bypass segmentation and speak directly
                                if (hasSSML) {
                                    speechService.speak(inputText, selected, selected?.pitch, selected?.rate)
                                } else {
                                    val segments = if (!mathMode && secondaryLanguageRanges.isNotEmpty() && secondaryLang != null) {
                                        buildLanguageAwareSegments(inputText, secondaryLanguageRanges, secondaryLang)
                                    } else emptyList()
                                    if (segments.isNotEmpty()) {
                                        speechService.speakSegments(segments, selected, selected?.pitch, selected?.rate)
                                    } else {
                                        speechService.speak(inputText, selected, selected?.pitch, selected?.rate)
                                    }
                                }
                            }

                            // Refresh history from repo so the History chip appears after first save
                            // Also train prediction model incrementally with new phrase
                            try {
                                val list = saidRepo.list()
                                uiScope.launch { historyItems = list.filter { it.visibleInHistory }.sortedByDescending { it.date ?: it.createdAt ?: 0L } }
                                // Incremental learning for immediate feedback
                                if (predictionsEnabled) {
                                    (predictionService as? io.github.jdreioe.wingmate.infrastructure.SimpleNGramPredictionService)?.learnPhrase(input.text)
                                    predictionModelVersion++ // Trigger update after new entry
                                }
                            } catch (_: Throwable) {}
                        } catch (t: Throwable) {
                            // swallow for UI; diagnostics logged by service
                        }
                    }
                    refocusInput()
                }
            }
            val pauseSpeech: () -> Unit = {
                featureUsageReporter.reportEvent(
                    FeatureUsageEvents.PLAYBACK_PAUSE,
                    "source" to "input"
                )
                isSpeechPaused = true
                uiScope.launch {
                    runCatching { speechService.pause() }
                        .onFailure { isSpeechPaused = speechService.isPaused() }
                }
                refocusInput()
            }
            val stopSpeech: () -> Unit = {
                featureUsageReporter.reportEvent(
                    FeatureUsageEvents.PLAYBACK_STOP,
                    "source" to "input"
                )
                isSpeechPaused = false
                uiScope.launch {
                    runCatching { speechService.stop() }
                        .onFailure { isSpeechPaused = speechService.isPaused() }
                }
                refocusInput()
            }
            val resumeSpeech: () -> Unit = {
                featureUsageReporter.reportEvent(
                    FeatureUsageEvents.PLAYBACK_RESUME,
                    "source" to "input"
                )
                isSpeechPaused = false
                uiScope.launch {
                    runCatching { speechService.resume() }
                        .onFailure { isSpeechPaused = speechService.isPaused() }
                }
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
                        secondaryLanguageRanges = toggleSecondaryRange(
                            secondaryLanguageRanges,
                            normalizedSelection,
                            input.text.length
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
                val activeDraft = ThoughtDraft(
                    input = input,
                    secondaryLanguageRanges = secondaryLanguageRanges
                )

                if (pinnedThoughtDraft == null) {
                    pinnedThoughtDraft = activeDraft
                    val draftToLoad = scratchThoughtDraft
                        ?: ThoughtDraft(TextFieldValue(""), emptyList())
                    input = draftToLoad.input
                    secondaryLanguageRanges = draftToLoad.secondaryLanguageRanges
                    featureUsageReporter.reportEvent(
                        FeatureUsageEvents.PLAYBACK_ON_THAT_THOUGHT,
                        "action" to "pin"
                    )
                } else {
                    scratchThoughtDraft = activeDraft
                    val restoredDraft = pinnedThoughtDraft ?: ThoughtDraft(TextFieldValue(""), emptyList())
                    pinnedThoughtDraft = null
                    input = restoredDraft.input
                    secondaryLanguageRanges = restoredDraft.secondaryLanguageRanges
                    featureUsageReporter.reportEvent(
                        FeatureUsageEvents.PLAYBACK_ON_THAT_THOUGHT,
                        "action" to "resume"
                    )
                }

                syncDisplayText(input.text)
                refocusInput()
            }
            var showPhraseSheet by remember { mutableStateOf(false) }
            LaunchedEffect(showPhraseSheet) {
                AndroidAccessInputBus.restartScan()
            }
            val focusManager = LocalFocusManager.current
            val softwareKeyboardController = LocalSoftwareKeyboardController.current
            BackHandler(enabled = showPhraseSheet) {
                showPhraseSheet = false
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
                                                text = { Text(stringResource(R.string.typing_screen_edit)) },
                                                enabled = onEditTypingScreen != null,
                                                onClick = {
                                                    appBarMenuExpanded = false
                                                    onEditTypingScreen?.invoke()
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.typing_screen_reset)) },
                                                onClick = {
                                                    appBarMenuExpanded = false
                                                    requestTypingScreenReset()
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
                                    Box {
                                        IconButton(onClick = { typingMenuExpanded = true }) {
                                            Icon(
                                                imageVector = Icons.Filled.MoreVert,
                                                contentDescription = stringResource(R.string.common_more_actions),
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = typingMenuExpanded,
                                            onDismissRequest = { typingMenuExpanded = false },
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.typing_screen_edit)) },
                                                enabled = onEditTypingScreen != null,
                                                onClick = {
                                                    typingMenuExpanded = false
                                                    onEditTypingScreen?.invoke()
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.typing_screen_reset)) },
                                                onClick = {
                                                    typingMenuExpanded = false
                                                    requestTypingScreenReset()
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    }
                },
                // Playback controls live in the message bar and the "+" phrase
                // sheet; there is no bottom bar anymore (#243).
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

                    if (typingTemplateLoadFailed || categoriesLoadFailed) {
                        RepositoryFailurePanel(
                            onRetry = {
                                if (typingTemplateLoadFailed) typingTemplateRevision++
                                if (categoriesLoadFailed) categoryLoadRevision++
                            },
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
                            val previous = input
                            secondaryLanguageRanges = adjustRangesAfterEdit(previous.text, newValue.text, secondaryLanguageRanges)
                            input = newValue
                            syncDisplayText(newValue.text)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(2.dp, RoundedCornerShape(16.dp))
                            // Compact by default to match SymbolBar's 64dp resting height;
                            // still grows via the input-field-scale setting.
                            .heightIn(min = (64.dp * settings.inputFieldScale), max = (180.dp * settings.inputFieldScale)),
                        focusRequester = textFieldFocusRequester,
                        onFocused = { showPhraseSheet = false },
                        highlightRanges = secondaryLanguageRanges,
                        highlightColor = secondaryHighlightColor,
                        ssmlRanges = ssmlRanges,
                        ssmlColor = ssmlHighlightColor,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        minLines = 2,
                        maxLines = 6,
                        placeholder = {
                            Text(
                                stringResource(R.string.phrase_screen_enter_text_placeholder),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                VerticalDivider(
                                    modifier = Modifier
                                        .padding(horizontal = 8.dp)
                                        .height(40.dp)
                                )
                                if (isSpeechPaused) {
                                    FilledIconButton(
                                        onClick = resumeSpeech,
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.primary
                                        )
                                    ) {
                                        Icon(
                                            Icons.Rounded.SkipNext,
                                            contentDescription = stringResource(R.string.playback_resume)
                                        )
                                    }
                                } else {
                                    FilledIconButton(
                                        onClick = playInput,
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.primary
                                        )
                                    ) {
                                        Icon(
                                            Icons.Filled.PlayArrow,
                                            contentDescription = stringResource(R.string.playback_play)
                                        )
                                    }
                                }
                                IconButton(onClick = pauseSpeech) {
                                    Icon(
                                        Icons.Filled.Pause,
                                        contentDescription = stringResource(R.string.playback_pause)
                                    )
                                }
                                IconButton(onClick = stopSpeech) {
                                    Icon(
                                        Icons.Filled.Stop,
                                        contentDescription = stringResource(R.string.playback_stop)
                                    )
                                }
                                IconButton(onClick = {
                                    if (showPhraseSheet) {
                                        showPhraseSheet = false
                                        textFieldFocusRequester.requestFocus()
                                        softwareKeyboardController?.show()
                                    } else {
                                        focusManager.clearFocus()
                                        softwareKeyboardController?.hide()
                                        showPhraseSheet = true
                                    }
                                }) {
                                    Icon(
                                        if (showPhraseSheet) Icons.Filled.Keyboard else Icons.Filled.Add,
                                        contentDescription = stringResource(
                                            if (showPhraseSheet) R.string.board_native_keyboard_title
                                            else R.string.common_more_actions
                                        )
                                    )
                                }
                            }
                        }
                    )
                    

                    // Typing vocabulary shown below the Message bar.
                    var showEditDialog by remember { mutableStateOf(false) }
                    var showAddPhraseDialog by remember { mutableStateOf(false) }
                    var editingPhrase by remember { mutableStateOf<Phrase?>(null) }
                    // Show only actual phrase items (not category markers), filtered by selected category
                    val isHistory = settings.historyVisible && selectedPage == TypingPageSelection.History
                    val selectedCategoryId = selectedCategory?.id
                    var lastPhraseCategory by remember { mutableStateOf<CategoryItem?>(null) }
                    LaunchedEffect(selectedCategoryId, isHistory) {
                        if (!isHistory) lastPhraseCategory = selectedCategory
                    }
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
                    val compactPhrases by remember(state.items, lastPhraseCategory?.id) {
                        derivedStateOf {
                            val categoryId = lastPhraseCategory?.id
                            state.items.filter { categoryId == null || it.parentId == categoryId }
                        }
                    }
                    // #119: unified phrase playback for the grid's explicit play affordance and
                    // immediate-policy insertion. Plays the recording when present, else TTS.
                    suspend fun speakPhraseFromGrid(phrase: Phrase) {
                        val selected = runCatching { voiceUseCase.selected() }.getOrNull()
                        val textToSpeak = phrase.name?.ifBlank { null } ?: phrase.text
                        val playedRecorded = phrase.recordingPath?.let { path ->
                            runCatching {
                                speechService.speakRecordedAudio(
                                    audioFilePath = path,
                                    textForHistory = textToSpeak,
                                    voice = selected
                                )
                            }.getOrDefault(false)
                        } ?: false
                        if (!playedRecorded) {
                            speechService.speak(textToSpeak, selected)
                        }
                        featureUsageReporter.reportEvent(
                            FeatureUsageEvents.PHRASE_PLAYED,
                            "source" to "grid",
                            "used_recording" to playedRecorded.toString()
                        )
                        // Refresh history from repo
                        try {
                            val list = saidRepo.list()
                            uiScope.launch { historyItems = list.filter { it.visibleInHistory }.sortedByDescending { it.date ?: it.createdAt ?: 0L } }
                        } catch (_: Throwable) {}
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
                            uiScope.launch(Dispatchers.IO) {
                                try {
                                    speakPhraseFromGrid(phrase)
                                } catch (_: Throwable) {}
                            }
                        }
                    }
                    val typingActivationBehavior = typingTemplateGraph
                        ?.boardSet
                        ?.screenSettings
                        ?.activationBehavior
                        ?: BoardActivationBehavior.SpeakOnly
                    val activatePhraseFromTypingScreen: (Phrase) -> Unit = { phrase ->
                        val cursor = input.selection.start.coerceIn(0, input.text.length)
                        val activation = message.activatePhrase(
                            phrase = phrase,
                            cursor = cursor,
                            activationBehavior = typingActivationBehavior,
                            speechPolicy = settings.speechPolicy,
                        )
                        if (activation.message != message) {
                            val oldText = input.text
                            message = activation.message
                            val newText = message.displayText
                            val newCursor = cursor + phrase.text.length
                            secondaryLanguageRanges = adjustRangesAfterEdit(oldText, newText, secondaryLanguageRanges)
                            input = TextFieldValue(newText, selection = TextRange(newCursor))
                            syncDisplayText(newText)
                            featureUsageReporter.reportEvent(
                                FeatureUsageEvents.PHRASE_INSERTED,
                                "source" to if (isHistory) "history" else "typing_screen",
                            )
                        }
                        if (activation.shouldSpeak) {
                            playPhraseFromGrid(phrase)
                        }
                    }

                    fun replaceInputText(newText: String, cursor: Int) {
                        secondaryLanguageRanges = adjustRangesAfterEdit(input.text, newText, secondaryLanguageRanges)
                        input = TextFieldValue(newText, selection = TextRange(cursor.coerceIn(0, newText.length)))
                        syncDisplayText(newText)
                    }

                    val onTypingAction: (ObfButtonActionEffect) -> Unit = { effect ->
                        when (effect) {
                            is ObfButtonActionEffect.AppendText -> {
                                val selection = normalizeRange(input.selection, input.text.length)
                                val newText = input.text.replaceRange(selection.start, selection.end, effect.text)
                                replaceInputText(newText, selection.start + effect.text.length)
                            }
                            is ObfButtonActionEffect.WrapSelection -> {
                                val selection = normalizeRange(input.selection, input.text.length)
                                val selected = input.text.substring(selection.start, selection.end)
                                val replacement = effect.prefix + selected + effect.suffix
                                val newText = input.text.replaceRange(selection.start, selection.end, replacement)
                                val cursor = if (selected.isEmpty()) {
                                    selection.start + effect.prefix.length
                                } else {
                                    selection.start + replacement.length
                                }
                                replaceInputText(newText, cursor)
                            }
                            ObfButtonActionEffect.Backspace -> {
                                val selection = normalizeRange(input.selection, input.text.length)
                                if (selection.spanLength() > 0) {
                                    replaceInputText(input.text.removeRange(selection.start, selection.end), selection.start)
                                } else if (selection.start > 0) {
                                    replaceInputText(input.text.removeRange(selection.start - 1, selection.start), selection.start - 1)
                                }
                            }
                            ObfButtonActionEffect.Clear -> replaceInputText("", 0)
                            ObfButtonActionEffect.Speak -> playInput()
                            ObfButtonActionEffect.Pause -> pauseSpeech()
                            ObfButtonActionEffect.Resume -> resumeSpeech()
                            ObfButtonActionEffect.Stop -> stopSpeech()
                            ObfButtonActionEffect.ToggleSecondaryLanguage -> toggleSecondarySelection?.invoke()
                            ObfButtonActionEffect.SwapHeldMessage -> toggleThatThought()
                            ObfButtonActionEffect.NativeKeyboard -> {
                                showPhraseSheet = false
                                textFieldFocusRequester.requestFocus()
                                softwareKeyboardController?.show()
                            }
                            ObfButtonActionEffect.Home,
                            ObfButtonActionEffect.Predictions -> Unit
                            is ObfButtonActionEffect.Unsupported -> coroutineScope.launch {
                                snackbarHostState.showSnackbar("Unsupported action")
                            }
                        }
                    }
                    // Everything below the message bar docks under the "+" panel.
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                    // On narrow screens, if keyboard is active, show prediction bar instead of SSML button
                    val isKeyboardVisible = WindowInsets.ime.asPaddingValues().calculateBottomPadding() > 0.dp
                    val keyboardHeight = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
                    LaunchedEffect(keyboardHeight) {
                        if (keyboardHeight >= 200.dp) {
                            typingTrayHeight = keyboardHeight
                            typingTrayPreferences.edit()
                                .putFloat("tray-height-dp", keyboardHeight.value)
                                .apply()
                        }
                    }
                    
                    if (predictionsEnabled && !isWide && isKeyboardVisible && (predictions.words.isNotEmpty() || predictions.letters.isNotEmpty())) {
                         PredictionBar(
                            predictions = predictions,
                            onWordSelected = { word ->
                                val fv = input
                                val updated = completePredictedWord(fv, word)
                                secondaryLanguageRanges = adjustRangesAfterEdit(fv.text, updated.text, secondaryLanguageRanges)
                                input = updated
                                syncDisplayText(updated.text)
                            },
                            onLetterSelected = { letter ->
                                val fv = input
                                val updated = insertPredictedText(fv, letter.toString())
                                secondaryLanguageRanges = adjustRangesAfterEdit(fv.text, updated.text, secondaryLanguageRanges)
                                input = updated
                                syncDisplayText(updated.text)
                            },
                            fontSizeScale = settings.fontSizeScale,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    if (categoryUseCaseState.value == null) {
                        Text(stringResource(R.string.phrase_screen_loading), style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = MaterialTheme.typography.labelSmall.fontSize * settings.fontSizeScale
                        ), color = MaterialTheme.colorScheme.outline)
                    }

                    // Category chips
                    val historyCategoryLabel = stringResource(R.string.category_history)
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

                    // 1-row preview when not in "+" mode; full board lives in the docked panel.
                    if (!showPhraseSheet && compactPhrases.isNotEmpty() && typingTemplateGraph?.rootBoard != null) {
                        CompactPhraseRow(
                            template = typingTemplateGraph!!.rootBoard!!,
                            phrases = compactPhrases,
                            onPhraseActivated = activatePhraseFromTypingScreen,
                            onPhraseLongPress = { phrase ->
                                if (!isHistory) {
                                    editingPhrase = phrase
                                    requestTypingMutation { showEditDialog = true }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        // Add new phrase → Edit Screen (the "+" panel)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            TextButton(
                                enabled = categoriesLoadedOnce && !categoriesLoadFailed,
                                onClick = {
                                focusManager.clearFocus()
                                showPhraseSheet = true
                            }) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.phrase_add_title))
                            }
                        }
                    }

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

                    // Docked "+" panel: covers the content below the message bar
                    // while the bar stays visible (Signal-style attachment picker).
                    if (showPhraseSheet) {
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val renderedHeight = typingTrayHeight.coerceIn(
                                minimumValue = minOf(240.dp, maxHeight),
                                maximumValue = maxHeight,
                            )
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(renderedHeight),
                                color = MaterialTheme.colorScheme.background,
                            ) {
                                Column {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(28.dp)
                                            .draggable(
                                                orientation = Orientation.Vertical,
                                                state = rememberDraggableState { delta ->
                                                    val change = with(density) { (-delta).toDp() }
                                                    typingTrayHeight = (typingTrayHeight + change).coerceAtLeast(240.dp)
                                                },
                                                onDragStopped = {
                                                    typingTrayPreferences.edit()
                                                        .putFloat("tray-height-dp", typingTrayHeight.value)
                                                        .apply()
                                                },
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        HorizontalDivider(modifier = Modifier.width(48.dp))
                                    }
                                    val typingTemplate = typingTemplateGraph?.rootBoard
                                    if (typingTemplate == null) {
                                        RepositoryFailurePanel(onRetry = { typingTemplateRevision++ })
                                    } else TypingScreenTray(
                                    template = typingTemplate,
                                    phrases = visiblePhrases,
                                    categories = categories,
                                    selection = selectedPage,
                                    showHistory = settings.historyVisible && historyItems.isNotEmpty(),
                                    history = historyItems,
                                    onSelectionChanged = { selectedPage = it },
                                    onAddCategory = { requestTypingMutation { showAddCategoryDialog = true } },
                                    onAddPhrase = { requestTypingMutation { showAddPhraseDialog = true } },
                                    onPhraseActivated = activatePhraseFromTypingScreen,
                                    onPhraseLongPress = { phrase ->
                                        if (!isHistory) {
                                            editingPhrase = phrase
                                            requestTypingMutation { showEditDialog = true }
                                        }
                                    },
                                    onHistoryActivated = { historyItem ->
                                        val historyPhrase = Phrase(
                                            id = "history_${historyItem.id ?: historyItem.date ?: historyItem.createdAt ?: 0}",
                                            text = historyItem.saidText.orEmpty(),
                                            createdAt = historyItem.date ?: historyItem.createdAt ?: 0L,
                                            recordingPath = historyItem.audioFilePath,
                                        )
                                        activatePhraseFromTypingScreen(historyPhrase)
                                    },
                                    onAction = onTypingAction,
                                    vocabularyMutationsEnabled = categoriesLoadedOnce &&
                                        !categoriesLoadFailed &&
                                        !typingTemplateLoadFailed &&
                                        state.error == null,
                                    isActionEnabled = { effect ->
                                        when (effect) {
                                            ObfButtonActionEffect.Pause -> speechService.isPlaying() && !isSpeechPaused
                                            ObfButtonActionEffect.Resume -> isSpeechPaused
                                            ObfButtonActionEffect.Stop -> speechService.isPlaying() || isSpeechPaused
                                            ObfButtonActionEffect.ToggleSecondaryLanguage -> toggleSecondarySelection != null
                                            is ObfButtonActionEffect.Unsupported -> false
                                            else -> true
                                        }
                                    },
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                )
                                }
                            }
                        }
                    }
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
                                        input = TextFieldValue("")
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
                                    input = newValue
                                    syncDisplayText(newValue.text)
                                },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).then(boardShowKeyboard),
                                placeholder = { Text(stringResource(R.string.board_legacy_build_sentence)) },
                                trailingIcon = {
                                    if (input.text.isNotEmpty()) {
                                        IconButton(onClick = {
                                            // Speak the entire text
                                            uiScope.launch(Dispatchers.IO) {
                                                val selected = runCatching { voiceUseCase.selected() }.getOrNull()
                                                val inputText = input.text
                                                val playedRecording = runCatching {
                                                    trySpeakUsingRecordedPhrases(
                                                        inputText = inputText,
                                                        phrases = state.items,
                                                        speechService = speechService,
                                                        voice = selected
                                                    )
                                                }.getOrDefault(false)
                                                if (!playedRecording) {
                                                    speechService.speak(inputText, selected)
                                                }
                                            }
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
                                    input = TextFieldValue(text, selection = TextRange(text.length))
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
                                        // Normal button - speak and append text
                                        val textToSpeak = button.vocalization ?: button.label
                                        if (!textToSpeak.isNullOrBlank()) {
                                            // Append to main input text field for consistency
                                            val newText = if (input.text.isEmpty()) textToSpeak else "${input.text} $textToSpeak"
                                            input = TextFieldValue(newText, selection = TextRange(newText.length))
                                            
                                            // Append to Symbol Bar list
                                            selectedObfButtons = selectedObfButtons + (button to null)
                                            
                                            uiScope.launch(Dispatchers.IO) {
                                                val selected = runCatching { voiceUseCase.selected() }.getOrNull()
                                                val playedRecording = runCatching {
                                                    trySpeakUsingRecordedPhrases(
                                                        inputText = textToSpeak,
                                                        phrases = state.items,
                                                        speechService = speechService,
                                                        voice = selected
                                                    )
                                                }.getOrDefault(false)
                                                if (!playedRecording) {
                                                    speechService.speak(textToSpeak, selected)
                                                }
                                            }
                                            syncDisplayText(newText)
                                        }
                                    }
                                },
                                onSpeakSentence = {
                                    if (input.text.isNotBlank()) {
                                        aacLogger.logSentenceSpeak(input.text)
                                        uiScope.launch(Dispatchers.IO) {
                                            val selected = runCatching { voiceUseCase.selected() }.getOrNull()
                                            speechService.speak(input.text, selected)
                                        }
                                    }
                                },
                                onDeleteLast = {
                                    if (selectedObfButtons.isNotEmpty()) {
                                        val last = selectedObfButtons.last().first
                                        val textToRemove = last.vocalization ?: last.label ?: ""
                                        selectedObfButtons = selectedObfButtons.dropLast(1)
                                        
                                        val currentText = input.text.trim()
                                        val newText = if (currentText.endsWith(textToRemove)) {
                                            currentText.removeSuffix(textToRemove).trim()
                                        } else {
                                            currentText.substringBeforeLast(" ").trim()
                                        }
                                        input = TextFieldValue(newText, selection = TextRange(newText.length))
                                        syncDisplayText(newText)
                                    } else if (input.text.isNotEmpty()) {
                                        val newText = input.text.dropLast(1)
                                        input = TextFieldValue(newText, selection = TextRange(newText.length))
                                        syncDisplayText(newText)
                                    }
                                },
                                onClearSentence = {
                                    selectedObfButtons = emptyList()
                                    input = TextFieldValue("")
                                    syncDisplayText("")
                                },
                                modifier = Modifier.weight(1f).fillMaxWidth()
                            )
                        }
                    }
                }
                    }
                }
            }

            if (showSettingsDialog) {
                SettingsScreen(onDismiss = { showSettingsDialog = false }, onSaved = { showSettingsDialog = false }, onBackToWelcome = onBackToWelcome)
            }
            if (showTypingResetUnlock && editingAccessController != null) {
                EditingAccessDialog(
                    controller = editingAccessController,
                    mode = EditingAccessDialogMode.Unlock,
                    onDismiss = { showTypingResetUnlock = false },
                    onSuccess = {
                        showTypingResetUnlock = false
                        showTypingResetConfirmation = true
                    },
                )
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
            if (showTypingResetConfirmation) {
                AlertDialog(
                    onDismissRequest = { showTypingResetConfirmation = false },
                    title = { Text(stringResource(R.string.typing_screen_reset)) },
                    text = { Text(stringResource(R.string.typing_screen_reset_description)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showTypingResetConfirmation = false
                                phraseScreenScope.launch {
                                    runCatching { typingScreenUseCase.reset(settings.gridColumns) }
                                        .onSuccess { typingTemplateRevision++ }
                                        .onFailure {
                                            snackbarHostState.showSnackbar(
                                                typingResetFailedMessage
                                            )
                                        }
                                }
                            },
                        ) {
                            Text(stringResource(R.string.typing_screen_reset))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showTypingResetConfirmation = false }) {
                            Text(stringResource(R.string.common_cancel))
                        }
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
    onFocused: () -> Unit = {},
    highlightRanges: List<TextRange> = emptyList(),
    highlightColor: Color,
    ssmlRanges: List<TextRange> = emptyList(),
    ssmlColor: Color = MaterialTheme.colorScheme.primaryContainer,
    textStyle: TextStyle,
    placeholder: (@Composable () -> Unit)? = null,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    // Inline actions rendered inside the bar, to the right of the text (message-bar style).
    trailingContent: (@Composable () -> Unit)? = null
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
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (value.text.isEmpty()) {
                    placeholder?.invoke()
                }

                val showKeyboardMod = Modifier.showKeyboardOnFocus()
                val inputModifier = if (focusRequester != null) {
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { if (it.isFocused) onFocused() }
                        .then(showKeyboardMod)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (it.isFocused) onFocused() }
                        .then(showKeyboardMod)
                }

                BasicTextField(
                    value = styledValue,
                    onValueChange = {
                        // Pass the plain text back to the parent to keep the logic simple there
                        onValueChange(it.copy(annotatedString = AnnotatedString(it.text)))
                    },
                    textStyle = textStyle,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = inputModifier,
                    minLines = minLines,
                    maxLines = maxLines
                )
            }

            trailingContent?.invoke()
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

private fun toggleSecondaryRange(
    ranges: List<TextRange>,
    selection: TextRange,
    textLength: Int
): List<TextRange> {
    return TextEditingPolicy.toggle(ranges.map { it.toTextSpan() }, selection.toTextSpan(), textLength)
        .map { it.toTextRange() }
}

private fun adjustRangesAfterEdit(oldText: String, newText: String, ranges: List<TextRange>): List<TextRange> {
    return TextEditingPolicy.adjustAfterEdit(oldText, newText, ranges.map { it.toTextSpan() })
        .map { it.toTextRange() }
}

private fun clampRanges(ranges: List<TextRange>, maxLength: Int): List<TextRange> {
    return TextEditingPolicy.merge(ranges.map { it.toTextSpan() }, maxLength).map { it.toTextRange() }
}

private fun mergeRanges(ranges: List<TextRange>): List<TextRange> {
    val maxLength = ranges.maxOfOrNull { maxOf(it.start, it.end) } ?: 0
    return clampRanges(ranges, maxLength)
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

private val PauseTagRegex = Regex("""<(?:pause|break)(?:\s+(?:duration|time)=["']([^"']+)["'])?[^>]*/>""", RegexOption.IGNORE_CASE)

private fun buildLanguageAwareSegments(
    rawText: String,
    markedRanges: List<TextRange>,
    secondaryLanguage: String?
): List<SpeechSegment> {
    if (rawText.isBlank()) return emptyList()
    if (markedRanges.isEmpty()) return SpeechTextProcessor.processText(rawText)

    val normalizedRanges = clampRanges(markedRanges, rawText.length)
    val segments = mutableListOf<SpeechSegment>()
    var cursor = 0

    PauseTagRegex.findAll(rawText).forEach { match ->
        val before = rawText.substring(cursor, match.range.first)
        segments += chunkWithLanguage(before, cursor, normalizedRanges, secondaryLanguage)
        val duration = parseDuration(match.groupValues.getOrNull(1))
        segments += SpeechSegment(text = "", pauseDurationMs = duration)
        cursor = match.range.last + 1
    }

    val tail = rawText.substring(cursor)
    segments += chunkWithLanguage(tail, cursor, normalizedRanges, secondaryLanguage)

    return segments.filter { it.text.isNotBlank() || it.pauseDurationMs > 0 }
}

private fun chunkWithLanguage(
    chunk: String,
    offset: Int,
    ranges: List<TextRange>,
    secondaryLanguage: String?
): List<SpeechSegment> {
    if (chunk.isEmpty()) return emptyList()
    val result = mutableListOf<SpeechSegment>()
    var buffer = StringBuilder()
    var currentState: Boolean? = null
    var rangeIndex = 0
    var activeRange = ranges.getOrNull(rangeIndex)

    fun flush(state: Boolean?) {
        if (buffer.isEmpty()) return
        val textPart = buffer.toString()
        val processed = SpeechTextProcessor.processText(textPart)
        val lang = if (state == true) secondaryLanguage else null
        processed.forEach { segment ->
            val languageOverride = segment.languageTag ?: lang
            result.add(segment.copy(languageTag = languageOverride))
        }
        buffer = StringBuilder()
    }

    chunk.forEachIndexed { index, c ->
        val absoluteIndex = offset + index
        while (activeRange != null && absoluteIndex >= activeRange.end) {
            rangeIndex++
            activeRange = ranges.getOrNull(rangeIndex)
        }
        val isSecondary = activeRange?.let { absoluteIndex >= it.start && absoluteIndex < it.end } ?: false
        if (currentState == null) currentState = isSecondary
        if (isSecondary != currentState) {
            flush(currentState)
            currentState = isSecondary
        }
        buffer.append(c)
    }

    flush(currentState)
    return result
}

private fun parseDuration(durationStr: String?): Long {
    if (durationStr.isNullOrBlank()) return 500L
    val clean = durationStr.trim().lowercase()
    return when {
        clean.endsWith("ms") -> clean.removeSuffix("ms").toDoubleOrNull()?.toLong() ?: 500L
        clean.endsWith("s") -> {
            val seconds = clean.removeSuffix("s").toDoubleOrNull() ?: 0.5
            (seconds * 1000).toLong()
        }
        else -> clean.toDoubleOrNull()?.toLong() ?: 500L
    }
}

private data class RecordedPhraseEntry(
    val phraseId: String,
    val spokenText: String,
    val audioPath: String
)

private sealed interface MixedPlaybackChunk {
    data class Recorded(val entry: RecordedPhraseEntry) : MixedPlaybackChunk
    data class Text(val text: String) : MixedPlaybackChunk
}

private suspend fun trySpeakUsingRecordedPhrases(
    inputText: String,
    phrases: List<Phrase>,
    speechService: io.github.jdreioe.wingmate.domain.SpeechService,
    voice: io.github.jdreioe.wingmate.domain.Voice?
): Boolean {
    val normalizedInput = inputText.trim()
    if (normalizedInput.isEmpty()) return false

    val recordedEntries = phrases.mapNotNull { phrase ->
        val spoken = phraseSpokenText(phrase) ?: return@mapNotNull null
        val path = phrase.recordingPath?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        RecordedPhraseEntry(
            phraseId = phrase.id,
            spokenText = spoken,
            audioPath = path
        )
    }
    if (recordedEntries.isEmpty()) return false

    val plan = buildMixedPlaybackPlan(normalizedInput, recordedEntries) ?: return false
    return playMixedPlan(plan, speechService, voice)
}

private fun phraseSpokenText(phrase: Phrase): String? {
    val spoken = (phrase.name?.ifBlank { null } ?: phrase.text).trim()
    return spoken.ifBlank { null }
}

private fun buildMixedPlaybackPlan(
    inputText: String,
    entries: List<RecordedPhraseEntry>
): List<MixedPlaybackChunk>? {
    if (entries.isEmpty()) return null

    val matchPool = entries
        .distinctBy { it.phraseId }
        .sortedByDescending { it.spokenText.length }

    val chunks = mutableListOf<MixedPlaybackChunk>()
    val textBuffer = StringBuilder()
    var usedRecording = false
    var cursor = 0

    fun flushTextBuffer() {
        if (textBuffer.isEmpty()) return
        chunks += MixedPlaybackChunk.Text(textBuffer.toString())
        textBuffer.clear()
    }

    while (cursor < inputText.length) {
        val match = matchPool.firstOrNull { entry ->
            val candidate = entry.spokenText
            if (candidate.isEmpty()) return@firstOrNull false
            if (cursor + candidate.length > inputText.length) return@firstOrNull false
            if (!inputText.regionMatches(cursor, candidate, 0, candidate.length, ignoreCase = true)) {
                return@firstOrNull false
            }

            isRecordedBoundaryStart(inputText, cursor) &&
                isRecordedBoundaryEnd(inputText, cursor + candidate.length)
        }

        if (match != null) {
            flushTextBuffer()
            chunks += MixedPlaybackChunk.Recorded(match)
            usedRecording = true
            cursor += match.spokenText.length
        } else {
            textBuffer.append(inputText[cursor])
            cursor++
        }
    }

    flushTextBuffer()

    return chunks.takeIf { usedRecording }
}

private suspend fun playMixedPlan(
    chunks: List<MixedPlaybackChunk>,
    speechService: io.github.jdreioe.wingmate.domain.SpeechService,
    voice: io.github.jdreioe.wingmate.domain.Voice?
): Boolean {
    var usedRecording = false

    for (chunk in chunks) {
        when (chunk) {
            is MixedPlaybackChunk.Recorded -> {
                val entry = chunk.entry
                val played = speechService.speakRecordedAudio(
                    audioFilePath = entry.audioPath,
                    textForHistory = entry.spokenText,
                    voice = voice
                )
                if (!played) return false
                usedRecording = true
            }

            is MixedPlaybackChunk.Text -> {
                val text = chunk.text
                if (text.isBlank()) {
                    val pause = pauseForSeparatorChunk(text)
                    if (pause > 0L) delay(pause)
                    continue
                }

                val hasSpeakableContent = text.any { it.isLetterOrDigit() }
                if (!hasSpeakableContent) {
                    val pause = pauseForSeparatorChunk(text)
                    if (pause > 0L) delay(pause)
                    continue
                }

                // Keep punctuation/whitespace around text so transition from recording sounds less abrupt.
                speechService.speak(text, voice, voice?.pitch, voice?.rate)
                waitForSpeechToFinish(speechService)
            }
        }
    }

    return usedRecording
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

private fun pauseForSeparatorChunk(text: String): Long {
    val compact = text.trim()
    if (compact.isEmpty()) {
        return if (text.contains('\n')) 80L else 50L
    }

    return when {
        compact.any { it == '.' || it == '!' || it == '?' } -> 100L
        compact.any { it == ',' || it == ';' || it == ':' } -> 80L
        else -> 50L
    }
}

private suspend fun waitForSpeechToFinish(
    speechService: io.github.jdreioe.wingmate.domain.SpeechService,
    timeoutMs: Long = 15_000L
) {
    var elapsed = 0L
    delay(100)
    while (elapsed < timeoutMs && speechService.isPlaying()) {
        delay(50)
        elapsed += 50
    }
}

private fun isRecordedBoundaryStart(text: String, index: Int): Boolean {
    if (index <= 0) return true
    return isRecordedPhraseSeparator(text[index - 1])
}

private fun isRecordedBoundaryEnd(text: String, indexExclusive: Int): Boolean {
    if (indexExclusive >= text.length) return true
    return isRecordedPhraseSeparator(text[indexExclusive])
}

private fun isRecordedPhraseSeparator(char: Char): Boolean {
    return char.isWhitespace() || when (char) {
        '.', ',', '!', '?', ';', ':', '-', '_', '/', '\\', '(', ')', '[', ']', '{', '}', '"', '\'' -> true
        else -> false
    }
}
