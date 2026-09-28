package io.github.jdreioe.wingmate.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.ui.text.style.TextOverflow
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
import io.github.jdreioe.wingmate.domain.CommunicationAction
import io.github.jdreioe.wingmate.domain.CommunicationPlaybackStatus
import io.github.jdreioe.wingmate.domain.CommunicationSession
import io.github.jdreioe.wingmate.domain.Message
import io.github.jdreioe.wingmate.domain.MessagePart
import io.github.jdreioe.wingmate.domain.MessagePartSource
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.activatePhrase
import io.github.jdreioe.wingmate.domain.fromScreenButton
import io.github.jdreioe.wingmate.domain.fromTextDiff
import io.github.jdreioe.wingmate.domain.isGridPhrase
import io.github.jdreioe.wingmate.domain.phraseSubtree
import io.github.jdreioe.wingmate.domain.toScreenButtons
import io.github.jdreioe.wingmate.domain.PredictionResult
import io.github.jdreioe.wingmate.domain.TextEditingPolicy
import io.github.jdreioe.wingmate.domain.TextPredictionService
import io.github.jdreioe.wingmate.domain.TextSpan
import io.github.jdreioe.wingmate.domain.TtsEngine
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
import androidx.compose.ui.res.stringResource
import org.koin.compose.getKoin
import org.koin.compose.koinInject

import com.hojmoseit.wingmate.R
/** Input surface under the Message bar. They take turns; only one is ever shown. */
private enum class TypingInputSurface { Keyboard, Tray }

internal fun supportsMathMode(ttsEngine: TtsEngine): Boolean =
    ttsEngine == TtsEngine.AZURE_USER_RESOURCE || ttsEngine == TtsEngine.AZURE_MANAGED

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalComposeUiApi::class,
    ExperimentalLayoutApi::class,
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
    // Tray height, per client and outside the Screen graph (#248): a height the
    // user dragged to wins; otherwise the last keyboard height, so switching
    // surfaces doesn't move the Message bar. Both include the navigation bar.
    fun storedHeight(key: String): Dp? =
        typingTrayPreferences.getFloat(key, Float.NaN).takeUnless { it.isNaN() }?.dp
    var draggedTrayHeight by remember { mutableStateOf(storedHeight("tray-height-dp")) }
    var keyboardTrayHeight by remember { mutableStateOf(storedHeight("keyboard-height-dp")) }
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
    val typingScreenUseCase = koinInject<TypingScreenUseCase>()
    val editingAccessController = remember(koin) { koin.getOrNull<EditingAccessController>() }
    val obfParser = koinInject<io.github.jdreioe.wingmate.infrastructure.ObfParser>()

    val releaseBuild = isReleaseBuild()
    val predictionsEnabled = !releaseBuild
    val predictionService = remember(koin, predictionsEnabled) {
        if (predictionsEnabled) koin.getOrNull<TextPredictionService>() else null
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
            // The system keyboard and the Typing Screen tray take turns below the
            // Message bar (#248, #299). The tray is the resting surface.
            var inputSurface by remember { mutableStateOf(TypingInputSurface.Tray) }
            // Returning focus would raise the keyboard, so only do it while typing.
            val refocusInput = remember(textFieldFocusRequester) {
                {
                    if (inputSurface == TypingInputSurface.Keyboard) {
                        textFieldFocusRequester.requestFocus()
                    }
                }
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
            // The keyboard's in-progress word (composition). Gboard needs it back to
            // replace the word with a suggestion or finish a swipe. It is kept only
            // while the text is still what the field reported; any other edit
            // (Phrase, Clear, Swap) drops it.
            var lastFieldValue by remember { mutableStateOf<TextFieldValue?>(null) }
            val displayText = communicationState.activeMessage.displayText
            val input = TextFieldValue(
                text = displayText,
                selection = cursor,
                composition = lastFieldValue?.takeIf { it.text == displayText }?.composition,
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

            LaunchedEffect(saidRepo) {
                try {
                    historyItems = saidRepo.list().filter { it.visibleInHistory }
                        .sortedByDescending { it.date ?: it.createdAt ?: 0L }
                } catch (failure: kotlinx.coroutines.CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    // Preserve visible history if a refresh fails.
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
            
            // input is an immutable projection of the communication session.
            // Restart on text changes instead of capturing its initial value in a flow.
            LaunchedEffect(predictionService, input.text) {
                val service = predictionService ?: return@LaunchedEffect
                if (input.text.isBlank()) {
                    predictions = PredictionResult()
                    return@LaunchedEffect
                }
                delay(250)
                service.predictions(input.text, maxWords = 5, maxLetters = 4)
                    .collect { predictions = it }
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
            LaunchedEffect(inputSurface) {
                AndroidAccessInputBus.restartScan()
            }
            val focusManager = LocalFocusManager.current
            val softwareKeyboardController = LocalSoftwareKeyboardController.current
            // Follow the system keyboard: showing it means typing, and dismissing it
            // (Back or the keyboard's own hide key) brings the tray back. A hardware
            // keyboard never shows one, so the toggle below still sets the surface.
            // Uses where the keyboard is heading, so the switch happens as its
            // animation starts rather than after it ends.
            val isImeVisible = WindowInsets.imeAnimationTarget.getBottom(density) > 0
            var wasImeVisible by remember { mutableStateOf(false) }
            // When switching to the keyboard, the tray stays underneath while the
            // keyboard starts (a few hundred ms after it is requested) and slides up
            // over it, so the Message bar never drops in between. A hardware
            // keyboard never shows one, so this simply ends after a moment.
            var trayLingering by remember { mutableStateOf(false) }
            LaunchedEffect(trayLingering) {
                if (trayLingering) {
                    delay(700)
                    trayLingering = false
                }
            }
            LaunchedEffect(isImeVisible) {
                if (isImeVisible) {
                    if (inputSurface == TypingInputSurface.Tray) trayLingering = true
                    inputSurface = TypingInputSurface.Keyboard
                } else if (wasImeVisible) {
                    inputSurface = TypingInputSurface.Tray
                }
                wasImeVisible = isImeVisible
            }
            val showTray: () -> Unit = {
                inputSurface = TypingInputSurface.Tray
                focusManager.clearFocus()
                softwareKeyboardController?.hide()
            }
            val showKeyboard: () -> Unit = {
                inputSurface = TypingInputSurface.Keyboard
                trayLingering = true
                textFieldFocusRequester.requestFocus()
                softwareKeyboardController?.show()
            }

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    // Hidden while the system keyboard is up to leave room for the
                    // Message; everything in it is back as soon as the keyboard closes.
                    AnimatedVisibility(
                        visible = !isImeVisible,
                        enter = fadeIn(tween(200)),
                        exit = fadeOut(tween(200)),
                    ) { BoxWithConstraints(Modifier.fillMaxWidth()) {
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
                    } }
                },
                // Speak lives in the Message bar and the other speech controls in
                // the tray's Action strip; there is no bottom bar (#243, #299).
            ) { innerPadding ->
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .consumeWindowInsets(innerPadding),
                ) {
                    Row(Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(horizontal = 8.dp),
                        ) {
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
                                state.items.filter {
                                    it.isGridPhrase() && (selectedCategoryId == null || it.parentId == selectedCategoryId)
                                }
                            }
                        }
                    }
                    val compactPhrases by remember(state.items, lastPhraseCategory?.id) {
                        derivedStateOf {
                            val categoryId = lastPhraseCategory?.id
                            state.items.filter { it.isGridPhrase() && (categoryId == null || it.parentId == categoryId) }
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
                    val typingActivationBehavior = typingTemplateGraph
                        ?.boardSet
                        ?.screenSettings
                        ?.activationBehavior
                        ?: BoardActivationBehavior.SpeakOnly
                    val activatePhraseFromTypingScreen: (Phrase) -> Unit = { phrase ->
                        val cursorPos = cursor.start.coerceIn(0, input.text.length)
                        val currentMessage = communicationSession.state.value.activeMessage
                        val activation = currentMessage.activatePhrase(
                            phrase = phrase,
                            cursor = cursorPos,
                            activationBehavior = typingActivationBehavior,
                            speechPolicy = settings.speechPolicy,
                        )
                        if (activation.message != currentMessage) {
                            communicationSession.accept(
                                CommunicationAction.ReplaceMessage(activation.message)
                            )
                            cursor = TextRange((cursorPos + phrase.text.length).coerceIn(0, activation.message.displayText.length))
                            syncDisplayText(activation.message.displayText)
                            featureUsageReporter.reportEvent(
                                FeatureUsageEvents.PHRASE_INSERTED,
                                "source" to if (isHistory) "history" else "typing_screen",
                            )
                        }
                        if (activation.shouldSpeak) {
                            playPhraseFromGrid(phrase)
                        }
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
                            ObfButtonActionEffect.NativeKeyboard -> showKeyboard()
                            ObfButtonActionEffect.Home,
                            ObfButtonActionEffect.Predictions -> Unit
                            is ObfButtonActionEffect.Unsupported -> coroutineScope.launch {
                                snackbarHostState.showSnackbar("Unsupported action")
                            }
                        }
                    }
                    val keyboardHeight = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
                    LaunchedEffect(keyboardHeight) {
                        if (keyboardHeight >= 200.dp) {
                            // Wait for the keyboard animation to settle before storing.
                            delay(300)
                            keyboardTrayHeight = keyboardHeight
                            typingTrayPreferences.edit()
                                .putFloat("keyboard-height-dp", keyboardHeight.value)
                                .apply()
                        }
                    }

                    if (categoryUseCaseState.value == null) {
                        Text(stringResource(R.string.phrase_screen_loading), style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = MaterialTheme.typography.labelSmall.fontSize * settings.fontSizeScale
                        ), color = MaterialTheme.colorScheme.outline)
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

                    // Category menu (move, delete). Opened by tapping the selected
                    // Category chip in the tray, behind editing access.
                    var categoryMenu by remember { mutableStateOf<CategoryItem?>(null) }
                    categoryMenu?.let { menuCategory ->
                        val index = categories.indexOfFirst { it.id == menuCategory.id }
                        val moveCategory: (Int) -> Unit = { target ->
                            val uc = categoryUseCaseState.value
                            if (index >= 0 && target in categories.indices && uc != null) {
                                coroutineScope.launch(Dispatchers.IO) {
                                    runCatching {
                                        uc.move(index, target)
                                        uc.list()
                                    }.onSuccess { updated ->
                                        coroutineScope.launch { categories = updated }
                                    }.onFailure {
                                        coroutineScope.launch { categoriesLoadFailed = true }
                                    }
                                }
                            }
                        }
                        val menuTextStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                        )
                        ModalBottomSheet(onDismissRequest = { categoryMenu = null }) {
                            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.category_move_left), style = menuTextStyle) },
                                    enabled = index > 0,
                                    onClick = {
                                        categoryMenu = null
                                        moveCategory(index - 1)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.category_move_right), style = menuTextStyle) },
                                    enabled = index in 0 until categories.lastIndex,
                                    onClick = {
                                        categoryMenu = null
                                        moveCategory(index + 1)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.category_delete_with_phrases), style = menuTextStyle) },
                                    onClick = {
                                        categoryMenu = null
                                        confirmDeleteCategory = menuCategory
                                    },
                                )
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
                        AddPhraseDialog(
                            onDismiss = { showEditDialog = false; editingPhrase = null },
                            categories = categories,
                            initialPhrase = editingPhrase,
                            onSave = { p -> bloc.dispatch(PhraseEvent.Edit(p)); showEditDialog = false; editingPhrase = null },
                            onDelete = { id -> deleteWithUndo(id); showEditDialog = false; editingPhrase = null },
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

                    // Free space above the Message bar; the bar and its input
                    // surface stay anchored to the bottom (#299, layout 1).
                    Spacer(modifier = Modifier.weight(1f))

                    val speechActive = isSpeechPaused ||
                        communicationState.currentSpeechRequestId != null ||
                        communicationState.queuedSpeechCount > 0
                    val speechPlaying = communicationState.currentSpeechRequestId != null && !isSpeechPaused
                    val typingActionEnabled: (ObfButtonActionEffect) -> Boolean = { effect ->
                        when (effect) {
                            ObfButtonActionEffect.Pause -> speechPlaying
                            ObfButtonActionEffect.Resume -> isSpeechPaused
                            ObfButtonActionEffect.Stop -> speechActive
                            ObfButtonActionEffect.ToggleSecondaryLanguage -> toggleSecondarySelection != null
                            is ObfButtonActionEffect.Unsupported -> false
                            else -> true
                        }
                    }
                    val typingTemplate = typingTemplateGraph?.rootBoard
                    // Landscape with the keyboard up leaves room for one row above the
                    // Message bar: the Action strip (with Swap when a Message is held)
                    // replaces the Held and Phrase rows, so the Message stays visible.
                    val landscape = LocalConfiguration.current.orientation ==
                        android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    val oneRowWhileTyping = isImeVisible && landscape

                    // The app bar (with the fullscreen display) hides while typing, so the
                    // typing Action row starts with its own fullscreen toggle.
                    val fullscreenLabel = stringResource(R.string.phrase_screen_toggle_fullscreen_cd)
                    val fullscreenButton: @Composable () -> Unit = {
                        FilledTonalIconButton(
                            onClick = toggleFullscreen,
                            modifier = Modifier.fillMaxHeight().aspectRatio(1f),
                        ) {
                            Icon(
                                imageVector = if (showFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                                contentDescription = fullscreenLabel,
                            )
                        }
                    }

                    // Portrait while typing has free space above the Held row; the
                    // Action strip (SSML, Language, Hold) fills it, as in the tray.
                    AnimatedVisibility(
                        visible = isImeVisible && !landscape && typingTemplate != null,
                        enter = expandVertically(tween(250), expandFrom = Alignment.Bottom) + fadeIn(tween(250)),
                        exit = shrinkVertically(tween(250), shrinkTowards = Alignment.Bottom) + fadeOut(tween(250)),
                    ) {
                        if (typingTemplate != null) {
                            TypingActionRow(
                                template = typingTemplate,
                                onAction = onTypingAction,
                                isActionEnabled = typingActionEnabled,
                                modifier = Modifier.fillMaxWidth(),
                                leading = fullscreenButton,
                            )
                        }
                    }

                    communicationState.heldMessage?.takeUnless { oneRowWhileTyping }?.let { held ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(12.dp),
                                )
                                .padding(start = 12.dp, end = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Bookmark,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
                            Text(
                                text = stringResource(R.string.typing_held_message, held.displayText),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                                ),
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = toggleThatThought,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Icon(Icons.Filled.SwapHoriz, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.typing_held_message_swap))
                            }
                        }
                    }

                    if (inputSurface == TypingInputSurface.Keyboard) {
                        // Gboard shows its own suggestions, so Wingmate's only appear
                        // for a hardware keyboard.
                        if (!isImeVisible && !trayLingering && predictionsEnabled &&
                            (predictions.words.isNotEmpty() || predictions.letters.isNotEmpty())
                        ) {
                            PredictionBar(
                                predictions = predictions,
                                onWordSelected = { word ->
                                    val updated = completePredictedWord(input, word)
                                    replaceInputText(updated.text, updated.selection.start)
                                },
                                onLetterSelected = { letter ->
                                    val updated = insertPredictedText(input, letter.toString())
                                    replaceInputText(updated.text, updated.selection.start)
                                },
                                fontSizeScale = settings.fontSizeScale,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    // Slides in and out with the keyboard instead of popping, so the rows
                    // above the Message bar move smoothly.
                    AnimatedVisibility(
                        visible = inputSurface == TypingInputSurface.Keyboard && !oneRowWhileTyping &&
                            compactPhrases.isNotEmpty() && typingTemplate != null,
                        enter = expandVertically(tween(250), expandFrom = Alignment.Bottom) + fadeIn(tween(250)),
                        exit = shrinkVertically(tween(250), shrinkTowards = Alignment.Bottom) + fadeOut(tween(250)),
                    ) {
                        if (typingTemplate != null) {
                            CompactPhraseRow(
                                template = typingTemplate,
                                phrases = compactPhrases,
                                onPhraseActivated = activatePhraseFromTypingScreen,
                                onPhraseLongPress = { phrase ->
                                    editingPhrase = phrase
                                    requestTypingMutation { showEditDialog = true }
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                        }
                    }

                    if (oneRowWhileTyping && typingTemplate != null) {
                        val held = communicationState.heldMessage
                        TypingActionRow(
                            template = typingTemplate,
                            onAction = onTypingAction,
                            isActionEnabled = typingActionEnabled,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            leading = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    fullscreenButton()
                                    if (held != null) {
                                    val heldLabel = stringResource(R.string.typing_held_message, held.displayText)
                                    FilledTonalButton(
                                        onClick = toggleThatThought,
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .semantics { contentDescription = heldLabel },
                                    ) {
                                        Icon(Icons.Filled.SwapHoriz, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.typing_held_message_swap))
                                    }
                                    }
                                }
                            },
                        )
                    }

                    // Message bar: the input-surface toggle, the Message, and the speech
                    // control as the largest target on the thumb side. It speaks, turns
                    // into Pause while speech plays and Resume while paused; Stop joins
                    // it while speech is active. All transport lives here (#299).
                    val barButtonSize = (56.dp * settings.playbackIconScale).coerceIn(48.dp, 72.dp)
                    val speakButtonSize = (64.dp * settings.playbackIconScale).coerceIn(56.dp, 88.dp)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        val trayOpen = inputSurface == TypingInputSurface.Tray
                        FilledTonalIconButton(
                            onClick = if (trayOpen) showKeyboard else showTray,
                            modifier = Modifier.size(barButtonSize),
                        ) {
                            Icon(
                                imageVector = if (trayOpen) Icons.Filled.Keyboard else Icons.Filled.Add,
                                contentDescription = stringResource(
                                    if (trayOpen) R.string.typing_show_keyboard else R.string.typing_show_screen
                                ),
                            )
                        }
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
                                lastFieldValue = newValue
                                cursor = newValue.selection
                                syncDisplayText(newValue.text)
                            },
                            modifier = Modifier
                                .weight(1f)
                                // Focusing the Message means typing, with or without an
                                // on-screen keyboard (a hardware keyboard never shows one).
                                .onFocusChanged { focus ->
                                    if (focus.hasFocus && inputSurface == TypingInputSurface.Tray) {
                                        trayLingering = true
                                        inputSurface = TypingInputSurface.Keyboard
                                    }
                                }
                                // Grows with the Message up to a cap, then scrolls.
                                .heightIn(
                                    min = (56.dp * settings.inputFieldScale),
                                    max = (160.dp * settings.inputFieldScale),
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
                            minLines = 1,
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
                        )
                        if (speechActive) {
                            FilledTonalIconButton(
                                onClick = stopSpeech,
                                modifier = Modifier.size(barButtonSize),
                            ) {
                                Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.playback_stop))
                            }
                        }
                        FilledIconButton(
                            onClick = when {
                                isSpeechPaused -> resumeSpeech
                                speechPlaying -> pauseSpeech
                                else -> playInput
                            },
                            modifier = Modifier.size(speakButtonSize),
                        ) {
                            Icon(
                                imageVector = when {
                                    speechPlaying -> Icons.Filled.Pause
                                    else -> Icons.Filled.PlayArrow
                                },
                                contentDescription = stringResource(
                                    when {
                                        isSpeechPaused -> R.string.playback_resume
                                        speechPlaying -> R.string.playback_pause
                                        else -> R.string.playback_play
                                    }
                                ),
                                modifier = Modifier.size(speakButtonSize / 2),
                            )
                        }
                    }

                    // Space under the Message bar. The app root already pads for the
                    // keyboard (safeDrawingPadding), so this only adds what the tray
                    // needs beyond the keyboard's current height. The tray opens at the
                    // keyboard's last height and stays drawn beneath the keyboard while
                    // it slides in or out, so the Message bar never jumps.
                    val navigationBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    // Limits come from the screen, which stays put while the keyboard
                    // resizes the content area.
                    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
                    val maxTrayHeight = screenHeight * 0.6f
                    val preferredTrayHeight = draggedTrayHeight ?: keyboardTrayHeight ?: (screenHeight * 0.4f)
                    val trayHeight = (preferredTrayHeight - navigationBarHeight)
                        .coerceIn(minOf(200.dp, maxTrayHeight), maxTrayHeight)
                    val trayDrawn = inputSurface == TypingInputSurface.Tray || trayLingering
                    val imeInsets = WindowInsets.ime
                    val imeTargetInsets = WindowInsets.imeAnimationTarget
                    val navigationInsets = WindowInsets.navigationBars
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // Measured in the layout pass, like the root's keyboard padding,
                            // so both follow the same keyboard frame.
                            .layout { measurable, constraints ->
                                val navigation = navigationInsets.getBottom(this)
                                val ime = (imeInsets.getBottom(this) - navigation).coerceAtLeast(0)
                                val imeTarget = (imeTargetInsets.getBottom(this) - navigation).coerceAtLeast(0)
                                val height = if (trayDrawn || ime != imeTarget) {
                                    (trayHeight.roundToPx() - ime).coerceAtLeast(0)
                                } else {
                                    0
                                }
                                val placeable = measurable.measure(
                                    constraints.copy(minHeight = height, maxHeight = height)
                                )
                                layout(placeable.width, height) { placeable.place(0, 0) }
                            },
                    ) {
                    if (trayDrawn) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                // Full height even while the keyboard still covers part of it.
                                .wrapContentHeight(Alignment.Top, unbounded = true)
                                .height(trayHeight),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(20.dp)
                                    .draggable(
                                        orientation = Orientation.Vertical,
                                        state = rememberDraggableState { delta ->
                                            val change = with(density) { (-delta).toDp() }
                                            draggedTrayHeight = (trayHeight + navigationBarHeight + change)
                                                .coerceIn(minOf(200.dp, maxTrayHeight), maxTrayHeight + navigationBarHeight)
                                        },
                                        onDragStopped = {
                                            draggedTrayHeight?.let { height ->
                                                typingTrayPreferences.edit()
                                                    .putFloat("tray-height-dp", height.value)
                                                    .apply()
                                            }
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                HorizontalDivider(modifier = Modifier.width(48.dp))
                            }
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
                                onOpenCategoryMenu = { category ->
                                    requestTypingMutation { categoryMenu = category }
                                },
                                onAddCategory = { requestTypingMutation { showAddCategoryDialog = true } },
                                onAddPhrase = { requestTypingMutation { showAddPhraseDialog = true } },
                                onPhraseActivated = activatePhraseFromTypingScreen,
                                onEditPhrase = { phrase ->
                                    editingPhrase = phrase
                                    requestTypingMutation { showEditDialog = true }
                                },
                                onDeletePhrase = { phrase -> requestTypingMutation { deleteWithUndo(phrase.id) } },
                                // Dropping onto a Phrase takes its place in the repository order.
                                onMovePhrase = { moved, target ->
                                    val from = state.items.indexOfFirst { it.id == moved.id }
                                    val to = state.items.indexOfFirst { it.id == target.id }
                                    if (from >= 0 && to >= 0 && from != to) {
                                        requestTypingMutation { bloc.dispatch(PhraseEvent.Move(from, to)) }
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
                                isActionEnabled = typingActionEnabled,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
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
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.text.isEmpty()) {
                placeholder?.invoke()
            }
            val inputModifier = Modifier
                .fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .showKeyboardOnFocus()
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
