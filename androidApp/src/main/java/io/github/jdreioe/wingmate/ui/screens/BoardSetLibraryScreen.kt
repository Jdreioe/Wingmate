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

internal const val BOARD_SET_OPEN_TEST_TAG_PREFIX = "board-set-open-"

/**
 * Board-set entry point with a familiar library -> Run/Edit workspace flow.
 */
@Composable
fun BoardSetManagerRoot(
    onBack: () -> Unit,
    onBackToWelcome: () -> Unit,
    initialBoardSetId: String? = null,
    initialMode: BoardWorkspaceMode = BoardWorkspaceMode.Run,
) {
    val koin = org.koin.compose.getKoin()
    val useCase = koinInject<BoardSetUseCase>()
    val boardSetSpeechCache = koinInject<BoardSetSpeechCacheUseCase>()
    val boardImportService = remember(koin) { koin.getOrNull<BoardImportService>() }
    val editingAccessController = remember(koin) { koin.getOrNull<EditingAccessController>() }
    val operations = remember(
        useCase,
        boardSetSpeechCache,
        boardImportService,
        editingAccessController,
    ) {
        DefaultBoardSetManagerOperations(
            useCase = useCase,
            speechCache = boardSetSpeechCache,
            importService = boardImportService,
            editingAccessController = editingAccessController,
        )
    }
    val factory = remember(operations) {
        viewModelFactory {
            initializer {
                BoardSetManagerViewModel(
                    savedStateHandle = createSavedStateHandle(),
                    operations = operations,
                )
            }
        }
    }
    val viewModel: BoardSetManagerViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val defaultBoardName = stringResource(R.string.board_dialog_default_board_name)

    LaunchedEffect(viewModel, initialBoardSetId, initialMode) {
        viewModel.onAction(
            BoardSetManagerAction.Initialize(
                initialBoardSetId = initialBoardSetId,
                initialMode = initialMode,
            )
        )
    }
    LaunchedEffect(viewModel, onBack) {
        viewModel.events.collect { event ->
            when (event) {
                BoardSetManagerEvent.NavigateBack -> onBack()
            }
        }
    }

    when (val route = state.route) {
        BoardSetManagerRoute.Library -> BoardSetManagerScreen(
            state = state,
            status = state.status,
            importAvailable = boardImportService != null,
            onAction = viewModel::onAction,
        )
        is BoardSetManagerRoute.Workspace -> BoardSetWorkspaceRoot(
            boardSetId = route.boardSetId,
            initialMode = route.mode,
            onSwitchToKeyboard = { viewModel.onAction(BoardSetManagerAction.BackClicked) },
            onExitToLibrary = { viewModel.onAction(BoardSetManagerAction.WorkspaceExited) },
        )
    }

    if (state.showSpeechImport) {
        SpeechImportDialog(state = state, onAction = viewModel::onAction)
    }

    if (state.showCreateDialog) {
        CreateBoardSetDialog(
            draft = state.createDraft,
            onDismiss = { viewModel.onAction(BoardSetManagerAction.CreateDismissed) },
            onNameChange = { viewModel.onAction(BoardSetManagerAction.CreateNameChanged(it)) },
            onRowsChange = { viewModel.onAction(BoardSetManagerAction.CreateRowsChanged(it)) },
            onColumnsChange = { viewModel.onAction(BoardSetManagerAction.CreateColumnsChanged(it)) },
            onTemplateSelected = { template, suggestedName ->
                viewModel.onAction(BoardSetManagerAction.CreateTemplateSelected(template, suggestedName))
            },
            onKeyboardPresetSelected = {
                viewModel.onAction(BoardSetManagerAction.CreateKeyboardPresetSelected(it))
            },
            onCreate = {
                val draft = state.createDraft
                viewModel.onAction(
                    BoardSetManagerAction.CreateSubmitted(
                        name = draft.name,
                        rows = draft.rowsText.toIntOrNull() ?: 4,
                        columns = draft.columnsText.toIntOrNull() ?: 8,
                        template = draft.template,
                        keyboardPreset = draft.keyboardPreset,
                        defaultBoardName = defaultBoardName,
                    )
                )
            },
        )
    }

    state.deleteTargetId?.let { targetId ->
        val boardSet = state.boardSets.firstOrNull { it.id == targetId } ?: return@let
        AlertDialog(
            onDismissRequest = { viewModel.onAction(BoardSetManagerAction.DeleteDismissed) },
            title = { Text(stringResource(R.string.board_sets_delete_title)) },
            text = { Text(stringResource(R.string.board_sets_delete_body, boardSet.name)) },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.onAction(BoardSetManagerAction.DeleteConfirmed) }
                ) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.onAction(BoardSetManagerAction.DeleteDismissed) }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (state.showDeleteAccessDialog && editingAccessController != null) {
        EditingAccessDialog(
            controller = editingAccessController,
            mode = EditingAccessDialogMode.Unlock,
            onDismiss = { viewModel.onAction(BoardSetManagerAction.DeleteAccessDismissed) },
            onSuccess = { viewModel.onAction(BoardSetManagerAction.DeleteAccessGranted) },
        )
    }

    if (state.showSettings) {
        SettingsScreen(
            onDismiss = { viewModel.onAction(BoardSetManagerAction.SettingsDismissed) },
            onBackToWelcome = {
                viewModel.onAction(BoardSetManagerAction.SettingsDismissed)
                onBackToWelcome()
            },
        )
    }
}

@Composable
internal fun BoardSetManagerMessage.resolve(): String = when (this) {
    is BoardSetManagerMessage.Dynamic -> value
    is BoardSetManagerMessage.Resource -> stringResource(id)
}

@Composable
internal fun BoardSetManagerScreen(
    state: BoardSetManagerState,
    status: LibraryStatus?,
    importAvailable: Boolean,
    onAction: (BoardSetManagerAction) -> Unit,
) {
    BoardSetLibraryScreen(
        boardSets = state.boardSets,
        isLoading = state.isLoading,
        isCreating = state.isCreating,
        status = status,
        onBack = { onAction(BoardSetManagerAction.BackClicked) },
        onOpenSettings = { onAction(BoardSetManagerAction.SettingsClicked) },
        onCreate = { onAction(BoardSetManagerAction.CreateClicked) },
        onImportSpeech = { onAction(BoardSetManagerAction.SpeechImportClicked) },
        onRetry = { onAction(BoardSetManagerAction.RetryLoad) },
        onImport = if (importAvailable) {
            { onAction(BoardSetManagerAction.ImportClicked) }
        } else {
            null
        },
        onOpen = { onAction(BoardSetManagerAction.OpenClicked(it.id)) },
        onEdit = { onAction(BoardSetManagerAction.EditClicked(it.id)) },
        onDuplicate = { onAction(BoardSetManagerAction.DuplicateClicked(it.id)) },
        onToggleLock = { onAction(BoardSetManagerAction.ToggleLockClicked(it.id)) },
        onDelete = { onAction(BoardSetManagerAction.DeleteClicked(it.id)) },
    )
}

@Preview(showBackground = true, widthDp = 1000, heightDp = 700)
@Composable
internal fun BoardSetManagerScreenPreview() {
    AppTheme {
        BoardSetManagerScreen(
            state = BoardSetManagerState(
                boardSets = listOf(
                    ObfBoardSet(
                        id = "core-words",
                        name = "Core words",
                        rootBoardId = "home",
                        boardIds = listOf("home", "people", "places"),
                        createdAt = 1L,
                        updatedAt = 2L,
                    ),
                    ObfBoardSet(
                        id = "school",
                        name = "School",
                        rootBoardId = "classroom",
                        boardIds = listOf("classroom", "subjects"),
                        isLocked = true,
                        createdAt = 1L,
                        updatedAt = 2L,
                    ),
                ),
                isLoading = false,
            ),
            status = null,
            importAvailable = true,
            onAction = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BoardSetLibraryScreen(
    boardSets: List<ObfBoardSet>,
    isLoading: Boolean,
    isCreating: Boolean,
    status: LibraryStatus?,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onCreate: () -> Unit,
    onImportSpeech: () -> Unit,
    onRetry: () -> Unit,
    onImport: (() -> Unit)?,
    onOpen: (ObfBoardSet) -> Unit,
    onEdit: (ObfBoardSet) -> Unit,
    onDuplicate: (ObfBoardSet) -> Unit,
    onToggleLock: (ObfBoardSet) -> Unit,
    onDelete: (ObfBoardSet) -> Unit
) {
    // A large window shows the library as one readable column with its actions above it,
    // instead of floating buttons stranded in a far corner.
    val large = currentWindowWidth().isLarge
    val actionsReady = !isLoading && !isCreating
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.board_sets_title), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.board_sets_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.Keyboard,
                            contentDescription = stringResource(R.string.mode_switch_to_keyboard)
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.phrase_screen_app_settings)
                        )
                    }
                    if (onImport != null) {
                        IconButton(onClick = onImport) {
                            Icon(
                                Icons.Default.ImportExport,
                                contentDescription = stringResource(R.string.board_sets_import)
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (actionsReady && !large) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
                    Button(onClick = onImportSpeech) { Text(stringResource(R.string.speech_import_title)) }
                    ExtendedFloatingActionButton(
                        onClick = onCreate,
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text(stringResource(R.string.board_sets_new)) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (large && actionsReady && boardSets.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .widthIn(max = BoardSetLibraryMaxWidth)
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    BoardSetLibraryActions(onImportSpeech = onImportSpeech, onCreate = onCreate)
                }
            }
            Box(Modifier.widthIn(max = if (large) BoardSetLibraryMaxWidth else Dp.Unspecified).fillMaxSize()) {
                when {
                    isCreating -> Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.board_sets_creating))
                    }
                    isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    boardSets.isEmpty() -> Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(stringResource(R.string.board_sets_empty_title), style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.board_sets_empty_body),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        status?.let {
                            Spacer(Modifier.height(12.dp))
                            LibraryStatusBanner(it)
                        }
                        Spacer(Modifier.height(16.dp))
                        if (large) {
                            // Without floating buttons, the empty library always offers both ways to
                            // start, also after a failure (e.g. a failed import or the last delete).
                            // The same buttons, in the same order, as above a list.
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if ((status as? LibraryStatus.Failed)?.canRetry == true) {
                                    TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 56.dp)) {
                                        Text(stringResource(R.string.common_retry))
                                    }
                                }
                                BoardSetLibraryActions(onImportSpeech = onImportSpeech, onCreate = onCreate)
                            }
                        } else if ((status as? LibraryStatus.Failed)?.canRetry == true) {
                            Button(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
                        } else {
                            Button(onClick = onCreate) { Text(stringResource(R.string.board_sets_create)) }
                        }
                    }
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        // Room for the floating buttons, which a large window does not have.
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = if (large) 24.dp else 152.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        status?.let {
                            item(key = "status") { LibraryStatusBanner(it) }
                        }
                        items(boardSets, key = { it.id }) { boardSet ->
                            BoardSetLibraryCard(
                                boardSet = boardSet,
                                onOpen = { onOpen(boardSet) },
                                onEdit = { onEdit(boardSet) },
                                onDuplicate = { onDuplicate(boardSet) },
                                onToggleLock = { onToggleLock(boardSet) },
                                onDelete = { onDelete(boardSet) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The library's last result. A failure is an error container with an icon, so it does not rely
 * on colour alone; both are announced politely.
 */
@Composable
internal fun LibraryStatusBanner(status: LibraryStatus) {
    val failed = status is LibraryStatus.Failed
    Surface(
        color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (failed) Icons.Default.ErrorOutline else Icons.Default.CheckCircle, contentDescription = null)
            Text(status.message.resolve(), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Import speech and New screen, as a large window shows them in both library states. */
@Composable
internal fun BoardSetLibraryActions(onImportSpeech: () -> Unit, onCreate: () -> Unit) {
    FilledTonalButton(onClick = onImportSpeech, modifier = Modifier.heightIn(min = 56.dp)) {
        Text(stringResource(R.string.speech_import_title))
    }
    Button(onClick = onCreate, modifier = Modifier.heightIn(min = 56.dp)) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.board_sets_new))
    }
}

/**
 * The edit top bar while a Button is selected, as Android shows a selection: what is selected,
 * and labelled actions for it. The non-gesture way to edit or remove a Button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectedButtonTopBar(label: String, onEdit: () -> Unit, onRemove: () -> Unit, onDeselect: () -> Unit) {
    TopAppBar(
        title = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onDeselect) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.board_selection_done))
            }
        },
        actions = {
            TextButton(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Default.Edit, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.board_cell_menu_edit))
            }
            TextButton(onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.board_cell_menu_remove), color = MaterialTheme.colorScheme.error)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    )
}

/** How wide the Screens library grows on a large window. */
internal val BoardSetLibraryMaxWidth = 840.dp

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun BoardSetLibraryCard(
    boardSet: ObfBoardSet,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onToggleLock: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val openLabel = stringResource(R.string.board_sets_open)
            ListItem(
                headlineContent = { Text(boardSet.name, fontWeight = FontWeight.SemiBold) },
                supportingContent = {
                    Text(pluralStringResource(R.plurals.board_sets_board_count, boardSet.boardIds.size, boardSet.boardIds.size))
                },
                leadingContent = {
                    Icon(
                        if (boardSet.isLocked) Icons.Default.Lock else Icons.Default.Home,
                        contentDescription = if (boardSet.isLocked) stringResource(R.string.board_sets_locked) else null
                    )
                },
                // The card supplies the background, so the row and its actions read as one surface.
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClickLabel = openLabel, onClick = onOpen)
                    .testTag("$BOARD_SET_OPEN_TEST_TAG_PREFIX${boardSet.id}"),
            )
            Row {
                IconButton(onClick = onEdit, enabled = !boardSet.isLocked) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.board_sets_edit))
                }
                IconButton(onClick = onDuplicate) {
                    Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.board_sets_duplicate))
                }
                IconButton(onClick = onToggleLock) {
                    Icon(
                        if (boardSet.isLocked) Icons.Default.LockOpen else Icons.Default.Lock,
                        contentDescription = stringResource(if (boardSet.isLocked) R.string.board_sets_unlock else R.string.board_sets_lock)
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
