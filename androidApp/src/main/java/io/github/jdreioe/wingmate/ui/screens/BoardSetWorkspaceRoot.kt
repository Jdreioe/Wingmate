package io.github.jdreioe.wingmate.ui

import io.github.jdreioe.wingmate.domain.obf.withBackgroundImage
import androidx.compose.ui.graphics.asImageBitmap
import io.github.jdreioe.wingmate.domain.obf.backgroundImage
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Print
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import io.github.jdreioe.wingmate.application.BoardSetUseCase
import io.github.jdreioe.wingmate.application.GridsetExportResult
import io.github.jdreioe.wingmate.application.GridsetExporter
import io.github.jdreioe.wingmate.application.ObzExportResult
import io.github.jdreioe.wingmate.application.BoardSetSpeechCacheUseCase
import io.github.jdreioe.wingmate.application.KeyboardPreset
import io.github.jdreioe.wingmate.infrastructure.BoardImportService
import io.github.jdreioe.wingmate.infrastructure.BoardImportResult
import io.github.jdreioe.wingmate.application.FeatureUsageEvents
import io.github.jdreioe.wingmate.application.reportEvent
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.application.EditingAccessController
import io.github.jdreioe.wingmate.domain.Base64Decoder
import io.github.jdreioe.wingmate.domain.CommunicationAction
import io.github.jdreioe.wingmate.domain.CommunicationSession
import io.github.jdreioe.wingmate.domain.CommunicationSessionSnapshot
import io.github.jdreioe.wingmate.domain.FileStorage
import io.github.jdreioe.wingmate.domain.Message
import io.github.jdreioe.wingmate.domain.MessagePart
import io.github.jdreioe.wingmate.domain.SoundPlayer
import io.github.jdreioe.wingmate.domain.TextPredictionService
import io.github.jdreioe.wingmate.domain.fromScreenButton
import io.github.jdreioe.wingmate.domain.fromTextDiff
import io.github.jdreioe.wingmate.domain.legacyScreenMessage
import io.github.jdreioe.wingmate.domain.obf.BoardSetGraph
import io.github.jdreioe.wingmate.domain.toScreenButtons
import io.github.jdreioe.wingmate.domain.obf.ScreenKind
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfButtonActionEffect
import io.github.jdreioe.wingmate.domain.obf.ObfButtonType
import io.github.jdreioe.wingmate.domain.obf.ObfButtonShape
import io.github.jdreioe.wingmate.domain.obf.wordType
import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import io.github.jdreioe.wingmate.domain.obf.obfImageSources
import io.github.jdreioe.wingmate.domain.obf.printableScreen
import io.github.jdreioe.wingmate.domain.obf.ObfKeyboardLayout
import io.github.jdreioe.wingmate.domain.obf.ObfLoadBoard
import io.github.jdreioe.wingmate.domain.obf.ObfSound
import io.github.jdreioe.wingmate.domain.obf.ObfMediaSource
import io.github.jdreioe.wingmate.domain.obf.ObfMediaUrlLoader
import io.github.jdreioe.wingmate.domain.obf.obfSoundSources
import io.github.jdreioe.wingmate.domain.obf.BoardActivationBehavior
import io.github.jdreioe.wingmate.domain.obf.BoardReturnBehavior
import io.github.jdreioe.wingmate.domain.obf.pageSettingsOverrides
import io.github.jdreioe.wingmate.domain.obf.resolveBoardSettings
import io.github.jdreioe.wingmate.domain.obf.withPageSettingsOverrides
import io.github.jdreioe.wingmate.domain.obf.withHomeFieldsBottomLeft
import io.github.jdreioe.wingmate.domain.obf.parseObfButtonActions
import io.github.jdreioe.wingmate.domain.obf.GridFieldSpan
import io.github.jdreioe.wingmate.domain.obf.CellTapResult
import io.github.jdreioe.wingmate.domain.obf.nGramPredictionInsertion
import io.github.jdreioe.wingmate.domain.obf.shouldAddBoardSelection
import io.github.jdreioe.wingmate.domain.obf.shouldSpeakSelectionImmediately
import io.github.jdreioe.wingmate.domain.obf.applyBoardReturnBehavior
import io.github.jdreioe.wingmate.domain.obf.orderedPredictionButtonIds
import io.github.jdreioe.wingmate.domain.obf.resolveCellTap
import io.github.jdreioe.wingmate.domain.obf.renameDraftBoardSet
import io.github.jdreioe.wingmate.domain.obf.renameDraftBoard
import io.github.jdreioe.wingmate.domain.obf.resizeDraftBoard
import io.github.jdreioe.wingmate.domain.obf.moveDraftField
import io.github.jdreioe.wingmate.domain.obf.resizeDraftField
import io.github.jdreioe.wingmate.domain.obf.updateDraftCell
import io.github.jdreioe.wingmate.domain.obf.clearDraftCell
import io.github.jdreioe.wingmate.domain.obf.normalizedOrder
import io.github.jdreioe.wingmate.domain.obf.fieldSpanAt
import io.github.jdreioe.wingmate.domain.obf.fieldAnchorAt
import io.github.jdreioe.wingmate.domain.obf.availableFieldSpansAt
import io.github.jdreioe.wingmate.domain.obf.withFieldSpan
import io.github.jdreioe.wingmate.domain.obf.resized
import io.github.jdreioe.wingmate.domain.obf.moveOrSwapField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.koin.compose.koinInject
import kotlin.random.Random
import kotlin.time.Clock

import com.hojmoseit.wingmate.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BoardSetWorkspaceRoot(
    boardSetId: String,
    initialMode: BoardWorkspaceMode,
    onSwitchToKeyboard: () -> Unit,
    onExitToLibrary: () -> Unit
) {
    val useCase = koinInject<BoardSetUseCase>()
    val communicationSession = koinInject<CommunicationSession>()
    val communicationState by communicationSession.state.collectAsStateWithLifecycle()
    val voiceUseCase = koinInject<VoiceUseCase>()
    val soundPlayer = koinInject<SoundPlayer>()
    val fileStorage = koinInject<FileStorage>()
    val settings by rememberReactiveSettings()
    val koin = org.koin.compose.getKoin()
    val predictionService = remember(koin) { koin.getOrNull<TextPredictionService>() }
    val mediaUrlLoader = remember(koin) { koin.getOrNull<ObfMediaUrlLoader>() }
    val shareService = remember(koin) { koin.getOrNull<io.github.jdreioe.wingmate.platform.ShareService>() }
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
    val selectedVoice by produceState<io.github.jdreioe.wingmate.domain.Voice?>(
        initialValue = null,
        key1 = voiceUseCase,
    ) {
        value = runCatching { voiceUseCase.selected() }.getOrNull()
    }
    val savedGraph = workspace.savedGraph
    val editSession = workspace.editSession
    val mode = workspace.mode
    val statusMessage = workspace.statusMessage
    val showFinishDialog = workspace.showFinishDialog
    val isExporting = workspace.isExporting
    val isFullscreen = workspace.isFullscreen
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
    var settingsTarget by remember(boardSetId) { mutableStateOf<BoardSettingsTarget?>(null) }
    val hiddenButtonsSession = remember(boardSetId) { HiddenButtonsSession() }
    val showHiddenButtons = hiddenButtonsSession.revealed
    val showEditingAccessDialog = workspace.showEditingAccessDialog
    var appBarMenuExpanded by remember(boardSetId) { mutableStateOf(false) }
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
            workspaceViewModel.onAction(
                BoardWorkspaceAction.LoadFailed(loadErrorMessage)
            )
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

    fun markButtonSelected(buttonId: String) {
        selectionHighlight.select(buttonId)
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
    LaunchedEffect(
        communicationState.isInitialized,
        activeGraph?.boardSet?.id,
        legacySentenceButtons,
    ) {
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
            screen = activeGraph?.boardSet?.screenSettings
                ?: io.github.jdreioe.wingmate.domain.obf.BoardSettingsOverrides(),
            page = activeBoard?.pageSettingsOverrides()
                ?: io.github.jdreioe.wingmate.domain.obf.BoardSettingsOverrides()
        )
    }
    val messageText = activeMessage.displayText
    val availableBoardActions = remember(activeBoard, showHiddenButtons) {
        val visibleGridButtonIds = activeBoard?.grid
            ?.order
            ?.flatten()
            ?.filterNotNull()
            ?.toSet()
        activeBoard?.buttons
            ?.asSequence()
            ?.filter { button ->
                (!button.hidden || showHiddenButtons) &&
                    (visibleGridButtonIds == null || button.id in visibleGridButtonIds)
            }
            ?.flatMap { parseObfButtonActions(it).asSequence() }
            ?.toList()
            .orEmpty()
    }
    val boardHasDeleteField = availableBoardActions.any { it == ObfButtonActionEffect.Backspace }
    val boardHasClearField = availableBoardActions.any { it == ObfButtonActionEffect.Clear }
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

    fun navigateBack() {
        workspaceViewModel.onAction(BoardWorkspaceAction.BackClicked)
    }

    // The top bar's ← always leaves the Screen; Pages have their own Back in the Message bar.
    fun leave() {
        workspaceViewModel.onAction(BoardWorkspaceAction.LeaveClicked)
    }

    fun exportBoardSet() {
        scope.launch {
            workspaceViewModel.onAction(BoardWorkspaceAction.ExportStarted)
            var resultMessage = exportCancelledMessage
            try {
                val graph = activeGraph
                if (graph == null) {
                    resultMessage = exportErrorMessage
                } else {
                    when (val export = useCase.exportBoardSetAsObzResult(graph.boardSet.id)) {
                        is ObzExportResult.Success -> {
                            val obzBytes = export.bytes
                            val fileName = "${graph.boardSet.name}.obz"
                            if (shareService != null) {
                                val shared = shareService.shareFile(fileName, obzBytes)
                                resultMessage = if (shared) {
                                    exportedTemplate.replace("%NAME%", fileName)
                                } else {
                                    exportCancelledMessage
                                }
                            } else {
                                // Without a share sheet there is nowhere to deliver the file.
                                resultMessage = exportErrorMessage
                            }
                        }
                        is ObzExportResult.Failure -> resultMessage = exportErrorMessage
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                resultMessage = exportErrorMessage
            } finally {
                workspaceViewModel.onAction(BoardWorkspaceAction.ExportFinished(resultMessage))
            }
        }
    }

    fun exportGridset() {
        scope.launch {
            workspaceViewModel.onAction(BoardWorkspaceAction.ExportStarted)
            var resultMessage = exportCancelledMessage
            try {
                val graph = activeGraph
                if (graph == null) {
                    resultMessage = exportErrorMessage
                } else {
                    when (
                        val export = useCase.exportBoardSetAsGridsetResult(
                            boardSetId = graph.boardSet.id,
                            appSettings = settings,
                            loadUrl = mediaUrlLoader?.let { loader -> { url -> loader.load(url) } },
                        )
                    ) {
                        is GridsetExportResult.Success -> {
                            val fileName = GridsetExporter.sanitizeExportFileName(graph.boardSet.name, "gridset")
                            if (shareService != null) {
                                val shared = shareService.shareFile(fileName, export.bytes)
                                resultMessage = if (shared) {
                                    exportedTemplate.replace("%NAME%", fileName)
                                } else {
                                    exportCancelledMessage
                                }
                            } else {
                                resultMessage = exportErrorMessage
                            }
                        }
                        is GridsetExportResult.Failure -> resultMessage = exportErrorMessage
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                resultMessage = exportErrorMessage
            } finally {
                workspaceViewModel.onAction(BoardWorkspaceAction.ExportFinished(resultMessage))
            }
        }
    }

    /** Shares the whole Screen as a printable PDF, the fallback for when no device works (#227). */
    fun printBoardSet() {
        scope.launch {
            workspaceViewModel.onAction(BoardWorkspaceAction.ExportStarted)
            var resultMessage = exportErrorMessage
            try {
                val graph = activeGraph
                if (graph != null && shareService != null) {
                    val pdf = renderBoardPdf(
                        pages = printableScreen(graph, settings.primaryLanguage),
                        loadImage = { image -> loadObfImageBytes(image, fileStorage, mediaUrlLoader) },
                        opensLabel = { link -> resources.getString(R.string.board_workspace_print_opens, link.title, link.pageNumber) },
                    )
                    val fileName = "${graph.boardSet.name}.pdf"
                    resultMessage = if (shareService.shareFile(fileName, pdf)) {
                        exportedTemplate.replace("%NAME%", fileName)
                    } else {
                        exportCancelledMessage
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                resultMessage = exportErrorMessage
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
                exportBoardSet()
            },
            onExportGridset = {
                showExportDialog = false
                exportGridset()
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
                    workspaceViewModel.onAction(BoardWorkspaceAction.ApplyEdit(session.draft.copy(
                        boardSet = session.draft.boardSet.copy(name = name, screenSettings = updatedSettings)
                    )))
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
                    val draft = editSession?.draft ?: return@launch
                    workspaceViewModel.onAction(BoardWorkspaceAction.ApplyEdit(draft.copy(
                        boards = draft.boards.map { board ->
                            if (board.id != pageId) return@map board
                            val edited = board.copy(name = name, backgroundColor = updatedBackgroundColor)
                                .withPageSettingsOverrides(updatedSettings)
                            val currentImage = board.backgroundImage?.let { bg -> board.images.firstOrNull { it.id == bg.imageId } }
                            when (backgroundEdit) {
                                BackgroundImageEdit.None -> edited.withBackgroundImage(null)
                                is BackgroundImageEdit.Current -> edited.withBackgroundImage(currentImage, backgroundEdit.fit)
                                is BackgroundImageEdit.Picked ->
                                    if (picked != null) edited.withBackgroundImage(picked, backgroundEdit.fit) else edited
                            }
                        }
                    )))
                }
            },
            onBack = { settingsTarget = null }
        )
        return
    }

    BackHandler(enabled = true, onBack = ::navigateBack)
    PlatformBackgroundEffect { editingAccessController?.lock() }
    // The selected Button while editing, if any; Back deselects it before leaving.
    val selectedButton = if (mode == BoardWorkspaceMode.Edit) {
        selectedField?.let { (row, column) ->
            activeBoard?.grid?.normalizedOrder()?.getOrNull(row)?.getOrNull(column)
                ?.let { id -> activeBoard.buttons.firstOrNull { it.id == id } }
        }
    } else null
    BackHandler(enabled = selectedButton != null) { selectedField = null }

    Scaffold(
        topBar = {
            val selection = selectedField
            if (!isFullscreen && selectedButton != null && selection != null) {
                SelectedButtonTopBar(
                    label = selectedButton.label ?: selectedButton.vocalization.orEmpty(),
                    onEdit = { editingCell = WorkspaceCellTarget(selection.first, selection.second, selectedButton) },
                    onRemove = {
                        val session = editSession
                        val boardId = activeBoard?.id
                        if (session != null && boardId != null) {
                            selectedField = null
                            workspaceViewModel.onAction(BoardWorkspaceAction.ApplyEdit(
                                clearDraftCell(session.draft, boardId, selection.first, selection.second)
                            ))
                        }
                    },
                    onDeselect = { selectedField = null },
                )
            } else if (!isFullscreen) BoxWithConstraints(Modifier.fillMaxWidth()) {
                val useOverflowMenu = maxWidth < if (mode == BoardWorkspaceMode.Edit) 720.dp else 560.dp
                TopAppBar(
                title = {
                    Column {
                        Text(
                            if (mode == BoardWorkspaceMode.Edit) {
                                stringResource(
                                    R.string.board_workspace_editing,
                                    activeGraph?.boardSet?.name.orEmpty()
                                )
                            } else {
                                // "Screen · Page", so the Communicator sees which Page of which Screen.
                                val page = activeBoard?.name ?: stringResource(R.string.board_workspace_board_fallback)
                                val screen = activeGraph?.boardSet?.name
                                if (screen != null && screen != page && (activeGraph?.boards?.size ?: 0) > 1) "$screen · $page" else page
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (mode == BoardWorkspaceMode.Edit) activeBoard?.name?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = ::leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (useOverflowMenu && mode == BoardWorkspaceMode.Edit) {
                        // Undo is the safety net and Finish the way out, so a phone keeps both
                        // visible; the rest of the editing tools are in the menu.
                        IconButton(
                            onClick = { workspaceViewModel.onAction(BoardWorkspaceAction.UndoEdit) },
                            enabled = editSession?.undoStack?.isNotEmpty() == true
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.board_workspace_undo))
                        }
                        TextButton(onClick = ::requestFinishEditing) {
                            Text(stringResource(R.string.board_workspace_finish))
                        }
                    }
                    if (useOverflowMenu) {
                        Box {
                            IconButton(onClick = { appBarMenuExpanded = true }) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.common_more_actions)
                                )
                            }
                            DropdownMenu(
                                expanded = appBarMenuExpanded,
                                onDismissRequest = { appBarMenuExpanded = false }
                            ) {
                                if (mode == BoardWorkspaceMode.Edit) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.board_workspace_add)) },
                                        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                        onClick = {
                                            appBarMenuExpanded = false
                                            showAddBoardDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.board_settings_page_title)) },
                                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                        onClick = {
                                            appBarMenuExpanded = false
                                            settingsTarget = BoardSettingsTarget.Page
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.board_settings_screen_title)) },
                                        leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                        onClick = {
                                            appBarMenuExpanded = false
                                            settingsTarget = BoardSettingsTarget.Screen
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.board_workspace_resize)) },
                                        leadingIcon = { Icon(Icons.Default.ImportExport, contentDescription = null) },
                                        enabled = activeBoard?.grid != null,
                                        onClick = {
                                            appBarMenuExpanded = false
                                            showResizeBoardDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.board_workspace_set_home)) },
                                        leadingIcon = { Icon(Icons.Default.Home, contentDescription = null) },
                                        enabled = activeBoard != null &&
                                            activeBoard.id != activeGraph.boardSet.rootBoardId,
                                        onClick = {
                                            appBarMenuExpanded = false
                                            val session = editSession
                                            val board = activeBoard
                                            if (session != null && board != null) {
                                                workspaceViewModel.onAction(
                                                    BoardWorkspaceAction.ApplyEdit(
                                                        setDraftRoot(session.draft, board.id)
                                                    )
                                                )
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.board_workspace_delete)) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        },
                                        enabled = activeGraph?.boards?.size?.let { it > 1 } == true,
                                        onClick = {
                                            appBarMenuExpanded = false
                                            showDeleteBoardDialog = true
                                        }
                                    )
                                } else {
                                    DropdownMenuItem(
                                        text = {
                                            Text(stringResource(
                                                if (showHiddenButtons) R.string.board_workspace_hide_hidden
                                                else R.string.board_workspace_show_hidden
                                            ))
                                        },
                                        leadingIcon = {
                                            Icon(
                                                if (showHiddenButtons) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = null
                                            )
                                        },
                                        onClick = {
                                            appBarMenuExpanded = false
                                            hiddenButtonsSession.toggle()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.board_workspace_enter_fullscreen)) },
                                        leadingIcon = { Icon(Icons.Default.Fullscreen, contentDescription = null) },
                                        onClick = {
                                            appBarMenuExpanded = false
                                            workspaceViewModel.onAction(
                                                BoardWorkspaceAction.FullscreenChanged(true)
                                            )
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.mode_switch_to_keyboard)) },
                                        leadingIcon = { Icon(Icons.Default.Keyboard, contentDescription = null) },
                                        onClick = {
                                            appBarMenuExpanded = false
                                            onSwitchToKeyboard()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.phrase_screen_import_export_data)) },
                                        leadingIcon = { Icon(Icons.Default.ImportExport, contentDescription = null) },
                                        enabled = !isExporting,
                                        onClick = {
                                            appBarMenuExpanded = false
                                            showExportDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.board_workspace_print)) },
                                        leadingIcon = { Icon(Icons.Default.Print, contentDescription = null) },
                                        enabled = !isExporting,
                                        onClick = {
                                            appBarMenuExpanded = false
                                            printBoardSet()
                                        }
                                    )
                                    if (activeGraph?.boardSet?.isLocked == false) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.board_workspace_edit)) },
                                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                            onClick = {
                                                appBarMenuExpanded = false
                                                startEditing()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    } else if (mode == BoardWorkspaceMode.Edit) {
                        IconButton(
                            onClick = { workspaceViewModel.onAction(BoardWorkspaceAction.UndoEdit) },
                            enabled = editSession?.undoStack?.isNotEmpty() == true
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.board_workspace_undo))
                        }
                        TextButton(onClick = ::requestFinishEditing) {
                            Text(stringResource(R.string.board_workspace_finish))
                        }
                        IconButton(onClick = { settingsTarget = BoardSettingsTarget.Page }) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.board_settings_page_title))
                        }
                        IconButton(onClick = { settingsTarget = BoardSettingsTarget.Screen }) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.board_settings_screen_title))
                        }
                        IconButton(
                            onClick = { showResizeBoardDialog = true },
                            enabled = activeBoard?.grid != null
                        ) {
                            Icon(Icons.Default.ImportExport, contentDescription = stringResource(R.string.board_workspace_resize))
                        }
                        IconButton(
                            onClick = {
                                val session = editSession
                                val board = activeBoard
                                if (session != null && board != null) {
                                    workspaceViewModel.onAction(
                                        BoardWorkspaceAction.ApplyEdit(
                                            setDraftRoot(session.draft, board.id)
                                        )
                                    )
                                }
                            },
                            enabled = activeBoard != null && activeBoard.id != activeGraph.boardSet.rootBoardId
                        ) {
                            Icon(Icons.Default.Home, contentDescription = stringResource(R.string.board_workspace_set_home))
                        }
                        IconButton(
                            onClick = { showDeleteBoardDialog = true },
                            enabled = activeGraph?.boards?.size?.let { it > 1 } == true
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.board_workspace_delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                        Box {
                            IconButton(onClick = { appBarMenuExpanded = true }) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.common_more_actions)
                                )
                            }
                            DropdownMenu(
                                expanded = appBarMenuExpanded,
                                onDismissRequest = { appBarMenuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.board_workspace_add)) },
                                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                    onClick = {
                                        appBarMenuExpanded = false
                                        showAddBoardDialog = true
                                    }
                                )
                            }
                        }
                    } else {
                        IconButton(onClick = hiddenButtonsSession::toggle) {
                            Icon(
                                if (showHiddenButtons) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(
                                    if (showHiddenButtons) R.string.board_workspace_hide_hidden
                                    else R.string.board_workspace_show_hidden
                                )
                            )
                        }
                        IconButton(onClick = {
                            workspaceViewModel.onAction(BoardWorkspaceAction.FullscreenChanged(true))
                        }) {
                            Icon(
                                Icons.Default.Fullscreen,
                                contentDescription = stringResource(R.string.board_workspace_enter_fullscreen)
                            )
                        }
                        IconButton(onClick = onSwitchToKeyboard) {
                            Icon(
                                Icons.Default.Keyboard,
                                contentDescription = stringResource(R.string.mode_switch_to_keyboard)
                            )
                        }
                        IconButton(
                            onClick = { showExportDialog = true },
                            enabled = !isExporting
                        ) {
                            Icon(
                                Icons.Default.ImportExport,
                                contentDescription = stringResource(R.string.phrase_screen_import_export_data)
                            )
                        }
                        if (activeGraph?.boardSet?.isLocked == false) {
                            IconButton(onClick = ::startEditing) {
                                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.board_workspace_edit))
                            }
                        }
                    }
                },
                colors = if (mode == BoardWorkspaceMode.Edit) {
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.primary,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                    )
                } else TopAppBarDefaults.topAppBarColors()
                )
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            BoardWorkspaceContent(
                state = workspace,
                hasActiveBoard = activeGraph != null && activeBoard != null,
                onBackToLibrary = onExitToLibrary,
                onRetry = { workspaceViewModel.onAction(BoardWorkspaceAction.RetryLoad) },
            ) {
                if (activeGraph != null && activeBoard != null) {
                    if (!isFullscreen && mode == BoardWorkspaceMode.Edit) {
                        BoardStrip(
                            boards = activeGraph.boards,
                            selectedBoardId = workspace.selectedBoardId,
                            onSelect = {
                                workspaceViewModel.onAction(
                                    BoardWorkspaceAction.SelectBoard(it)
                                )
                                selectedField = null
                            }
                        )
                    }
                    PageSwipeBox(
                        enabled = settings.swipeBetweenPages && mode == BoardWorkspaceMode.Run,
                        onSwipe = { step -> workspaceViewModel.onAction(BoardWorkspaceAction.SwipePage(step)) },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) {
                    ObfBoardView(
                        pageNavigation = activeGraph?.takeIf { mode == BoardWorkspaceMode.Run && it.boards.size > 1 }?.let { graph ->
                            PageNavigation(
                                canGoBack = workspace.canNavigateBack,
                                atStartPage = workspace.selectedBoardId == graph.boardSet.rootBoardId,
                                onBack = { workspaceViewModel.onAction(BoardWorkspaceAction.PreviousPageClicked) },
                                onStartPage = { workspaceViewModel.onAction(BoardWorkspaceAction.GoHome(graph.boardSet.rootBoardId)) },
                            )
                        },
                        board = activeBoard,
                        isEditMode = mode == BoardWorkspaceMode.Edit,
                        showMessageBar = mode == BoardWorkspaceMode.Run &&
                            resolvedBoardSettings.showMessageBar,
                        messageBarEditable = resolvedBoardSettings.messageBarEditable,
                        onSentenceChanged = { text ->
                            communicationSession.accept(
                                Message.fromTextDiff(
                                    currentText = communicationSession.state.value.activeMessage.displayText,
                                    newText = text,
                                )
                            )
                        },
                        messageText = messageText,
                        symbolBarPresentation = if (isFullscreen) {
                            SymbolBarPresentation.Fullscreen
                        } else {
                            SymbolBarPresentation.Normal
                        },
                        showSpeakControl = resolvedBoardSettings.showSpeakButton,
                        showDeleteControl = !boardHasDeleteField,
                        showClearControl = !boardHasClearField,
                        boardSettings = resolvedBoardSettings,
                        showHiddenButtons = showHiddenButtons,
                        selectedButtons = selectedButtons,
                        highlightedButtonId = selectionHighlight.buttonId,
                        backgroundImage = backgroundBitmap,
                        predictionLabels = predictionsById,
                        onButtonClick = { button ->
                            run {
                                // #118: debounce gating now lives in ObfButtonItem, which only
                                // dispatches accepted activations.
                                markButtonSelected(button.id)
                                val actions = parseObfButtonActions(button)
                            if (actions.isNotEmpty()) {
                                var navigateHome = false
                                for (effect in actions) {
                                    when (effect) {
                                        is ObfButtonActionEffect.AppendText -> {
                                            if (effect.text.isNotEmpty()) {
                                                communicationSession.accept(
                                                    CommunicationAction.AppendPart(
                                                        part = MessagePart(
                                                            displayText = effect.text,
                                                            languageTag = button.locale,
                                                            mathMode = button.mathMode,
                                                        ),
                                                        spellingMode = activeBoard.spellingMode,
                                                    )
                                                )
                                            }
                                        }
                                        is ObfButtonActionEffect.WrapSelection -> {
                                            // Token sentences hold no selection, so wrap falls back
                                            // to inserting prefix + fallback + suffix as one token.
                                            val wrapped = effect.prefix + effect.fallback + effect.suffix
                                            communicationSession.accept(
                                                CommunicationAction.AppendPart(
                                                    part = MessagePart(
                                                        displayText = wrapped,
                                                        languageTag = button.locale,
                                                        mathMode = button.mathMode,
                                                    ),
                                                    spellingMode = activeBoard.spellingMode,
                                                )
                                            )
                                        }
                                        ObfButtonActionEffect.Backspace -> {
                                            communicationSession.accept(
                                                CommunicationAction.RemoveLastPart(activeBoard.spellingMode)
                                            )
                                        }
                                        ObfButtonActionEffect.Clear -> {
                                            communicationSession.accept(CommunicationAction.Clear)
                                        }
                                        ObfButtonActionEffect.Speak -> {
                                            communicationSession.accept(
                                                CommunicationAction.SpeakActive(
                                                    voice = selectedVoice,
                                                    cacheAudio = activeGraph.boardSet.cacheWholeSentences,
                                                )
                                            )
                                        }
                                        ObfButtonActionEffect.Home -> {
                                            navigateHome = true
                                        }
                                        ObfButtonActionEffect.NativeKeyboard -> {
                                            nativeKeyboardDraft = communicationSession.state.value.activeMessage.displayText
                                        }
                                        ObfButtonActionEffect.Predictions -> {
                                            val currentText = communicationSession.state.value.activeMessage.displayText
                                            val insertion = predictionButtonIds
                                                .indexOf(button.id)
                                                .takeIf { it != -1 }
                                                ?.let { index -> predictionsById[button.id] }
                                                ?.let { nGramPredictionInsertion(currentText, it) }
                                            if (!insertion.isNullOrEmpty()) {
                                                communicationSession.accept(
                                                    CommunicationAction.AppendPart(
                                                        part = MessagePart(
                                                            displayText = insertion,
                                                            languageTag = button.locale,
                                                        ),
                                                        spellingMode = activeBoard.spellingMode,
                                                    )
                                                )
                                            }
                                        }
                                        ObfButtonActionEffect.Pause -> {
                                            communicationSession.accept(CommunicationAction.Pause)
                                        }
                                        ObfButtonActionEffect.Resume -> {
                                            communicationSession.accept(CommunicationAction.Resume)
                                        }
                                        ObfButtonActionEffect.Stop -> {
                                            communicationSession.accept(CommunicationAction.Stop)
                                        }
                                        ObfButtonActionEffect.SwapHeldMessage -> {
                                            communicationSession.accept(CommunicationAction.SwapHeldMessage)
                                        }
                                        ObfButtonActionEffect.ToggleSecondaryLanguage -> {
                                            workspaceViewModel.onAction(
                                                BoardWorkspaceAction.StatusChanged(
                                                    unsupportedActionTemplate.replace("%ACTION%", button.action.orEmpty())
                                                )
                                            )
                                        }
                                        is ObfButtonActionEffect.Unsupported -> {
                                            workspaceViewModel.onAction(
                                                BoardWorkspaceAction.StatusChanged(
                                                    unsupportedActionTemplate.replace("%ACTION%", effect.action)
                                                )
                                            )
                                        }
                                    }
                                }
                                if (navigateHome) {
                                    workspaceViewModel.onAction(
                                        BoardWorkspaceAction.GoHome(activeGraph.boardSet.rootBoardId)
                                    )
                                }
                            } else {
                                val linkedBoard = activeGraph.resolveLinkedBoard(button.loadBoard)
                                if (linkedBoard != null) {
                                    workspaceViewModel.onAction(
                                        BoardWorkspaceAction.OpenBoard(linkedBoard.id)
                                    )
                                } else {
                                    val part = MessagePart.fromScreenButton(
                                        screenId = activeGraph.boardSet.id,
                                        board = activeBoard,
                                        button = button,
                                        primaryLanguage = settings.primaryLanguage,
                                    )
                                    if (
                                        part != null &&
                                        shouldAddBoardSelection(resolvedBoardSettings.activationBehavior)
                                    ) {
                                        communicationSession.accept(
                                            CommunicationAction.AppendPart(
                                                part = part,
                                                spellingMode = activeBoard.spellingMode,
                                            )
                                        )
                                    }
                                    val sound = button.soundId?.let { id ->
                                        activeBoard.sounds.firstOrNull { it.id == id }
                                    }
                                    if (shouldSpeakSelectionImmediately(
                                        resolvedBoardSettings.speechPolicy ?: settings.speechPolicy,
                                        resolvedBoardSettings.activationBehavior
                                    )) {
                                        if (part != null) {
                                            communicationSession.accept(
                                                CommunicationAction.SpeakPart(
                                                    part = part,
                                                    voice = selectedVoice,
                                                )
                                            )
                                        } else if (sound != null) {
                                            scope.launch(Dispatchers.IO) {
                                                playButtonSound(
                                                sound = sound,
                                                fileStorage = fileStorage,
                                                soundPlayer = soundPlayer,
                                                urlLoader = mediaUrlLoader
                                            )
                                            }
                                        }
                                    }
                                    if (part != null || sound != null) {
                                        val returned = applyBoardReturnBehavior(
                                            behavior = resolvedBoardSettings.returnBehavior,
                                            currentBoardId = workspace.selectedBoardId,
                                            boardStack = workspace.boardStack,
                                            rootBoardId = activeGraph.boardSet.rootBoardId
                                        )
                                        workspaceViewModel.onAction(
                                            BoardWorkspaceAction.RestorePosition(
                                                selectedBoardId = returned.first,
                                                boardStack = returned.second,
                                            )
                                        )
                                    }
                                }
                            }
                            }
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
                                        // Like a launcher: a tap selects a Button (Edit and Remove in the
                                        // top bar, resize handles on it); tapping it again deselects. An
                                        // empty cell has nothing to select, so a tap adds a Button there.
                                        if (result.button != null) {
                                            val anchor = activeBoard.grid?.fieldAnchorAt(result.row, result.column)
                                            selectedField = anchor.takeUnless { it == selectedField }
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
                                val boardId = activeBoard.id
                                if (session != null) {
                                    selectedField = null
                                    workspaceViewModel.onAction(BoardWorkspaceAction.ApplyEdit(
                                        moveDraftField(
                                            session.draft,
                                            boardId,
                                            fromRow,
                                            fromColumn,
                                            toRow,
                                            toColumn
                                        )
                                    ))
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
                                        workspaceViewModel.onAction(BoardWorkspaceAction.ApplyEdit(
                                            clearDraftCell(session.draft, activeBoard.id, row, column)
                                        ))
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
                                val boardId = activeBoard.id
                                val resized = resizeDraftField(
                                    graph = session.draft,
                                    boardId = boardId,
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
                        onGridHeightFractionChange = if (mode == BoardWorkspaceMode.Edit) {
                            { fraction ->
                                val session = editSession ?: return@ObfBoardView
                                val boardId = activeBoard.id
                                workspaceViewModel.onAction(BoardWorkspaceAction.ApplyEdit(
                                    session.draft.copy(
                                        boards = session.draft.boards.map { board ->
                                            if (board.id == boardId) board.withGridHeightFraction(fraction) else board
                                        }
                                    )
                                ))
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
            if (isFullscreen && mode == BoardWorkspaceMode.Run && activeGraph != null && activeBoard != null) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    tonalElevation = 4.dp
                ) {
                    IconButton(onClick = {
                        workspaceViewModel.onAction(BoardWorkspaceAction.FullscreenChanged(false))
                    }) {
                        Icon(
                            Icons.Default.FullscreenExit,
                            contentDescription = stringResource(R.string.board_workspace_exit_fullscreen)
                        )
                    }
                }
            }
        }
    }

    if (showAddBoardDialog) {
        CreateBoardDialog(
            initialKeyboardLayout = activeBoard?.keyboardLayout,
            onDismiss = { showAddBoardDialog = false },
            onCreate = { name, rows, columns, keyboardLayout ->
                val session = editSession ?: return@CreateBoardDialog
                val updated = addDraftBoard(session.draft, name, rows, columns, keyboardLayout)
                workspaceViewModel.onAction(BoardWorkspaceAction.ApplyEdit(updated))
                workspaceViewModel.onAction(
                    BoardWorkspaceAction.SelectBoard(updated.boards.last().id)
                )
                showAddBoardDialog = false
            }
        )
    }

    if (showEditingAccessDialog && editingAccessController != null) {
        EditingAccessDialog(
            controller = editingAccessController,
            mode = EditingAccessDialogMode.Unlock,
            onDismiss = {
                workspaceViewModel.onAction(BoardWorkspaceAction.EditingAccessDismissed)
            },
            onSuccess = {
                enterEditing()
            }
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
                workspaceViewModel.onAction(BoardWorkspaceAction.ApplyEdit(
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
                ))
                editingCell = null
            },
            onClearCell = {
                val session = editSession ?: return@EditBoardCellDialog
                workspaceViewModel.onAction(BoardWorkspaceAction.ApplyEdit(
                    clearDraftCell(session.draft, activeBoard.id, target.row, target.column)
                ))
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
