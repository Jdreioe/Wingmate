package io.github.jdreioe.wingmate.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.application.BoardSetUseCase
import io.github.jdreioe.wingmate.application.pageSwipeIsActive
import io.github.jdreioe.wingmate.application.RemoteScreenActions
import io.github.jdreioe.wingmate.application.EditingAccessController
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.domain.CommunicationAction
import io.github.jdreioe.wingmate.domain.CommunicationPlaybackStatus
import io.github.jdreioe.wingmate.domain.CommunicationSession
import io.github.jdreioe.wingmate.domain.CommunicationSessionSnapshot
import io.github.jdreioe.wingmate.domain.FileStorage
import io.github.jdreioe.wingmate.domain.Message
import io.github.jdreioe.wingmate.domain.SoundPlayer
import io.github.jdreioe.wingmate.domain.TextPredictionService
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.fromTextDiff
import io.github.jdreioe.wingmate.domain.legacyScreenMessage
import io.github.jdreioe.wingmate.domain.toScreenButtons
import io.github.jdreioe.wingmate.domain.obf.BoardSettingsOverrides
import io.github.jdreioe.wingmate.domain.obf.CellTapResult
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfButtonShape
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import io.github.jdreioe.wingmate.domain.obf.ObfMediaUrlLoader
import io.github.jdreioe.wingmate.domain.obf.ScreenKind
import io.github.jdreioe.wingmate.domain.obf.availableFieldSpansAt
import io.github.jdreioe.wingmate.domain.obf.backgroundImage
import io.github.jdreioe.wingmate.domain.obf.clearDraftCell
import io.github.jdreioe.wingmate.domain.obf.fieldAnchorAt
import io.github.jdreioe.wingmate.domain.obf.moveDraftField
import io.github.jdreioe.wingmate.domain.obf.normalizedOrder
import io.github.jdreioe.wingmate.domain.obf.orderedPredictionButtonIds
import io.github.jdreioe.wingmate.domain.obf.pageSettingsOverrides
import io.github.jdreioe.wingmate.domain.obf.renameDraftBoardSet
import io.github.jdreioe.wingmate.domain.obf.resizeDraftBoard
import io.github.jdreioe.wingmate.domain.obf.resizeDraftField
import io.github.jdreioe.wingmate.domain.obf.resolveBoardSettings
import io.github.jdreioe.wingmate.domain.obf.resolveCellTap
import io.github.jdreioe.wingmate.domain.obf.updateDraftCell
import io.github.jdreioe.wingmate.domain.obf.withBackgroundImage
import io.github.jdreioe.wingmate.domain.obf.withHomeFieldsBottomLeft
import io.github.jdreioe.wingmate.domain.obf.withPageSettingsOverrides
import io.github.jdreioe.wingmate.domain.obf.wordType
import io.github.jdreioe.wingmate.platform.ShareService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.getKoin
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BoardSetWorkspaceRoot(
    boardSetId: String,
    initialMode: BoardWorkspaceMode,
    onSwitchToKeyboard: () -> Unit,
    onExitToLibrary: () -> Unit,
    onOpenSettings: () -> Unit = {},
    hideStatusBar: Boolean = true,
) {
    val useCase = koinInject<BoardSetUseCase>()
    val remoteActions = koinInject<RemoteScreenActions>()
    val communicationSession = koinInject<CommunicationSession>()
    val communicationState by communicationSession.state.collectAsStateWithLifecycle()
    val voiceUseCase = koinInject<VoiceUseCase>()
    val soundPlayer = koinInject<SoundPlayer>()
    val fileStorage = koinInject<FileStorage>()
    val settings by rememberReactiveSettings()
    val koin = getKoin()
    val predictionService = remember(koin) { koin.getOrNull<TextPredictionService>() }
    val mediaUrlLoader = remember(koin) { koin.getOrNull<ObfMediaUrlLoader>() }
    val shareService = remember(koin) { koin.getOrNull<ShareService>() }
    val editingAccessController = remember(koin) { koin.getOrNull<EditingAccessController>() }
    val scope = rememberCoroutineScope()
    val workspaceFactory = remember(boardSetId) {
        viewModelFactory {
            initializer { BoardWorkspaceViewModel(createSavedStateHandle()) }
        }
    }
    val workspaceViewModel: BoardWorkspaceViewModel = viewModel(
        key = "board-workspace-$boardSetId",
        factory = workspaceFactory,
    )
    val workspace by workspaceViewModel.state.collectAsStateWithLifecycle()
    val selectedVoice by produceState<Voice?>(initialValue = null, key1 = voiceUseCase) {
        value = runCatching { voiceUseCase.selected() }.getOrNull()
    }
    val savedGraph = workspace.savedGraph
    val editSession = workspace.editSession
    val mode = workspace.mode
    val showFinishDialog = workspace.showFinishDialog
    val isExporting = workspace.isExporting
    val legacySentenceButtons = remember(workspaceViewModel) {
        workspaceViewModel.consumeLegacySentenceButtons()
    }
    // #120: time-bounded selection highlight.
    val selectionHighlight = key(boardSetId) { rememberSelectionHighlightState(settings.selectionHighlightMillis) }
    var nativeKeyboardDraft by remember(boardSetId) { mutableStateOf<String?>(null) }
    var showAddBoardDialog by remember { mutableStateOf(false) }
    var editingCell by remember { mutableStateOf<WorkspaceCellTarget?>(null) }
    var selectedField by remember(boardSetId) { mutableStateOf<Pair<Int, Int>?>(null) }
    var showResizeBoardDialog by remember { mutableStateOf(false) }
    var showDeleteBoardDialog by remember { mutableStateOf(false) }
    var showRenameScreenDialog by remember { mutableStateOf(false) }
    var settingsTarget by remember(boardSetId) { mutableStateOf<BoardSettingsTarget?>(null) }
    val hiddenButtonsSession = remember(boardSetId) { HiddenButtonsSession() }
    val showHiddenButtons = hiddenButtonsSession.revealed
    val showEditingAccessDialog = workspace.showEditingAccessDialog
    var showExportDialog by remember(boardSetId) { mutableStateOf(false) }
    val unlockToEditMessage = stringResource(R.string.board_workspace_unlock_to_edit)
    val loadErrorMessage = stringResource(R.string.board_workspace_load_error)
    val saveErrorMessage = stringResource(R.string.board_workspace_save_error)
    val exportErrorMessage = stringResource(R.string.board_workspace_export_error)
    val backgroundImageFailedMessage = stringResource(R.string.board_settings_background_image_failed)
    val exportCancelledMessage = stringResource(R.string.board_workspace_export_cancelled)
    val exportedTemplate = stringResource(R.string.board_workspace_exported, "%NAME%")
    val resources = LocalContext.current.resources
    // Placeholder substituted in click handler (stringResource formatting is composition-only).
    val unsupportedActionTemplate = stringResource(R.string.board_workspace_unsupported_action, "%ACTION%")

    val englishLanguageName = stringResource(R.string.board_dialog_language_english)
    val danishLanguageName = stringResource(R.string.board_dialog_language_danish)
    val primaryLanguageName = languageName(
        settings.primaryLanguage,
        englishLanguageName,
        danishLanguageName,
        stringResource(R.string.language_primary)
    )
    val availableFieldLanguages = listOf(
        FieldLanguageOption(
            settings.primaryLanguage,
            stringResource(R.string.board_dialog_language_primary_value, primaryLanguageName)
        )
    ).plus(
        settings.secondaryLanguage
            .takeIf { it.isNotBlank() && it != settings.primaryLanguage }
            ?.let { secondaryLanguage ->
                val secondaryLanguageName = languageName(
                    secondaryLanguage,
                    englishLanguageName,
                    danishLanguageName,
                    stringResource(R.string.language_secondary)
                )
                FieldLanguageOption(
                    secondaryLanguage,
                    stringResource(R.string.board_dialog_language_secondary_value, secondaryLanguageName)
                )
            }
            ?.let(::listOf)
            .orEmpty()
    ).distinctBy { it.tag }

    LaunchedEffect(boardSetId, workspace.loadRequestId) {
        if (workspaceViewModel.state.value.savedGraph != null) return@LaunchedEffect
        try {
            val graph = withContext(Dispatchers.Default) {
                useCase.loadBoardSetGraph(boardSetId)?.takeIf { it.boardSet.kind == ScreenKind.User }
            }?.withHomeFieldsBottomLeft()
            val requiresUnlock = graph != null &&
                initialMode == BoardWorkspaceMode.Edit &&
                !graph.boardSet.isLocked &&
                editingAccessController?.requiresUnlock() == true
            workspaceViewModel.onAction(
                BoardWorkspaceAction.Initialize(
                    graph = graph,
                    startInEditMode = initialMode == BoardWorkspaceMode.Edit && !requiresUnlock,
                )
            )
            if (requiresUnlock) {
                workspaceViewModel.onAction(BoardWorkspaceAction.EditingAccessRequired)
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            workspaceViewModel.onAction(BoardWorkspaceAction.LoadFailed(loadErrorMessage))
        }
    }

    // A remote sync replaces the Screen in Run mode. The Message lives in CommunicationSession,
    // so it is kept; edit mode is never interrupted (RefreshGraph ignores it).
    LaunchedEffect(boardSetId, remoteActions) {
        var first = true
        remoteActions.syncVersion.collect {
            if (first) {
                first = false
            } else if (workspaceViewModel.state.value.mode == BoardWorkspaceMode.Run) {
                val refreshed = runCatching {
                    withContext(Dispatchers.Default) {
                        useCase.loadBoardSetGraph(boardSetId)
                            ?.takeIf { it.boardSet.kind == ScreenKind.User }
                            ?.withHomeFieldsBottomLeft()
                    }
                }.getOrNull()
                if (refreshed != null) {
                    workspaceViewModel.onAction(BoardWorkspaceAction.RefreshGraph(refreshed))
                }
            }
        }
    }

    LaunchedEffect(workspaceViewModel, onExitToLibrary) {
        workspaceViewModel.events.collect { event ->
            when (event) {
                BoardWorkspaceEvent.NavigateToLibrary -> {
                    hiddenButtonsSession.reset()
                    onExitToLibrary()
                }
            }
        }
    }

    val activeGraph = editSession?.draft ?: savedGraph
    val activeBoard = activeGraph?.boardsById?.get(workspace.selectedBoardId)
    // #103: missing or unreadable media leaves the plain background color.
    val activeBackgroundImage = activeBoard?.backgroundImage?.let { background ->
        activeBoard.images.firstOrNull { it.id == background.imageId }
    }
    val backgroundBitmap by produceState<ImageBitmap?>(null, activeBackgroundImage) {
        value = activeBackgroundImage?.let { image ->
            withContext(Dispatchers.Default) {
                loadObfImageBytes(image, fileStorage, mediaUrlLoader)?.decodeSampledBitmap(2048)?.asImageBitmap()
            }
        }
    }
    LaunchedEffect(communicationState.isInitialized, activeGraph?.boardSet?.id, legacySentenceButtons) {
        val graph = activeGraph ?: return@LaunchedEffect
        if (!communicationState.isInitialized || legacySentenceButtons.isEmpty()) return@LaunchedEffect
        communicationSession.accept(
            CommunicationAction.ImportIfEmpty(
                CommunicationSessionSnapshot(
                    activeMessage = legacyScreenMessage(
                        screenId = graph.boardSet.id,
                        graph = graph,
                        buttons = legacySentenceButtons,
                        primaryLanguage = settings.primaryLanguage,
                    )
                )
            )
        )
    }
    val activeMessage = communicationState.activeMessage
    val selectedButtonModels = remember(activeMessage, activeGraph) {
        activeGraph?.let(activeMessage::toScreenButtons).orEmpty()
    }
    val selectedButtons: List<Pair<ObfButton, ImageBitmap?>> = remember(selectedButtonModels) {
        selectedButtonModels.map { it to null }
    }
    val resolvedBoardSettings = remember(
        activeGraph?.boardSet?.screenSettings,
        activeBoard?.extensions,
        settings.showLabels,
        settings.showSymbols,
        settings.labelAtTop,
        settings.boardShowMessageBar,
        settings.boardShowSpeakButton,
        settings.boardMessageBarEditable,
        settings.boardActivationBehavior,
        settings.boardReturnBehavior
    ) {
        resolveBoardSettings(
            appShowLabels = settings.showLabels,
            appShowSymbols = settings.showSymbols,
            appLabelAtTop = settings.labelAtTop,
            appShowMessageBar = settings.boardShowMessageBar,
            appShowSpeakButton = settings.boardShowSpeakButton,
            appMessageBarEditable = settings.boardMessageBarEditable,
            appActivationBehavior = settings.boardActivationBehavior,
            appReturnBehavior = settings.boardReturnBehavior,
            screen = activeGraph?.boardSet?.screenSettings ?: BoardSettingsOverrides(),
            page = activeBoard?.pageSettingsOverrides() ?: BoardSettingsOverrides()
        )
    }
    val messageText = activeMessage.displayText
    val predictionButtonIds = remember(activeBoard?.id, activeBoard?.grid, activeBoard?.buttons, showHiddenButtons) {
        orderedPredictionButtonIds(activeBoard, showHiddenButtons)
    }
    var predictionsById by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(messageText, predictionButtonIds, predictionService) {
        predictionsById = emptyMap()
        val service = predictionService ?: return@LaunchedEffect
        if (predictionButtonIds.isEmpty()) return@LaunchedEffect
        service.predictions(messageText, maxWords = predictionButtonIds.size, maxLetters = 0)
            .collect { result ->
                predictionsById = predictionButtonIds.withIndex().mapNotNull { (index, id) ->
                    result.words.getOrNull(index)?.let { id to it }
                }.toMap()
            }
    }

    fun enterEditing() {
        val graph = savedGraph ?: return
        if (graph.boardSet.isLocked) {
            workspaceViewModel.onAction(BoardWorkspaceAction.StatusChanged(unlockToEditMessage))
            return
        }
        selectedField = null
        workspaceViewModel.onAction(BoardWorkspaceAction.StartEditing)
    }

    fun startEditing() {
        scope.launch {
            if (editingAccessController?.requiresUnlock() == true) {
                workspaceViewModel.onAction(BoardWorkspaceAction.EditingAccessRequired)
            } else {
                enterEditing()
            }
        }
    }

    fun requestFinishEditing() {
        selectedField = null
        workspaceViewModel.onAction(BoardWorkspaceAction.FinishEditingClicked)
    }

    fun navigateBack() = workspaceViewModel.onAction(BoardWorkspaceAction.BackClicked)

    // The top bar's ← always leaves the Screen; Pages have their own Back in the Message bar.
    fun leave() = workspaceViewModel.onAction(BoardWorkspaceAction.LeaveClicked)

    fun runExport(block: suspend () -> String) {
        scope.launch {
            workspaceViewModel.onAction(BoardWorkspaceAction.ExportStarted)
            var resultMessage = exportErrorMessage
            try {
                resultMessage = block()
            } finally {
                workspaceViewModel.onAction(BoardWorkspaceAction.ExportFinished(resultMessage))
            }
        }
    }

    if (showExportDialog) {
        ExportBoardSetFormatDialog(
            isExporting = isExporting,
            onDismiss = { showExportDialog = false },
            onExportObz = {
                showExportDialog = false
                runExport {
                    exportBoardSetAsObz(
                        graph = activeGraph,
                        useCase = useCase,
                        shareService = shareService,
                        exportedTemplate = exportedTemplate,
                        exportCancelledMessage = exportCancelledMessage,
                        exportErrorMessage = exportErrorMessage,
                    )
                }
            },
            onExportGridset = {
                showExportDialog = false
                runExport {
                    exportBoardSetAsGridset(
                        graph = activeGraph,
                        useCase = useCase,
                        settings = settings,
                        mediaUrlLoader = mediaUrlLoader,
                        shareService = shareService,
                        exportedTemplate = exportedTemplate,
                        exportCancelledMessage = exportCancelledMessage,
                        exportErrorMessage = exportErrorMessage,
                    )
                }
            },
        )
    }

    nativeKeyboardDraft?.let { currentDraft ->
        NativeKeyboardDraftDialog(
            draft = currentDraft,
            onDraftChange = { nativeKeyboardDraft = it },
            onDismiss = { nativeKeyboardDraft = null },
            onConfirm = {
                communicationSession.accept(
                    Message.fromTextDiff(
                        currentText = communicationSession.state.value.activeMessage.displayText,
                        newText = currentDraft,
                    )
                )
                nativeKeyboardDraft = null
            },
        )
    }

    val openSettingsTarget = settingsTarget
    if (
        openSettingsTarget != null &&
        mode == BoardWorkspaceMode.Edit &&
        activeGraph != null &&
        activeBoard != null
    ) {
        BoardSettingsScreen(
            target = openSettingsTarget,
            initialName = if (openSettingsTarget == BoardSettingsTarget.Screen) {
                activeGraph.boardSet.name
            } else {
                activeBoard.name.orEmpty()
            },
            initialBackgroundColor = activeBoard.backgroundColor,
            initialBackgroundImage = activeBoard.backgroundImage,
            screenSettings = activeGraph.boardSet.screenSettings,
            pageSettings = activeBoard.pageSettingsOverrides(),
            appShowLabels = settings.showLabels,
            appShowSymbols = settings.showSymbols,
            appLabelAtTop = settings.labelAtTop,
            appShowMessageBar = settings.boardShowMessageBar,
            appShowSpeakButton = settings.boardShowSpeakButton,
            appMessageBarEditable = settings.boardMessageBarEditable,
            appActivationBehavior = settings.boardActivationBehavior,
            appReturnBehavior = settings.boardReturnBehavior,
            onCommit = { name, updatedSettings, updatedBackgroundColor, backgroundEdit ->
                val session = editSession ?: return@BoardSettingsScreen
                if (openSettingsTarget == BoardSettingsTarget.Screen) {
                    workspaceViewModel.onAction(
                        BoardWorkspaceAction.ApplyEdit(
                            session.draft.copy(
                                boardSet = session.draft.boardSet.copy(name = name, screenSettings = updatedSettings)
                            )
                        )
                    )
                    return@BoardSettingsScreen
                }
                val pageId = activeBoard.id
                scope.launch {
                    // A picked image is copied into app storage first; if that fails the other changes still apply.
                    val picked = (backgroundEdit as? BackgroundImageEdit.Picked)?.let { edit ->
                        storeBackgroundImage(session.draft.boardSet.id, edit.source, fileStorage, mediaUrlLoader)
                    }
                    if (backgroundEdit is BackgroundImageEdit.Picked && picked == null) {
                        workspaceViewModel.onAction(BoardWorkspaceAction.StatusChanged(backgroundImageFailedMessage))
                    }
                    val draft = session.draft
                    workspaceViewModel.onAction(
                        BoardWorkspaceAction.ApplyEdit(
                            draft.copy(
                                boards = draft.boards.map { board ->
                                    if (board.id != pageId) return@map board
                                    val edited = board.copy(name = name, backgroundColor = updatedBackgroundColor)
                                        .withPageSettingsOverrides(updatedSettings)
                                    val currentImage = board.backgroundImage?.let { bg ->
                                        board.images.firstOrNull { it.id == bg.imageId }
                                    }
                                    when (backgroundEdit) {
                                        BackgroundImageEdit.None -> edited.withBackgroundImage(null)
                                        is BackgroundImageEdit.Current -> edited.withBackgroundImage(currentImage, backgroundEdit.fit)
                                        is BackgroundImageEdit.Picked ->
                                            if (picked != null) edited.withBackgroundImage(picked, backgroundEdit.fit) else edited
                                    }
                                }
                            )
                        )
                    )
                }
            },
            onBack = { settingsTarget = null }
        )
        return
    }

    BackHandler(enabled = true, onBack = ::navigateBack)
    PlatformBackgroundEffect { editingAccessController?.lock() }
    HideStatusBarEffect(hidden = hideStatusBar)
    // The selected Button while editing, if any; Back deselects it before leaving.
    val selectedButton = if (mode == BoardWorkspaceMode.Edit) {
        selectedField?.let { (row, column) ->
            activeBoard?.grid?.normalizedOrder()?.getOrNull(row)?.getOrNull(column)
                ?.let { id -> activeBoard.buttons.firstOrNull { it.id == id } }
        }
    } else null
    BackHandler(enabled = selectedButton != null) { selectedField = null }

    val messagePartItems = remember(activeMessage, activeGraph, settings.primaryLanguage) {
        buildScreensMessagePartItems(activeMessage, activeGraph, settings.primaryLanguage)
    }
    val loadChipImage: suspend (ObfImage) -> ImageBitmap? = remember(fileStorage, mediaUrlLoader) {
        { image ->
            withContext(Dispatchers.Default) {
                loadObfImageBytes(image, fileStorage, mediaUrlLoader)
                    ?.decodeSampledBitmap(256)
                    ?.asImageBitmap()
            }
        }
    }
    val isSpeaking = communicationState.playbackStatus != CommunicationPlaybackStatus.Idle
    val isHolding = communicationState.heldMessage != null
    val hasMessage = activeMessage.parts.isNotEmpty()
    val pageNavigation = activeGraph
        ?.takeIf { mode == BoardWorkspaceMode.Run && it.boards.size > 1 }
        ?.let { graph ->
            PageNavigation(
                canGoBack = workspace.canNavigateBack,
                atStartPage = workspace.selectedBoardId == graph.boardSet.rootBoardId,
                onBack = { workspaceViewModel.onAction(BoardWorkspaceAction.PreviousPageClicked) },
                onStartPage = { workspaceViewModel.onAction(BoardWorkspaceAction.GoHome(graph.boardSet.rootBoardId)) },
            )
        }

    Box(Modifier.fillMaxSize()) {
        BoardWorkspaceContent(
            state = workspace,
            hasActiveBoard = activeGraph != null && activeBoard != null,
            onBackToLibrary = onExitToLibrary,
            onRetry = { workspaceViewModel.onAction(BoardWorkspaceAction.RetryLoad) },
        ) {
            if (activeGraph != null && activeBoard != null) {
                if (mode == BoardWorkspaceMode.Edit) {
                    ScreensEditHeader(
                        screenName = activeGraph.boardSet.name,
                        boards = activeGraph.boards,
                        selectedBoardId = workspace.selectedBoardId,
                        rootBoardId = activeGraph.boardSet.rootBoardId,
                        canResizePage = activeBoard.grid != null,
                        canUndo = editSession?.undoStack?.isNotEmpty() == true,
                        onLeave = ::leave,
                        onRenameScreen = { showRenameScreenDialog = true },
                        onOpenScreenSettings = { settingsTarget = BoardSettingsTarget.Screen },
                        onSelectBoard = {
                            workspaceViewModel.onAction(BoardWorkspaceAction.SelectBoard(it))
                            selectedField = null
                        },
                        onOpenPageSettings = { settingsTarget = BoardSettingsTarget.Page },
                        onResizePage = { showResizeBoardDialog = true },
                        onSetRootPage = { boardId ->
                            val session = editSession ?: return@ScreensEditHeader
                            workspaceViewModel.onAction(
                                BoardWorkspaceAction.ApplyEdit(setDraftRoot(session.draft, boardId))
                            )
                        },
                        onDeletePage = { showDeleteBoardDialog = true },
                        onAddPage = { showAddBoardDialog = true },
                        onUndo = { workspaceViewModel.onAction(BoardWorkspaceAction.UndoEdit) },
                        onFinish = ::requestFinishEditing,
                    )
                } else {
                    ScreensRunTopRow(
                        canEdit = !activeGraph.boardSet.isLocked,
                        showHiddenButtons = showHiddenButtons,
                        isExporting = isExporting,
                        pageNavigation = pageNavigation,
                        onEditScreen = ::startEditing,
                        onToggleHiddenButtons = hiddenButtonsSession::toggle,
                        onExport = { showExportDialog = true },
                        onPrint = {
                            runExport {
                                printBoardSetAsPdf(
                                    graph = activeGraph,
                                    settings = settings,
                                    fileStorage = fileStorage,
                                    mediaUrlLoader = mediaUrlLoader,
                                    shareService = shareService,
                                    resources = resources,
                                    exportedTemplate = exportedTemplate,
                                    exportCancelledMessage = exportCancelledMessage,
                                    exportErrorMessage = exportErrorMessage,
                                )
                            }
                        },
                        onBackToLibrary = ::leave,
                        onSwitchToTyping = onSwitchToKeyboard,
                        onOpenSettings = onOpenSettings,
                    )
                }

                if (resolvedBoardSettings.showMessageBar) {
                    ScreensMessageBarRow(
                        isEditMode = mode == BoardWorkspaceMode.Edit,
                        parts = messagePartItems,
                        messageText = messageText,
                        editable = resolvedBoardSettings.messageBarEditable,
                        actions = resolvedBoardSettings.messageBarActions,
                        hasMessage = hasMessage,
                        isSpeaking = isSpeaking,
                        isHolding = isHolding,
                        selectOnTouchDown = settings.selectOnTouchDown,
                        onSpeakPart = { index ->
                            val part = activeMessage.parts.getOrNull(index) ?: return@ScreensMessageBarRow
                            communicationSession.accept(
                                CommunicationAction.SpeakPart(part = part, voice = selectedVoice)
                            )
                        },
                        onRemovePart = { index ->
                            removeMessagePartAction(activeMessage, index)
                                ?.let(communicationSession::accept)
                        },
                        onTextChange = { text ->
                            communicationSession.accept(
                                Message.fromTextDiff(
                                    currentText = communicationSession.state.value.activeMessage.displayText,
                                    newText = text,
                                )
                            )
                        },
                        loadImage = loadChipImage,
                        onSpeak = {
                            communicationSession.accept(
                                CommunicationAction.SpeakActive(
                                    voice = selectedVoice,
                                    cacheAudio = activeGraph.boardSet.cacheWholeSentences,
                                )
                            )
                        },
                        onStopSpeech = { communicationSession.accept(CommunicationAction.Stop) },
                        onHold = { communicationSession.accept(CommunicationAction.SwapHeldMessage) },
                        onDeleteLast = {
                            communicationSession.accept(
                                CommunicationAction.RemoveLastPart(activeBoard.spellingMode)
                            )
                        },
                        onClear = { communicationSession.accept(CommunicationAction.Clear) },
                        onActionsChanged = { nextActions ->
                            val session = editSession ?: return@ScreensMessageBarRow
                            val updatedScreen = session.draft.boardSet.screenSettings.copy(
                                messageBarActions = nextActions,
                            )
                            workspaceViewModel.onAction(
                                BoardWorkspaceAction.ApplyEdit(
                                    session.draft.copy(
                                        boardSet = session.draft.boardSet.copy(
                                            screenSettings = updatedScreen,
                                        )
                                    )
                                )
                            )
                        },
                    )
                }

                PageSwipeBox(
                    enabled = pageSwipeIsActive(
                        isEditMode = mode == BoardWorkspaceMode.Edit,
                        swipeBetweenPages = settings.swipeBetweenPages,
                        holdToSelectMillis = settings.holdToSelectMillis,
                        selectOnTouchDown = settings.selectOnTouchDown,
                        selectOnTouchExit = settings.selectOnTouchExit,
                    ),
                    onSwipe = { step -> workspaceViewModel.onAction(BoardWorkspaceAction.SwipePage(step)) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    ObfBoardView(
                        board = activeBoard,
                        isEditMode = mode == BoardWorkspaceMode.Edit,
                        showMessageBar = false,
                        messageText = messageText,
                        boardSettings = resolvedBoardSettings,
                        showHiddenButtons = showHiddenButtons,
                        selectedButtons = selectedButtons,
                        highlightedButtonId = selectionHighlight.buttonId,
                        backgroundImage = backgroundBitmap,
                        predictionLabels = predictionsById,
                        onButtonClick = { button ->
                            handleWorkspaceButtonClick(
                                button = button,
                                activeGraph = activeGraph,
                                activeBoard = activeBoard,
                                workspace = workspace,
                                settings = settings,
                                resolvedBoardSettings = resolvedBoardSettings,
                                selectedVoice = selectedVoice,
                                predictionButtonIds = predictionButtonIds,
                                predictionsById = predictionsById,
                                communicationSession = communicationSession,
                                workspaceViewModel = workspaceViewModel,
                                fileStorage = fileStorage,
                                soundPlayer = soundPlayer,
                                mediaUrlLoader = mediaUrlLoader,
                                scope = scope,
                                unsupportedActionTemplate = unsupportedActionTemplate,
                                onMarkButtonSelected = selectionHighlight::select,
                                onOpenNativeKeyboardDraft = { nativeKeyboardDraft = it },
                            )
                        },
                        onCellClick = if (mode == BoardWorkspaceMode.Edit) {
                            { row, column, button ->
                                when (
                                    val result = resolveCellTap(
                                        grid = activeBoard.grid,
                                        row = row,
                                        column = column,
                                        button = button
                                    )
                                ) {
                                    is CellTapResult.OpenDialog -> {
                                        // On-canvas editing: first tap selects the field (showing
                                        // resize handles and the trash badge), second tap opens its
                                        // editor. Empty cells open the editor immediately.
                                        if (result.button != null) {
                                            val anchor = activeBoard.grid?.fieldAnchorAt(result.row, result.column)
                                            if (anchor != null && anchor == selectedField) {
                                                editingCell = WorkspaceCellTarget(anchor.first, anchor.second, result.button)
                                            } else {
                                                selectedField = anchor
                                            }
                                        } else {
                                            selectedField = null
                                            editingCell = WorkspaceCellTarget(result.row, result.column, null)
                                        }
                                    }
                                }
                            }
                        } else null,
                        onCellMove = if (mode == BoardWorkspaceMode.Edit) {
                            { fromRow, fromColumn, toRow, toColumn ->
                                val session = editSession
                                if (session != null) {
                                    selectedField = null
                                    workspaceViewModel.onAction(
                                        BoardWorkspaceAction.ApplyEdit(
                                            moveDraftField(session.draft, activeBoard.id, fromRow, fromColumn, toRow, toColumn)
                                        )
                                    )
                                }
                            }
                        } else null,
                        // Launcher-style: hold a Button for Edit, Resize or Remove.
                        cellMenu = if (mode == BoardWorkspaceMode.Edit) {
                            BoardCellMenu(
                                onEdit = { row, column, button ->
                                    selectedField = activeBoard.grid?.fieldAnchorAt(row, column)
                                    editingCell = WorkspaceCellTarget(row, column, button)
                                },
                                // Selecting the Button shows its resize handles.
                                onResize = { row, column -> selectedField = activeBoard.grid?.fieldAnchorAt(row, column) },
                                // One edit, so Undo brings it back.
                                onRemove = { row, column ->
                                    val session = editSession
                                    if (session != null) {
                                        selectedField = null
                                        workspaceViewModel.onAction(
                                            BoardWorkspaceAction.ApplyEdit(
                                                clearDraftCell(session.draft, activeBoard.id, row, column)
                                            )
                                        )
                                    }
                                },
                            )
                        } else null,
                        selectedFieldAnchor = selectedField,
                        selectedFieldSpans = remember(activeBoard.grid, selectedField) {
                            selectedField?.let { (row, column) ->
                                activeBoard.grid?.availableFieldSpansAt(row, column).orEmpty()
                            }.orEmpty()
                        },
                        onResizeField = if (mode == BoardWorkspaceMode.Edit) {
                            { anchorRow, anchorColumn, rowSpan, columnSpan ->
                                val session = editSession ?: return@ObfBoardView
                                val resized = resizeDraftField(
                                    graph = session.draft,
                                    boardId = activeBoard.id,
                                    row = anchorRow,
                                    column = anchorColumn,
                                    rowSpan = rowSpan,
                                    columnSpan = columnSpan
                                )
                                if (resized != session.draft) {
                                    workspaceViewModel.onAction(BoardWorkspaceAction.ApplyEdit(resized))
                                }
                            }
                        } else null,
                        onRemoveField = if (mode == BoardWorkspaceMode.Edit) {
                            { row, column ->
                                val session = editSession ?: return@ObfBoardView
                                selectedField = null
                                workspaceViewModel.onAction(
                                    BoardWorkspaceAction.ApplyEdit(
                                        clearDraftCell(session.draft, activeBoard.id, row, column)
                                    )
                                )
                            }
                        } else null,
                        onGridHeightFractionChange = if (mode == BoardWorkspaceMode.Edit) {
                            { fraction ->
                                val session = editSession ?: return@ObfBoardView
                                workspaceViewModel.onAction(
                                    BoardWorkspaceAction.ApplyEdit(
                                        session.draft.copy(
                                            boards = session.draft.boards.map { board ->
                                                if (board.id == activeBoard.id) board.withGridHeightFraction(fraction) else board
                                            }
                                        )
                                    )
                                )
                            }
                        } else null,
                        homeBoardId = activeGraph.boardSet.rootBoardId,
                        onSpeakSentence = {
                            communicationSession.accept(
                                CommunicationAction.SpeakActive(
                                    voice = selectedVoice,
                                    cacheAudio = activeGraph.boardSet.cacheWholeSentences,
                                )
                            )
                        },
                        onDeleteLast = {
                            communicationSession.accept(
                                CommunicationAction.RemoveLastPart(activeBoard.spellingMode)
                            )
                        },
                        onClearSentence = {
                            communicationSession.accept(CommunicationAction.Clear)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    if (showRenameScreenDialog && activeGraph != null) {
        RenameScreenDialog(
            currentName = activeGraph.boardSet.name,
            onDismiss = { showRenameScreenDialog = false },
            onRename = { newName ->
                val session = editSession
                if (session != null) {
                    workspaceViewModel.onAction(
                        BoardWorkspaceAction.ApplyEdit(renameDraftBoardSet(session.draft, newName))
                    )
                }
                showRenameScreenDialog = false
            },
        )
    }

    if (showAddBoardDialog) {
        CreateBoardDialog(
            initialKeyboardLayout = activeBoard?.keyboardLayout,
            onDismiss = { showAddBoardDialog = false },
            onCreate = { name, rows, columns, keyboardLayout ->
                val session = editSession ?: return@CreateBoardDialog
                val updated = addDraftBoard(session.draft, name, rows, columns, keyboardLayout)
                workspaceViewModel.onAction(BoardWorkspaceAction.ApplyEdit(updated))
                workspaceViewModel.onAction(BoardWorkspaceAction.SelectBoard(updated.boards.last().id))
                showAddBoardDialog = false
            }
        )
    }

    if (showEditingAccessDialog && editingAccessController != null) {
        EditingAccessDialog(
            controller = editingAccessController,
            mode = EditingAccessDialogMode.Unlock,
            onDismiss = { workspaceViewModel.onAction(BoardWorkspaceAction.EditingAccessDismissed) },
            onSuccess = { enterEditing() }
        )
    }

    val target = editingCell
    if (target != null && activeBoard != null) {
        val initialImageUrl = target.button?.imageId
            ?.let { id -> activeBoard.images.firstOrNull { it.id == id }?.url }
            .orEmpty()
        val initialRecordingPath = target.button?.soundId
            ?.let { id -> activeBoard.sounds.firstOrNull { it.id == id }?.path }
        EditBoardCellDialog(
            boardName = activeBoard.name ?: stringResource(R.string.board_workspace_board_fallback),
            row = target.row,
            column = target.column,
            initialLabel = target.button?.label.orEmpty(),
            initialVocalization = target.button?.vocalization.orEmpty(),
            initialImageUrl = initialImageUrl,
            initialRecordingPath = initialRecordingPath,
            initialBackgroundColor = target.button?.backgroundColor,
            availableLanguages = availableFieldLanguages,
            initialLanguage = target.button?.locale,
            initialMathMode = target.button?.mathMode == true,
            initialHidden = target.button?.hidden == true,
            initialShape = target.button?.shape ?: ObfButtonShape.Rounded,
            initialWordType = target.button?.wordType,
            isKeyboardBoard = activeBoard.isKeyboard,
            showMathMode = supportsMathMode(settings.ttsEngine),
            availableBoards = activeGraph.boards.filterNot { it.id == activeBoard.id },
            initialLinkedBoardId = activeGraph.resolveLinkedBoard(target.button?.loadBoard)?.id,
            initialAction = target.button?.action,
            initialActions = target.button?.actions.orEmpty(),
            hasExistingValue = target.button != null,
            onDismiss = { editingCell = null },
            onSave = { label, vocalization, imageUrl, recordingPath, backgroundColor, language, mathMode, hidden, linkedBoardId,
                       action, actions, shape, wordType ->
                val session = editSession ?: return@EditBoardCellDialog
                workspaceViewModel.onAction(
                    BoardWorkspaceAction.ApplyEdit(
                        updateDraftCell(
                            graph = session.draft,
                            boardId = activeBoard.id,
                            row = target.row,
                            column = target.column,
                            label = label,
                            vocalization = vocalization,
                            imageUrl = imageUrl,
                            recordingPath = recordingPath,
                            backgroundColor = backgroundColor,
                            language = language,
                            mathMode = mathMode,
                            hidden = hidden,
                            linkedBoardId = linkedBoardId,
                            action = action,
                            actions = actions,
                            shape = shape,
                            wordType = wordType
                        )
                    )
                )
                editingCell = null
            },
            onClearCell = {
                val session = editSession ?: return@EditBoardCellDialog
                workspaceViewModel.onAction(
                    BoardWorkspaceAction.ApplyEdit(
                        clearDraftCell(session.draft, activeBoard.id, target.row, target.column)
                    )
                )
                editingCell = null
            }
        )
    }

    if (showFinishDialog) {
        FinishEditingBoardDialog(
            onKeepEditing = { workspaceViewModel.onAction(BoardWorkspaceAction.KeepEditing) },
            onDiscard = {
                selectedField = null
                workspaceViewModel.onAction(BoardWorkspaceAction.DiscardEdits)
            },
            onSave = {
                val session = editSession ?: return@FinishEditingBoardDialog
                selectedField = null
                workspaceViewModel.onAction(BoardWorkspaceAction.SaveStarted)
                val graph = session.draft
                // Persist on an application-scoped scope so leaving this screen can't
                // cancel the write halfway; branch back to the main thread for state updates.
                val appScope = koin.get<CoroutineScope>()
                appScope.launch {
                    val result = withContext(Dispatchers.Default) {
                        useCase.saveBoardSetGraph(graph)
                    }
                    // State updates remain tied to this screen even though persistence is
                    // allowed to finish after navigation.
                    scope.launch {
                        result.onSuccess { saved ->
                            workspaceViewModel.onAction(BoardWorkspaceAction.SaveSucceeded(saved))
                            // Prewarm TTS audio off the critical path; persistence
                            // must never wait on per-button synthesis.
                            appScope.launch {
                                runCatching { useCase.warmSpeechCache(saved) }
                            }
                        }.onFailure {
                            // Persistence failed: drop back into editing with the draft intact
                            // so the user does not lose their work.
                            workspaceViewModel.onAction(
                                BoardWorkspaceAction.SaveFailed(saveErrorMessage)
                            )
                        }
                    }
                }
            },
        )
    }

    val resizeTargetBoard = activeBoard
    val resizeTargetGrid = resizeTargetBoard?.grid
    if (showResizeBoardDialog && resizeTargetBoard != null && resizeTargetGrid != null) {
        ResizeBoardDialog(
            currentRows = resizeTargetGrid.rows,
            currentColumns = resizeTargetGrid.columns,
            onDismiss = { showResizeBoardDialog = false },
            onResize = { rows, columns ->
                val session = editSession ?: return@ResizeBoardDialog false
                val resized = resizeDraftBoard(session.draft, resizeTargetBoard.id, rows, columns)
                if (resized == session.draft) {
                    false
                } else {
                    workspaceViewModel.onAction(BoardWorkspaceAction.ApplyEdit(resized))
                    showResizeBoardDialog = false
                    true
                }
            }
        )
    }

    if (showDeleteBoardDialog && activeBoard != null) {
        DeleteBoardConfirmationDialog(
            boardName = activeBoard.name ?: stringResource(R.string.board_workspace_board_fallback),
            onDismiss = { showDeleteBoardDialog = false },
            onConfirmDelete = {
                val session = editSession
                if (session != null) {
                    val updated = deleteDraftBoard(session.draft, activeBoard.id)
                    workspaceViewModel.onAction(BoardWorkspaceAction.ApplyEdit(updated))
                    workspaceViewModel.onAction(
                        BoardWorkspaceAction.GoHome(updated.boardSet.rootBoardId)
                    )
                }
                showDeleteBoardDialog = false
            },
        )
    }
}
