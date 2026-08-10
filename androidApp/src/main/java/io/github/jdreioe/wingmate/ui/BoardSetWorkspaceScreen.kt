package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.ImportExport
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.application.BoardSetUseCase
import io.github.jdreioe.wingmate.application.ObzExportResult
import io.github.jdreioe.wingmate.application.BoardSetSpeechCacheUseCase
import io.github.jdreioe.wingmate.application.KeyboardPreset
import io.github.jdreioe.wingmate.infrastructure.BoardImportService
import io.github.jdreioe.wingmate.infrastructure.BoardImportResult
import io.github.jdreioe.wingmate.infrastructure.QuickCorePresetService
import io.github.jdreioe.wingmate.application.FeatureUsageEvents
import io.github.jdreioe.wingmate.application.reportEvent
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.application.EditingAccessController
import io.github.jdreioe.wingmate.application.SelectionHighlight
import io.github.jdreioe.wingmate.domain.Base64Decoder
import io.github.jdreioe.wingmate.domain.FileStorage
import io.github.jdreioe.wingmate.domain.SoundPlayer
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import io.github.jdreioe.wingmate.domain.TextPredictionService
import io.github.jdreioe.wingmate.domain.withLanguageOverride
import io.github.jdreioe.wingmate.domain.obf.BoardSetGraph
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfButtonActionEffect
import io.github.jdreioe.wingmate.domain.obf.ObfButtonType
import io.github.jdreioe.wingmate.domain.obf.ObfButtonShape
import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import io.github.jdreioe.wingmate.domain.obf.ObfImage
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
import io.github.jdreioe.wingmate.domain.obf.parseObfButtonActions
import io.github.jdreioe.wingmate.domain.obf.resolveObfLocalizedString
import io.github.jdreioe.wingmate.domain.obf.GridFieldSpan
import io.github.jdreioe.wingmate.domain.obf.CellTapResult
import io.github.jdreioe.wingmate.domain.obf.nGramPredictionInsertion
import io.github.jdreioe.wingmate.domain.obf.backspaceSentenceSelection
import io.github.jdreioe.wingmate.domain.obf.shouldAddBoardSelection
import io.github.jdreioe.wingmate.domain.obf.shouldSpeakBoardSelection
import io.github.jdreioe.wingmate.domain.obf.applyBoardReturnBehavior
import io.github.jdreioe.wingmate.domain.obf.buildResolvedSentence
import io.github.jdreioe.wingmate.domain.obf.orderedPredictionButtonIds
import io.github.jdreioe.wingmate.domain.obf.resolveCellTap
import io.github.jdreioe.wingmate.domain.obf.renameDraftBoardSet
import io.github.jdreioe.wingmate.domain.obf.renameDraftBoard
import io.github.jdreioe.wingmate.domain.obf.resizeDraftBoard
import io.github.jdreioe.wingmate.domain.obf.moveDraftField
import io.github.jdreioe.wingmate.domain.obf.resizeDraftField
import io.github.jdreioe.wingmate.domain.obf.withHomeFieldsBottomLeft
import io.github.jdreioe.wingmate.domain.obf.updateDraftCell
import io.github.jdreioe.wingmate.domain.obf.clearDraftCell
import io.github.jdreioe.wingmate.domain.obf.joinSentenceText
import io.github.jdreioe.wingmate.domain.obf.buttonSpeechPart
import io.github.jdreioe.wingmate.domain.obf.normalizedOrder
import io.github.jdreioe.wingmate.domain.obf.fieldSpanAt
import io.github.jdreioe.wingmate.domain.obf.fieldAnchorAt
import io.github.jdreioe.wingmate.domain.obf.availableFieldSpansAt
import io.github.jdreioe.wingmate.domain.obf.withFieldSpan
import io.github.jdreioe.wingmate.domain.obf.resized
import io.github.jdreioe.wingmate.domain.obf.moveOrSwapField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.koin.compose.koinInject
import kotlin.random.Random
import kotlin.time.Clock

import com.hojmoseit.wingmate.R
private enum class BoardWorkspaceMode { Run, Edit }

private sealed interface BoardSetRoute {
    data object Library : BoardSetRoute
    data class Workspace(
        val boardSetId: String,
        val mode: BoardWorkspaceMode
    ) : BoardSetRoute
}

internal data class BoardSetEditSession(
    val original: BoardSetGraph,
    val draft: BoardSetGraph,
    val undoStack: List<BoardSetGraph> = emptyList()
) {
    val isDirty: Boolean get() = draft != original

    fun apply(updated: BoardSetGraph): BoardSetEditSession {
        val normalized = updated.withHomeFieldsBottomLeft()
        if (normalized == draft) return this
        return copy(draft = normalized, undoStack = undoStack + draft)
    }

    fun undo(): BoardSetEditSession {
        val previous = undoStack.lastOrNull() ?: return this
        return copy(draft = previous, undoStack = undoStack.dropLast(1))
    }
}

private data class WorkspaceCellTarget(
    val row: Int,
    val column: Int,
    val button: ObfButton?
)

/**
 * Board-set entry point with a familiar library -> Run/Edit workspace flow.
 */
@Composable
fun BoardSetManagerScreen(
    onBack: () -> Unit,
    onBackToWelcome: () -> Unit,
    createOnLaunch: Boolean = false,
    initialBoardSetId: String? = null
) {
    val koin = org.koin.compose.getKoin()
    val useCase = koinInject<BoardSetUseCase>()
    val boardSetSpeechCache = koinInject<BoardSetSpeechCacheUseCase>()
    val boardImportService = remember(koin) { koin.getOrNull<BoardImportService>() }
    val quickCorePresetService = remember(koin) { koin.getOrNull<QuickCorePresetService>() }
    val quickCoreProgress by quickCorePresetService?.progress?.collectAsState()
        ?: remember { mutableStateOf(null) }
    val speechService = koinInject<SpeechService>()
    val voiceUseCase = koinInject<VoiceUseCase>()
    val featureUsageReporter = koinInject<io.github.jdreioe.wingmate.application.FeatureUsageReporter>()
    val updateService = remember(koin) { koin.getOrNull<io.github.jdreioe.wingmate.domain.UpdateService>() }
    val editingAccessController = remember(koin) { koin.getOrNull<EditingAccessController>() }
    val scope = rememberCoroutineScope()
    var route by remember { mutableStateOf<BoardSetRoute>(BoardSetRoute.Library) }
    var boardSets by remember { mutableStateOf<List<ObfBoardSet>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var isQuickCoreImporting by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showImportExport by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ObfBoardSet?>(null) }
    var pendingProtectedDelete by remember { mutableStateOf<ObfBoardSet?>(null) }
    var showDeleteAccessDialog by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val loadError = stringResource(R.string.board_sets_load_error)
    val duplicatedMessage = stringResource(R.string.board_sets_duplicated)
    val duplicateError = stringResource(R.string.board_sets_duplicate_error)
    val lockError = stringResource(R.string.board_sets_lock_error)
    val createError = stringResource(R.string.board_sets_create_error)
    val deletedMessage = stringResource(R.string.board_sets_deleted)
    val deleteError = stringResource(R.string.board_sets_delete_error)
    val importedMessage = stringResource(R.string.board_sets_imported)
    val importError = stringResource(R.string.board_sets_import_error)
    val defaultBoardName = stringResource(R.string.board_dialog_default_board_name)

    fun refreshBoardSets() {
        scope.launch {
            isLoading = true
            runCatching { useCase.listBoardSets() }
                .onSuccess { boardSets = it }
                .onFailure { statusMessage = it.message ?: loadError }
            isLoading = false
        }
    }

    fun deleteBoardSet(boardSet: ObfBoardSet) {
        scope.launch {
            runCatching { useCase.deleteBoardSet(boardSet.id) }
                .onSuccess {
                    statusMessage = deletedMessage
                    refreshBoardSets()
                }
                .onFailure { statusMessage = it.message ?: deleteError }
        }
    }

    LaunchedEffect(Unit) {
        refreshBoardSets()
        boardSetSpeechCache.cacheAll()
    }
    LaunchedEffect(initialBoardSetId) {
        val targetBoardSet = initialBoardSetId?.let { id ->
            withContext(Dispatchers.Default) { useCase.getBoardSet(id) }
        }
        if (targetBoardSet != null) {
            route = BoardSetRoute.Workspace(targetBoardSet.id, BoardWorkspaceMode.Run)
        }
    }
    LaunchedEffect(createOnLaunch) {
        if (createOnLaunch) showCreateDialog = true
    }

    when (val currentRoute = route) {
        BoardSetRoute.Library -> BoardSetLibraryScreen(
            boardSets = boardSets,
            isLoading = isLoading,
            statusMessage = statusMessage,
            onBack = onBack,
            onOpenSettings = { showSettings = true },
            onCreate = { showCreateDialog = true },
            onImport = boardImportService?.let { importService -> {
                scope.launch {
                    when (val result = importService.importBoardSetResult()) {
                        is BoardImportResult.Success -> {
                                val imported = result.boardSet
                                boardSetSpeechCache.cacheBoardSet(imported.id)
                                statusMessage = importedMessage
                                refreshBoardSets()
                                route = BoardSetRoute.Workspace(imported.id, BoardWorkspaceMode.Run)
                        }
                        BoardImportResult.Cancelled -> Unit
                        is BoardImportResult.Failure -> {
                            statusMessage = result.context.ifBlank { importError }
                        }
                    }
                }
            } },
            onOpen = { route = BoardSetRoute.Workspace(it.id, BoardWorkspaceMode.Run) },
            onEdit = { route = BoardSetRoute.Workspace(it.id, BoardWorkspaceMode.Edit) },
            onDuplicate = { boardSet ->
                scope.launch {
                    runCatching { useCase.duplicateBoardSet(boardSet.id) }
                        .onSuccess {
                            statusMessage = duplicatedMessage
                            refreshBoardSets()
                        }
                        .onFailure { statusMessage = it.message ?: duplicateError }
                }
            },
            onToggleLock = { boardSet ->
                scope.launch {
                    runCatching { useCase.toggleLocked(boardSet.id) }
                        .onSuccess { refreshBoardSets() }
                        .onFailure { statusMessage = it.message ?: lockError }
                }
            },
            onDelete = { deleteTarget = it }
        )

        is BoardSetRoute.Workspace -> BoardSetWorkspaceScreen(
            boardSetId = currentRoute.boardSetId,
            initialMode = currentRoute.mode,
            onSwitchToKeyboard = onBack,
            onExitToLibrary = {
                route = BoardSetRoute.Library
                refreshBoardSets()
            }
        )
    }

    if (showCreateDialog) {
        CreateBoardSetDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, rows, columns, template, keyboardPreset ->
                if (template != BoardSetTemplate.Blank && template.quickCoreSlug == null) {
                    showCreateDialog = false
                }
                scope.launch {
                    val quickCoreSlug = template.quickCoreSlug
                    if (quickCoreSlug != null) {
                        val service = quickCorePresetService
                        if (service == null) {
                            statusMessage = createError
                            return@launch
                        }
                        isQuickCoreImporting = true
                        when (val result = service.importPreset(quickCoreSlug)) {
                            is BoardImportResult.Success -> {
                                val created = useCase.renameBoardSet(result.boardSet.id, name.trim())
                                    ?: result.boardSet
                                showCreateDialog = false
                                refreshBoardSets()
                                route = BoardSetRoute.Workspace(created.id, BoardWorkspaceMode.Run)
                            }
                            is BoardImportResult.Failure -> statusMessage = result.context
                            BoardImportResult.Cancelled -> Unit
                        }
                        isQuickCoreImporting = false
                        return@launch
                    }
                    runCatching {
                        when (template) {
                            BoardSetTemplate.Blank -> useCase.createBoardSet(name.trim(), rows, columns, defaultBoardName)
                            BoardSetTemplate.Calculator -> useCase.createCalculatorBoardSet(name.trim())
                            BoardSetTemplate.Keyboard -> useCase.createKeyboardBoardSet(name.trim(), keyboardPreset)
                            else -> error("Quick Core presets are imported separately")
                        }
                    }
                        .onSuccess { created ->
                            showCreateDialog = false
                            when (template) {
                                BoardSetTemplate.Blank -> {
                                    refreshBoardSets()
                                    route = BoardSetRoute.Workspace(created.id, BoardWorkspaceMode.Edit)
                                }
                                BoardSetTemplate.Calculator -> {
                                    boardSets = (listOf(created) + boardSets)
                                        .distinctBy { it.id }
                                        .sortedByDescending { it.updatedAt }
                                }
                                BoardSetTemplate.Keyboard -> {
                                    boardSets = (listOf(created) + boardSets)
                                        .distinctBy { it.id }
                                        .sortedByDescending { it.updatedAt }
                                }
                                else -> Unit
                            }
                        }
                        .onFailure { statusMessage = it.message ?: createError }
                }
            },
            quickCoreProgress = quickCoreProgress?.fraction?.toFloat(),
            isQuickCoreImporting = isQuickCoreImporting,
        )
    }

    deleteTarget?.let { boardSet ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.board_sets_delete_title)) },
            text = { Text(stringResource(R.string.board_sets_delete_body, boardSet.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            if (editingAccessController?.requiresUnlock() == true) {
                                pendingProtectedDelete = boardSet
                                showDeleteAccessDialog = true
                            } else {
                                deleteBoardSet(boardSet)
                            }
                        }
                    }
                ) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (showDeleteAccessDialog && editingAccessController != null) {
        EditingAccessDialog(
            controller = editingAccessController,
            mode = EditingAccessDialogMode.Unlock,
            onDismiss = {
                showDeleteAccessDialog = false
                pendingProtectedDelete = null
            },
            onSuccess = {
                showDeleteAccessDialog = false
                pendingProtectedDelete?.let(::deleteBoardSet)
                pendingProtectedDelete = null
            }
        )
    }

    if (showSettings) {
        SettingsScreen(
            onDismiss = { showSettings = false },
            onBackToWelcome = onBackToWelcome
        )
    }
    if (showImportExport) {
        SettingsExportDialog(onDismiss = { showImportExport = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoardSetLibraryScreen(
    boardSets: List<ObfBoardSet>,
    isLoading: Boolean,
    statusMessage: String?,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onCreate: () -> Unit,
    onImport: (() -> Unit)?,
    onOpen: (ObfBoardSet) -> Unit,
    onEdit: (ObfBoardSet) -> Unit,
    onDuplicate: (ObfBoardSet) -> Unit,
    onToggleLock: (ObfBoardSet) -> Unit,
    onDelete: (ObfBoardSet) -> Unit
) {
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
            ExtendedFloatingActionButton(
                onClick = onCreate,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.board_sets_new)) }
            )
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when {
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
                    statusMessage?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onCreate) { Text(stringResource(R.string.board_sets_create)) }
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    statusMessage?.let {
                        item { Text(it, color = MaterialTheme.colorScheme.primary) }
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

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun BoardSetLibraryCard(
    boardSet: ObfBoardSet,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onToggleLock: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
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
            trailingContent = {
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
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoardSetWorkspaceScreen(
    boardSetId: String,
    initialMode: BoardWorkspaceMode,
    onSwitchToKeyboard: () -> Unit,
    onExitToLibrary: () -> Unit
) {
    val useCase = koinInject<BoardSetUseCase>()
    val speechService = koinInject<SpeechService>()
    val voiceUseCase = koinInject<VoiceUseCase>()
    val soundPlayer = koinInject<SoundPlayer>()
    val fileStorage = koinInject<FileStorage>()
    val saidTextRepository = koinInject<SaidTextRepository>()
    val settings by rememberReactiveSettings()
    val koin = org.koin.compose.getKoin()
    val predictionService = remember(koin) { koin.getOrNull<TextPredictionService>() }
    val dictionaryLoader = remember(koin) { koin.getOrNull<io.github.jdreioe.wingmate.infrastructure.DictionaryLoader>() }
    val mediaUrlLoader = remember(koin) { koin.getOrNull<ObfMediaUrlLoader>() }
    val shareService = remember(koin) { koin.getOrNull<io.github.jdreioe.wingmate.platform.ShareService>() }
    val editingAccessController = remember(koin) { koin.getOrNull<EditingAccessController>() }
    val scope = rememberCoroutineScope()
    var savedGraph by remember(boardSetId) { mutableStateOf<BoardSetGraph?>(null) }
    var editSession by remember(boardSetId) { mutableStateOf<BoardSetEditSession?>(null) }
    var mode by remember(boardSetId) { mutableStateOf(initialMode) }
    var selectedBoardId by remember(boardSetId) { mutableStateOf<String?>(null) }
    var boardStack by remember(boardSetId) { mutableStateOf<List<String>>(emptyList()) }
    var selectedButtons by remember(boardSetId) {
        mutableStateOf<List<Pair<ObfButton, ImageBitmap?>>>(emptyList())
    }
    // #120: time-bounded selection highlight.
    val selectionHighlight = remember(boardSetId) { SelectionHighlight() }
    var highlightedButtonId by remember(boardSetId) { mutableStateOf<String?>(null) }
    var highlightGeneration by remember(boardSetId) { mutableLongStateOf(0L) }
    var isLoading by remember(boardSetId) { mutableStateOf(true) }
    var statusMessage by remember(boardSetId) { mutableStateOf<String?>(null) }
    var nativeKeyboardDraft by remember(boardSetId) { mutableStateOf<String?>(null) }
    var showAddBoardDialog by remember { mutableStateOf(false) }
    var editingCell by remember { mutableStateOf<WorkspaceCellTarget?>(null) }
    var selectedField by remember(boardSetId) { mutableStateOf<Pair<Int, Int>?>(null) }
    var showFinishDialog by remember { mutableStateOf(false) }
    var showResizeBoardDialog by remember { mutableStateOf(false) }
    var showDeleteBoardDialog by remember { mutableStateOf(false) }
    var settingsTarget by remember(boardSetId) { mutableStateOf<BoardSettingsTarget?>(null) }
    var isExporting by remember(boardSetId) { mutableStateOf(false) }
    var isFullscreen by remember(boardSetId) { mutableStateOf(false) }
    val hiddenButtonsSession = remember(boardSetId) { HiddenButtonsSession() }
    val showHiddenButtons = hiddenButtonsSession.revealed
    var showEditingAccessDialog by remember(boardSetId) { mutableStateOf(false) }
    var appBarMenuExpanded by remember(boardSetId) { mutableStateOf(false) }
    val unlockToEditMessage = stringResource(R.string.board_workspace_unlock_to_edit)
    val saveErrorMessage = stringResource(R.string.board_workspace_save_error)
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

    // Board keyboards must initialize the local model themselves: unlike the phrase screen,
    // they may be the first communication surface a user opens.
    LaunchedEffect(predictionService, saidTextRepository, settings.primaryLanguage) {
        val service = predictionService ?: return@LaunchedEffect
        runCatching {
            val history = saidTextRepository.list()
            val nGramService = service as? io.github.jdreioe.wingmate.infrastructure.SimpleNGramPredictionService
            if (nGramService != null) {
                val dictionary = dictionaryLoader?.loadDictionary(settings.primaryLanguage).orEmpty()
                if (dictionary.isNotEmpty()) {
                    nGramService.setBaseLanguage(dictionary)
                    nGramService.train(history, clear = false)
                } else {
                    nGramService.train(history)
                }
            } else {
                service.train(history)
            }
        }
    }

    LaunchedEffect(boardSetId) {
        isLoading = true
        val loaded = withContext(Dispatchers.Default) { useCase.loadBoardSetGraph(boardSetId) }
        val normalized = loaded?.withHomeFieldsBottomLeft()
        savedGraph = normalized
        selectedBoardId = normalized?.boardSet?.rootBoardId
        if (normalized != null && initialMode == BoardWorkspaceMode.Edit && !normalized.boardSet.isLocked) {
            if (editingAccessController?.requiresUnlock() == true) {
                mode = BoardWorkspaceMode.Run
                showEditingAccessDialog = true
            } else {
                editSession = BoardSetEditSession(normalized, normalized)
            }
        } else if (normalized?.boardSet?.isLocked == true) {
            mode = BoardWorkspaceMode.Run
        }
        isLoading = false
    }

    // #120: expire the selection highlight after the configured duration. Re-activating a
    // target bumps the generation, restarting the timer so rapid selections never clear
    // the highlight early (no stale overlays).
    LaunchedEffect(highlightGeneration) {
        val id = highlightedButtonId
        val duration = settings.selectionHighlightMillis
        if (id != null && duration > 0) {
            delay(duration)
            val now = Clock.System.now().toEpochMilliseconds()
            if (selectionHighlight.highlightedTarget(now, duration) != id) {
                highlightedButtonId = null
            }
        }
    }

    fun markButtonSelected(buttonId: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        selectionHighlight.activate(buttonId, now)
        if (settings.selectionHighlightMillis > 0) {
            highlightedButtonId = buttonId
            highlightGeneration = selectionHighlight.generation
        }
    }

    val activeGraph = editSession?.draft ?: savedGraph
    val activeBoard = activeGraph?.boardsById?.get(selectedBoardId)
    val resolvedBoardSettings = remember(
        activeGraph?.boardSet?.screenSettings,
        activeBoard?.extensions,
        settings.showLabels,
        settings.showSymbols,
        settings.labelAtTop,
        settings.boardShowMessageBar,
        settings.boardActivationBehavior,
        settings.boardReturnBehavior
    ) {
        resolveBoardSettings(
            appShowLabels = settings.showLabels,
            appShowSymbols = settings.showSymbols,
            appLabelAtTop = settings.labelAtTop,
            appShowMessageBar = settings.boardShowMessageBar,
            appActivationBehavior = settings.boardActivationBehavior,
            appReturnBehavior = settings.boardReturnBehavior,
            screen = activeGraph?.boardSet?.screenSettings
                ?: io.github.jdreioe.wingmate.domain.obf.BoardSettingsOverrides(),
            page = activeBoard?.pageSettingsOverrides()
                ?: io.github.jdreioe.wingmate.domain.obf.BoardSettingsOverrides()
        )
    }
    val sentenceText = remember(selectedButtons, activeBoard?.strings, settings.primaryLanguage) {
        buildResolvedSentence(
            buttons = selectedButtons.map { it.first },
            strings = activeBoard?.strings.orEmpty(),
            spellingMode = activeBoard?.spellingMode == true,
            primaryLanguage = settings.primaryLanguage
        )
    }
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
    val boardHasSpeakField = availableBoardActions.any { it == ObfButtonActionEffect.Speak }
    val boardHasDeleteField = availableBoardActions.any { it == ObfButtonActionEffect.Backspace }
    val boardHasClearField = availableBoardActions.any { it == ObfButtonActionEffect.Clear }
    val predictionButtonIds = remember(activeBoard?.id, activeBoard?.grid, activeBoard?.buttons, showHiddenButtons) {
        orderedPredictionButtonIds(activeBoard, showHiddenButtons)
    }
    var predictionsById by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(sentenceText, predictionButtonIds, predictionService) {
        predictionsById = emptyMap()
        val service = predictionService?.takeIf { it.isTrained() } ?: return@LaunchedEffect
        if (predictionButtonIds.isEmpty()) return@LaunchedEffect
        val result = service.predict(sentenceText, maxWords = predictionButtonIds.size, maxLetters = 0)
        predictionsById = predictionButtonIds.withIndex().mapNotNull { (index, id) ->
            result.words.getOrNull(index)?.let { id to it }
        }.toMap()
    }

    fun enterEditing() {
        val graph = savedGraph ?: return
        if (graph.boardSet.isLocked) {
            statusMessage = unlockToEditMessage
            return
        }
        selectedButtons = emptyList()
        boardStack = emptyList()
        selectedField = null
        editSession = BoardSetEditSession(graph, graph)
        mode = BoardWorkspaceMode.Edit
    }

    fun startEditing() {
        scope.launch {
            if (editingAccessController?.requiresUnlock() == true) {
                showEditingAccessDialog = true
            } else {
                enterEditing()
            }
        }
    }

    fun requestFinishEditing() {
        val session = editSession ?: return
        if (session.isDirty) showFinishDialog = true
        else {
            selectedField = null
            editSession = null
            mode = BoardWorkspaceMode.Run
        }
    }

    fun navigateBack() {
        if (mode == BoardWorkspaceMode.Edit) {
            requestFinishEditing()
        } else if (boardStack.isNotEmpty()) {
            selectedBoardId = boardStack.last()
            boardStack = boardStack.dropLast(1)
        } else {
            hiddenButtonsSession.reset()
            onExitToLibrary()
        }
    }

    fun exportBoardSet() {
        scope.launch {
            isExporting = true
            statusMessage = null
            try {
                val graph = activeGraph
                if (graph == null) {
                    statusMessage = "Export failed: no board set loaded"
                } else {
                    when (val export = useCase.exportBoardSetAsObzResult(graph.boardSet.id)) {
                        is ObzExportResult.Success -> {
                            val obzBytes = export.bytes
                            val fileName = "${graph.boardSet.name}.obz"
                            if (shareService != null) {
                                val shared = shareService.shareFile(fileName, obzBytes)
                                statusMessage = if (shared) {
                                    "Exported ${graph.boardSet.name}.obz"
                                } else {
                                    "Export cancelled"
                                }
                            } else {
                                statusMessage = "Export saved (${obzBytes.size} bytes)"
                            }
                        }
                        is ObzExportResult.Failure -> {
                            val resources = export.resources
                                .takeIf { it.isNotEmpty() }
                                ?.joinToString(prefix = ": ")
                                .orEmpty()
                            statusMessage = "Export failed: ${export.context}$resources"
                    }
                    }
                }
            } catch (e: Exception) {
                statusMessage = "Export failed: ${e.message ?: "unknown error"}"
            } finally {
                isExporting = false
            }
        }
    }

    nativeKeyboardDraft?.let { currentDraft ->
        AlertDialog(
            onDismissRequest = { nativeKeyboardDraft = null },
            title = { Text(stringResource(R.string.board_native_keyboard_title)) },
            text = {
                OutlinedTextField(
                    value = currentDraft,
                    onValueChange = { nativeKeyboardDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.board_native_keyboard_hint)) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    selectedButtons = currentDraft.takeIf { it.isNotEmpty() }
                        ?.let { text ->
                            listOf(
                                ObfButton(
                                    id = workspaceId("native-keyboard"),
                                    label = text,
                                    vocalization = text,
                                ) to null
                            )
                        }
                        .orEmpty()
                    nativeKeyboardDraft = null
                }) {
                    Text(stringResource(R.string.board_native_keyboard_return))
                }
            },
            dismissButton = {
                TextButton(onClick = { nativeKeyboardDraft = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
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
            screenSettings = activeGraph.boardSet.screenSettings,
            pageSettings = activeBoard.pageSettingsOverrides(),
            appShowLabels = settings.showLabels,
            appShowSymbols = settings.showSymbols,
            appLabelAtTop = settings.labelAtTop,
            appShowMessageBar = settings.boardShowMessageBar,
            appActivationBehavior = settings.boardActivationBehavior,
            appReturnBehavior = settings.boardReturnBehavior,
            onCommit = { name, updatedSettings, updatedBackgroundColor ->
                val session = editSession ?: return@BoardSettingsScreen
                val updated = if (openSettingsTarget == BoardSettingsTarget.Screen) {
                    session.draft.copy(
                        boardSet = session.draft.boardSet.copy(
                            name = name,
                            screenSettings = updatedSettings
                        )
                    )
                } else {
                    session.draft.copy(
                        boards = session.draft.boards.map { board ->
                            if (board.id == activeBoard.id) {
                                board.copy(name = name, backgroundColor = updatedBackgroundColor)
                                    .withPageSettingsOverrides(updatedSettings)
                            } else {
                                board
                            }
                        }
                    )
                }
                editSession = session.apply(updated)
            },
            onBack = { settingsTarget = null }
        )
        return
    }

    PlatformBackHandler(enabled = true, onBack = ::navigateBack)
    PlatformBackgroundEffect { editingAccessController?.lock() }

    Scaffold(
        topBar = {
            if (!isFullscreen) BoxWithConstraints(Modifier.fillMaxWidth()) {
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
                                activeBoard?.name ?: stringResource(R.string.board_workspace_board_fallback)
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
                    IconButton(onClick = ::navigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
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
                                        text = { Text(stringResource(R.string.board_workspace_undo)) },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null) },
                                        enabled = editSession?.undoStack?.isNotEmpty() == true,
                                        onClick = {
                                            appBarMenuExpanded = false
                                            editSession = editSession?.undo()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.board_workspace_finish)) },
                                        onClick = {
                                            appBarMenuExpanded = false
                                            requestFinishEditing()
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
                                                editSession = session.apply(setDraftRoot(session.draft, board.id))
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
                                            isFullscreen = true
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
                                            exportBoardSet()
                                        }
                                    )
                                    if (selectedBoardId != activeGraph?.boardSet?.rootBoardId) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.board_workspace_home)) },
                                            leadingIcon = { Icon(Icons.Default.Home, contentDescription = null) },
                                            onClick = {
                                                appBarMenuExpanded = false
                                                selectedBoardId = activeGraph?.boardSet?.rootBoardId
                                                boardStack = emptyList()
                                            }
                                        )
                                    }
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
                            onClick = { editSession = editSession?.undo() },
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
                                    editSession = session.apply(setDraftRoot(session.draft, board.id))
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
                        IconButton(onClick = { isFullscreen = true }) {
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
                            onClick = ::exportBoardSet,
                            enabled = !isExporting
                        ) {
                            Icon(
                                Icons.Default.ImportExport,
                                contentDescription = stringResource(R.string.phrase_screen_import_export_data)
                            )
                        }
                        if (selectedBoardId != activeGraph?.boardSet?.rootBoardId) {
                            IconButton(onClick = {
                                selectedBoardId = activeGraph?.boardSet?.rootBoardId
                                boardStack = emptyList()
                            }) {
                                Icon(Icons.Default.Home, contentDescription = stringResource(R.string.board_workspace_home))
                            }
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
            when {
                isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                activeGraph == null || activeBoard == null -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(R.string.board_workspace_load_error))
                    TextButton(onClick = onExitToLibrary) {
                        Text(stringResource(R.string.board_workspace_back_to_library))
                    }
                }
                else -> Column(Modifier.fillMaxSize()) {
                    if (!isFullscreen) statusMessage?.let {
                        Text(
                            it,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (!isFullscreen && mode == BoardWorkspaceMode.Edit) {
                        BoardStrip(
                            boards = activeGraph.boards,
                            selectedBoardId = selectedBoardId,
                            onSelect = {
                                selectedBoardId = it
                                boardStack = emptyList()
                                selectedButtons = emptyList()
                                selectedField = null
                            }
                        )
                    }
                    ObfBoardView(
                        board = activeBoard,
                        isEditMode = mode == BoardWorkspaceMode.Edit,
                        showMessageBar = mode == BoardWorkspaceMode.Run &&
                            resolvedBoardSettings.showMessageBar,
                        sentenceText = sentenceText,
                        symbolBarPresentation = if (isFullscreen) {
                            SymbolBarPresentation.Fullscreen
                        } else {
                            SymbolBarPresentation.Normal
                        },
                        showSpeakControl = !boardHasSpeakField,
                        showDeleteControl = !boardHasDeleteField,
                        showClearControl = !boardHasClearField,
                        boardSettings = resolvedBoardSettings,
                        showHiddenButtons = showHiddenButtons,
                        selectedButtons = selectedButtons,
                        highlightedButtonId = highlightedButtonId,
                        predictionLabels = predictionsById,
                        onButtonClick = { button ->
                            run {
                                // #118: debounce gating now lives in ObfButtonItem, which only
                                // dispatches accepted activations.
                                markButtonSelected(button.id)
                                val actions = parseObfButtonActions(button)
                            if (actions.isNotEmpty()) {
                                var speakAfterActions = false
                                var navigateHome = false
                                var nextSelection = selectedButtons
                                var selectionToSpeak: List<Pair<ObfButton, ImageBitmap?>> = emptyList()
                                for (effect in actions) {
                                    when (effect) {
                                        is ObfButtonActionEffect.AppendText -> {
                                            if (effect.text.isNotEmpty()) {
                                                nextSelection = nextSelection + (
                                                    ObfButton(
                                                        id = workspaceId("action"),
                                                        label = effect.text,
                                                        vocalization = effect.text,
                                                        locale = button.locale
                                                    ).withMathMode(button.mathMode) to null
                                                )
                                            }
                                        }
                                        ObfButtonActionEffect.Backspace -> {
                                            nextSelection = backspaceSentenceSelection(
                                                nextSelection,
                                                spellingMode = activeBoard.spellingMode
                                            )
                                        }
                                        ObfButtonActionEffect.Clear -> {
                                            nextSelection = emptyList()
                                        }
                                        ObfButtonActionEffect.Speak -> {
                                            speakAfterActions = true
                                            selectionToSpeak = nextSelection
                                        }
                                        ObfButtonActionEffect.Home -> {
                                            navigateHome = true
                                        }
                                        ObfButtonActionEffect.NativeKeyboard -> {
                                            nativeKeyboardDraft = sentenceText
                                        }
                                        ObfButtonActionEffect.Predictions -> {
                                            val insertion = predictionButtonIds
                                                .indexOf(button.id)
                                                .takeIf { it != -1 }
                                                ?.let { index -> predictionsById[button.id] }
                                                ?.let { nGramPredictionInsertion(sentenceText, it) }
                                            if (!insertion.isNullOrEmpty()) {
                                                nextSelection = nextSelection + (
                                                    ObfButton(
                                                        id = workspaceId("prediction"),
                                                        label = insertion,
                                                        vocalization = insertion,
                                                        locale = button.locale
                                                    ) to null
                                                )
                                            }
                                        }
                                        is ObfButtonActionEffect.Unsupported -> {
                                            statusMessage = unsupportedActionTemplate.replace("%ACTION%", effect.action)
                                        }
                                    }
                                }
                                selectedButtons = nextSelection
                                if (navigateHome) {
                                    boardStack = emptyList()
                                    selectedBoardId = activeGraph.boardSet.rootBoardId
                                }
                                if (speakAfterActions) {
                                    speakSelectedButtons(
                                        selected = selectionToSpeak,
                                        board = activeBoard,
                                        primaryLanguage = settings.primaryLanguage,
                                        voiceUseCase = voiceUseCase,
                                        speechService = speechService,
                                        cacheWholeSentence = activeGraph.boardSet.cacheWholeSentences,
                                        scope = scope
                                    )
                                }
                            } else {
                                val linkedBoard = activeGraph.resolveLinkedBoard(button.loadBoard)
                                if (linkedBoard != null) {
                                    selectedBoardId?.let { boardStack = boardStack + it }
                                    selectedBoardId = linkedBoard.id
                                } else {
                                    val resolved = resolveObfLocalizedString(
                                        activeBoard.strings,
                                        settings.primaryLanguage,
                                        button.vocalization ?: button.label
                                    )
                                    val spokenText = resolved?.trim().orEmpty()
                                    if (
                                        spokenText.isNotEmpty() &&
                                        shouldAddBoardSelection(resolvedBoardSettings.activationBehavior)
                                    ) {
                                        selectedButtons = selectedButtons + (button to null)
                                    }
                                    val sound = button.soundId?.let { id ->
                                        activeBoard.sounds.firstOrNull { it.id == id }
                                    }
                                    if (shouldSpeakBoardSelection(resolvedBoardSettings.activationBehavior)) {
                                        scope.launch(Dispatchers.IO) {
                                            val recordedPath = sound?.path?.takeIf {
                                                it.isNotBlank() && sound.data.isNullOrBlank() && sound.dataUrl.isNullOrBlank()
                                            }
                                            val playedRecording = recordedPath?.let { path ->
                                                runCatching {
                                                    speechService.speakRecordedAudio(
                                                        audioFilePath = path,
                                                        textForHistory = spokenText
                                                    )
                                                }.getOrDefault(false)
                                            } ?: false
                                            val playedSound = playedRecording || playButtonSound(
                                                sound = sound,
                                                fileStorage = fileStorage,
                                                soundPlayer = soundPlayer,
                                                urlLoader = mediaUrlLoader
                                            )
                                            if (!playedSound && spokenText.isNotEmpty()) {
                                                runCatching {
                                                    val voice = voiceUseCase.selected()
                                                        .withLanguageOverride(button.locale ?: settings.primaryLanguage)
                                                        ?.copy(mathMode = button.mathMode)
                                                    speechService.speak(spokenText, voice, voice?.pitch, voice?.rate)
                                                }
                                            }
                                        }
                                    }
                                    if (spokenText.isNotEmpty() || sound != null) {
                                        val returned = applyBoardReturnBehavior(
                                            behavior = resolvedBoardSettings.returnBehavior,
                                            currentBoardId = selectedBoardId,
                                            boardStack = boardStack,
                                            rootBoardId = activeGraph.boardSet.rootBoardId
                                        )
                                        selectedBoardId = returned.first
                                        boardStack = returned.second
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
                                        selectedField = result.button?.let {
                                            activeBoard.grid?.fieldAnchorAt(result.row, result.column)
                                        }
                                        editingCell = WorkspaceCellTarget(result.row, result.column, result.button)
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
                                    editSession = session.apply(
                                        moveDraftField(
                                            session.draft,
                                            boardId,
                                            fromRow,
                                            fromColumn,
                                            toRow,
                                            toColumn
                                        )
                                    )
                                }
                            }
                        } else null,
                        selectedFieldAnchor = selectedField,
                        selectedFieldSpans = remember(activeBoard?.grid, selectedField) {
                            selectedField?.let { (row, column) ->
                                activeBoard?.grid?.availableFieldSpansAt(row, column).orEmpty()
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
                                    editSession = session.apply(resized)
                                }
                            }
                        } else null,
                        onGridHeightFractionChange = if (mode == BoardWorkspaceMode.Edit) {
                            { fraction ->
                                val session = editSession ?: return@ObfBoardView
                                val boardId = activeBoard.id
                                editSession = session.apply(
                                    session.draft.copy(
                                        boards = session.draft.boards.map { board ->
                                            if (board.id == boardId) board.withGridHeightFraction(fraction) else board
                                        }
                                    )
                                )
                            }
                        } else null,
                        homeBoardId = activeGraph.boardSet.rootBoardId,
                        onSpeakSentence = {
                            speakSelectedButtons(
                                selected = selectedButtons,
                                board = activeBoard,
                                primaryLanguage = settings.primaryLanguage,
                                voiceUseCase = voiceUseCase,
                                speechService = speechService,
                                cacheWholeSentence = activeGraph.boardSet.cacheWholeSentences,
                                scope = scope
                            )
                        },
                        onDeleteLast = {
                            if (selectedButtons.isNotEmpty()) selectedButtons = selectedButtons.dropLast(1)
                        },
                        onClearSentence = { selectedButtons = emptyList() },
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                }
            }
            if (isFullscreen && mode == BoardWorkspaceMode.Run && activeGraph != null && activeBoard != null) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    tonalElevation = 4.dp
                ) {
                    IconButton(onClick = { isFullscreen = false }) {
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
                editSession = session.apply(updated)
                selectedBoardId = updated.boards.last().id
                showAddBoardDialog = false
            }
        )
    }

    if (showEditingAccessDialog && editingAccessController != null) {
        EditingAccessDialog(
            controller = editingAccessController,
            mode = EditingAccessDialogMode.Unlock,
            onDismiss = { showEditingAccessDialog = false },
            onSuccess = {
                showEditingAccessDialog = false
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
            isKeyboardBoard = activeBoard.isKeyboard,
            showMathMode = supportsMathMode(settings.ttsEngine),
            availableBoards = activeGraph.boards.filterNot { it.id == activeBoard.id },
            initialLinkedBoardId = activeGraph.resolveLinkedBoard(target.button?.loadBoard)?.id,
            initialAction = target.button?.action,
            initialActions = target.button?.actions.orEmpty(),
            hasExistingValue = target.button != null,
            onDismiss = { editingCell = null },
            onSave = { label, vocalization, imageUrl, recordingPath, backgroundColor, language, mathMode, hidden, linkedBoardId,
                       action, actions, shape ->
                val session = editSession ?: return@EditBoardCellDialog
                editSession = session.apply(
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
                        shape = shape
                    )
                )
                editingCell = null
            },
            onClearCell = {
                val session = editSession ?: return@EditBoardCellDialog
                editSession = session.apply(
                    clearDraftCell(session.draft, activeBoard.id, target.row, target.column)
                )
                editingCell = null
            }
        )
    }

    if (showFinishDialog) {
        AlertDialog(
            onDismissRequest = { showFinishDialog = false },
            title = { Text(stringResource(R.string.board_workspace_finish_title)) },
            text = { Text(stringResource(R.string.board_workspace_finish_body)) },
            confirmButton = {
                Button(onClick = {
                    val session = editSession ?: return@Button
                    showFinishDialog = false
                    selectedField = null
                    editSession = null
                    mode = BoardWorkspaceMode.Run
                    val graph = session.draft
                    // Persist on an application-scoped scope so leaving this screen can't
                    // cancel the write halfway; branch back to the main thread for state updates.
                    val appScope = koin.get<CoroutineScope>()
                    appScope.launch(Dispatchers.Main) {
                        withContext(Dispatchers.Default) {
                            useCase.saveBoardSetGraph(graph)
                        }.onSuccess { saved ->
                            savedGraph = saved
                        }.onFailure { error ->
                            // Persistence failed: drop back into editing with the draft intact
                            // so the user does not lose their work.
                            mode = BoardWorkspaceMode.Edit
                            editSession = session
                            statusMessage = error.message ?: saveErrorMessage
                        }
                    }
                }) { Text(stringResource(R.string.board_workspace_save_changes)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showFinishDialog = false
                        selectedField = null
                        editSession = null
                        mode = BoardWorkspaceMode.Run
                        selectedBoardId = savedGraph?.boardSet?.rootBoardId
                    }) { Text(stringResource(R.string.board_workspace_discard)) }
                    TextButton(onClick = { showFinishDialog = false }) {
                        Text(stringResource(R.string.board_workspace_keep_editing))
                    }
                }
            }
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
                    editSession = session.apply(resized)
                    showResizeBoardDialog = false
                    true
                }
            }
        )
    }

    if (showDeleteBoardDialog && activeBoard != null) {
        AlertDialog(
            onDismissRequest = { showDeleteBoardDialog = false },
            title = { Text(stringResource(R.string.board_workspace_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.board_workspace_delete_body,
                        activeBoard.name ?: stringResource(R.string.board_workspace_board_fallback)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val session = editSession
                    if (session != null) {
                        val updated = deleteDraftBoard(session.draft, activeBoard.id)
                        editSession = session.apply(updated)
                        selectedBoardId = updated.boardSet.rootBoardId
                    }
                    showDeleteBoardDialog = false
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteBoardDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun ResizeBoardDialog(
    currentRows: Int,
    currentColumns: Int,
    onDismiss: () -> Unit,
    onResize: (rows: Int, columns: Int) -> Boolean
) {
    var rowsText by remember(currentRows) { mutableStateOf(currentRows.toString()) }
    var columnsText by remember(currentColumns) { mutableStateOf(currentColumns.toString()) }
    var blocked by remember { mutableStateOf(false) }
    val rows = rowsText.toIntOrNull()
    val columns = columnsText.toIntOrNull()
    val dimensionsValid = rows in 1..20 && columns in 1..20
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.board_workspace_resize)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.board_workspace_resize_body))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = rowsText,
                        onValueChange = { rowsText = it.filter(Char::isDigit); blocked = false },
                        label = { Text(stringResource(R.string.board_dialog_rows)) },
                        isError = rowsText.isNotEmpty() && rows !in 1..20,
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = columnsText,
                        onValueChange = { columnsText = it.filter(Char::isDigit); blocked = false },
                        label = { Text(stringResource(R.string.board_dialog_columns)) },
                        isError = columnsText.isNotEmpty() && columns !in 1..20,
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                if (blocked) {
                    Text(
                        stringResource(R.string.board_workspace_resize_blocked),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    blocked = !onResize(requireNotNull(rows), requireNotNull(columns))
                },
                enabled = dimensionsValid && (rows != currentRows || columns != currentColumns)
            ) { Text(stringResource(R.string.board_workspace_resize_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
private fun BoardStrip(
    boards: List<ObfBoard>,
    selectedBoardId: String?,
    onSelect: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val fallbackName = stringResource(R.string.board_workspace_board_fallback)
    LaunchedEffect(boards, selectedBoardId) {
        val index = boards.indexOfFirst { it.id == selectedBoardId }
        if (index >= 0) listState.animateScrollToItem(index)
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(boards, key = { it.id }) { board ->
            androidx.compose.material3.FilterChip(
                selected = board.id == selectedBoardId,
                onClick = { onSelect(board.id) },
                modifier = Modifier.semantics {
                    contentDescription = board.name ?: fallbackName
                },
                label = {
                    Text(
                        board.name ?: fallbackName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    }
}

private fun addDraftBoard(
    graph: BoardSetGraph,
    name: String,
    rows: Int,
    columns: Int,
    keyboardLayout: ObfKeyboardLayout? = null
): BoardSetGraph {
    val boardId = workspaceId("board")
    val safeRows = rows.coerceAtLeast(1)
    val safeColumns = columns.coerceAtLeast(1)
    var board = ObfBoard(
        format = "open-board-0.1",
        id = boardId,
        name = name.trim(),
        grid = ObfGrid(
            rows = safeRows,
            columns = safeColumns,
            order = List(safeRows) { List(safeColumns) { null } }
        )
    )
    if (keyboardLayout != null) {
        board = board
            .withCompactGrid(true)
            .withSpellingMode(true)
            .withKeyboardLayout(keyboardLayout)
    }
    return graph.copy(
        boardSet = graph.boardSet.copy(boardIds = graph.boardSet.boardIds + boardId),
        boards = graph.boards + board
    )
}


private fun setDraftRoot(graph: BoardSetGraph, boardId: String): BoardSetGraph {
    if (boardId !in graph.boardSet.boardIds) return graph
    return graph.copy(boardSet = graph.boardSet.copy(rootBoardId = boardId))
}

private fun deleteDraftBoard(graph: BoardSetGraph, boardId: String): BoardSetGraph {
    if (graph.boards.size <= 1 || boardId !in graph.boardSet.boardIds) return graph
    val remainingBoards = graph.boards.filterNot { it.id == boardId }.map { board ->
        board.copy(
            buttons = board.buttons.map { button ->
                if (button.loadBoard?.id == boardId) button.copy(loadBoard = null) else button
            }
        )
    }
    val remainingIds = graph.boardSet.boardIds.filterNot { it == boardId }
    val rootId = if (graph.boardSet.rootBoardId == boardId) remainingIds.first() else graph.boardSet.rootBoardId
    return graph.copy(
        boardSet = graph.boardSet.copy(rootBoardId = rootId, boardIds = remainingIds),
        boards = remainingBoards
    )
}

private fun workspaceId(prefix: String): String {
    return "${prefix}_${Clock.System.now().toEpochMilliseconds()}_${Random.nextInt(1000, 9999)}"
}

internal fun backspaceSentenceSelection(
    selected: List<Pair<ObfButton, ImageBitmap?>>,
    spellingMode: Boolean = false
): List<Pair<ObfButton, ImageBitmap?>> {
    if (selected.isEmpty()) return selected
    val texts = selected.map { (button, _) -> button.vocalization ?: button.label ?: "" }
    val trimmed = backspaceSentenceSelection(texts, spellingMode)
    if (trimmed.size < texts.size) return selected.dropLast(1)
    val lastButton = selected.last().first
    val lastText = trimmed.last()
    return selected.dropLast(1) + (
        lastButton.copy(label = lastText, vocalization = lastText) to selected.last().second
    )
}

private fun speakSelectedButtons(
    selected: List<Pair<ObfButton, ImageBitmap?>>,
    board: ObfBoard,
    primaryLanguage: String,
    voiceUseCase: VoiceUseCase,
    speechService: SpeechService,
    cacheWholeSentence: Boolean,
    scope: kotlinx.coroutines.CoroutineScope
) {
    data class PlaybackPart(
        val text: String,
        val language: String?,
        val recordingPath: String?,
        val mathMode: Boolean
    )

    val speechParts = selected.mapNotNull { (button, _) ->
        board.buttonSpeechPart(button, primaryLanguage)?.let {
            PlaybackPart(
                text = it.text,
                language = it.language,
                recordingPath = it.recordingPath,
                mathMode = it.mathMode
            )
        }
    }
    if (speechParts.isEmpty()) return
    scope.launch(Dispatchers.IO) {
        runCatching {
            val voice = voiceUseCase.selected()
            val pendingTts = mutableListOf<PlaybackPart>()

            suspend fun speakPendingTts() {
                if (pendingTts.isEmpty()) return
                val texts = pendingTts.map { it.text }
                val sentence = joinSentenceText(texts, board.spellingMode)
                val pendingVoice = voice
                    .withLanguageOverride(pendingTts.first().language ?: primaryLanguage)
                    ?.copy(mathMode = pendingTts.first().mathMode)
                if (!pendingTts.first().mathMode && pendingTts.any { !it.language.isNullOrBlank() }) {
                    val segments = pendingTts.map {
                        SpeechSegment(text = it.text, languageTag = it.language)
                    }
                    speechService.speakSegmentsWithCachePolicy(
                        segments,
                        pendingVoice,
                        pendingVoice?.pitch,
                        pendingVoice?.rate,
                        cacheAudio = cacheWholeSentence
                    )
                } else {
                    speechService.speakWithCachePolicy(
                        sentence,
                        pendingVoice,
                        pendingVoice?.pitch,
                        pendingVoice?.rate,
                        cacheAudio = cacheWholeSentence
                    )
                }
                pendingTts.clear()
                awaitSpeechPlayback(speechService)
            }

            for (part in speechParts) {
                if (pendingTts.isNotEmpty() && pendingTts.first().mathMode != part.mathMode) {
                    speakPendingTts()
                }
                val recordingPath = part.recordingPath
                if (recordingPath == null) {
                    pendingTts += part
                    continue
                }
                speakPendingTts()
                val played = speechService.speakRecordedAudio(
                    audioFilePath = recordingPath,
                    textForHistory = part.text,
                    voice = voice
                )
                if (!played) {
                    pendingTts += part.copy(recordingPath = null)
                }
            }
            speakPendingTts()
        }
    }
}

private suspend fun awaitSpeechPlayback(speechService: SpeechService) {
    withTimeoutOrNull(120_000) {
        while (speechService.isPlaying()) {
            delay(20)
        }
    }
}

/**
 * Plays an OBF button sound from storage or inline data. Returns true when playback started.
 */
internal suspend fun playButtonSound(
    sound: ObfSound?,
    fileStorage: FileStorage,
    soundPlayer: SoundPlayer,
    urlLoader: ObfMediaUrlLoader? = null
): Boolean {
    if (sound == null) return false
    for (source in obfSoundSources(sound)) {
        val bytes = when (source) {
            is ObfMediaSource.Data -> decodeObfDataUri(source.value)
            is ObfMediaSource.Path -> fileStorage.loadBytes(source.value)
            is ObfMediaSource.Url -> urlLoader?.load(source.value)
            is ObfMediaSource.Symbol -> null
        }
        if (bytes != null && bytes.isNotEmpty() && soundPlayer.playBytes(bytes, sound.contentType)) return true
    }
    return false
}

private fun decodeObfDataUri(data: String): ByteArray? {
    return Base64Decoder.decodeOrNull(data.substringAfter("base64,", data))
}

private fun languageName(
    languageTag: String,
    englishName: String,
    danishName: String,
    fallbackName: String
): String = when (languageTag.substringBefore('-').lowercase()) {
    "en" -> englishName
    "da" -> danishName
    else -> fallbackName
}
