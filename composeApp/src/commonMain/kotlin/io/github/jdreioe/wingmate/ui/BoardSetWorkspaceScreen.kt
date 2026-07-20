package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.MenuBook
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
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Undo
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
import androidx.compose.runtime.getValue
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
import io.github.jdreioe.wingmate.application.FeatureUsageEvents
import io.github.jdreioe.wingmate.application.reportEvent
import io.github.jdreioe.wingmate.application.PhraseUseCase
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.withLanguageOverride
import io.github.jdreioe.wingmate.domain.obf.BoardSetGraph
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import io.github.jdreioe.wingmate.domain.obf.ObfLoadBoard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import wingmatekmp.composeapp.generated.resources.*
import kotlin.random.Random
import kotlin.time.Clock

private enum class BoardWorkspaceMode { Run, Edit }

private sealed interface BoardSetRoute {
    data object Library : BoardSetRoute
    data class Workspace(
        val boardSetId: String,
        val mode: BoardWorkspaceMode
    ) : BoardSetRoute
}

private data class BoardSetEditSession(
    val original: BoardSetGraph,
    val draft: BoardSetGraph,
    val undoStack: List<BoardSetGraph> = emptyList()
) {
    val isDirty: Boolean get() = draft != original

    fun apply(updated: BoardSetGraph): BoardSetEditSession {
        if (updated == draft) return this
        return copy(draft = updated, undoStack = undoStack + draft)
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

internal data class GridFieldSpan(val rows: Int, val columns: Int)

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
    val useCase = koinInject<BoardSetUseCase>()
    val saidTextRepository = koinInject<io.github.jdreioe.wingmate.domain.SaidTextRepository>()
    val dictionaryRepository = koinInject<io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository>()
    val speechService = koinInject<SpeechService>()
    val voiceUseCase = koinInject<VoiceUseCase>()
    val koin = org.koin.compose.getKoin()
    val featureUsageReporter = koinInject<io.github.jdreioe.wingmate.application.FeatureUsageReporter>()
    val updateService = remember(koin) { koin.getOrNull<io.github.jdreioe.wingmate.domain.UpdateService>() }
    val audioClipboard = remember(koin) { koin.getOrNull<io.github.jdreioe.wingmate.platform.AudioClipboard>() }
    val shareService = remember(koin) { koin.getOrNull<io.github.jdreioe.wingmate.platform.ShareService>() }
    val scope = rememberCoroutineScope()
    var route by remember { mutableStateOf<BoardSetRoute>(BoardSetRoute.Library) }
    var boardSets by remember { mutableStateOf<List<ObfBoardSet>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showDictionary by remember { mutableStateOf(false) }
    var showImportExport by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ObfBoardSet?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val loadError = stringResource(Res.string.board_sets_load_error)
    val duplicatedMessage = stringResource(Res.string.board_sets_duplicated)
    val duplicateError = stringResource(Res.string.board_sets_duplicate_error)
    val lockError = stringResource(Res.string.board_sets_lock_error)
    val createError = stringResource(Res.string.board_sets_create_error)
    val deletedMessage = stringResource(Res.string.board_sets_deleted)
    val deleteError = stringResource(Res.string.board_sets_delete_error)
    val defaultBoardName = stringResource(Res.string.board_dialog_default_board_name)

    fun refreshBoardSets() {
        scope.launch {
            isLoading = true
            runCatching { useCase.listBoardSets() }
                .onSuccess { boardSets = it }
                .onFailure { statusMessage = it.message ?: loadError }
            isLoading = false
        }
    }

    fun useLatestAudio(action: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val path = runCatching {
                saidTextRepository.list()
                    .filter { !it.audioFilePath.isNullOrBlank() }
                    .maxByOrNull { it.date ?: it.createdAt ?: 0L }
                    ?.audioFilePath
            }.getOrNull()
            if (!path.isNullOrBlank()) action(path)
        }
    }

    LaunchedEffect(Unit) { refreshBoardSets() }
    LaunchedEffect(initialBoardSetId) {
        val targetBoardSet = withContext(Dispatchers.Default) {
            initialBoardSetId
                ?.let { useCase.getBoardSet(it) }
                ?: useCase.listBoardSets().singleOrNull()
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
            onOpenDictionary = {
                showDictionary = true
                featureUsageReporter.reportEvent(
                    FeatureUsageEvents.SETTINGS_UPDATED,
                    "action" to "open_pronunciation_dictionary"
                )
            },
            onCopyLastSound = { useLatestAudio { audioClipboard?.copyAudioFile(it) } },
            onShareLastSound = { useLatestAudio { shareService?.shareAudio(it) } },
            onOpenSettings = { showSettings = true },
            onOpenImportExport = { showImportExport = true },
            onCheckUpdates = {
                featureUsageReporter.reportEvent(
                    FeatureUsageEvents.SETTINGS_UPDATED,
                    "action" to "check_updates"
                )
                scope.launch(Dispatchers.IO) {
                    runCatching { updateService?.checkForUpdates() }
                }
            },
            onOpenWelcome = onBackToWelcome,
            onCreate = { showCreateDialog = true },
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
            onCreate = { name, rows, columns ->
                scope.launch {
                    runCatching {
                        useCase.createBoardSet(name.trim(), rows, columns, defaultBoardName)
                    }
                        .onSuccess { created ->
                            showCreateDialog = false
                            refreshBoardSets()
                            route = BoardSetRoute.Workspace(created.id, BoardWorkspaceMode.Edit)
                        }
                        .onFailure { statusMessage = it.message ?: createError }
                }
            }
        )
    }

    deleteTarget?.let { boardSet ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(Res.string.board_sets_delete_title)) },
            text = { Text(stringResource(Res.string.board_sets_delete_body, boardSet.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            runCatching { useCase.deleteBoardSet(boardSet.id) }
                                .onSuccess {
                                    statusMessage = deletedMessage
                                    refreshBoardSets()
                                }
                                .onFailure { statusMessage = it.message ?: deleteError }
                        }
                    }
                ) { Text(stringResource(Res.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(Res.string.common_cancel)) }
            }
        )
    }

    if (showSettings) {
        SettingsScreen(onDismiss = { showSettings = false })
    }
    if (showImportExport) {
        SettingsExportDialog(onDismiss = { showImportExport = false })
    }
    if (showDictionary) {
        var entries by remember { mutableStateOf<List<io.github.jdreioe.wingmate.domain.PronunciationEntry>>(emptyList()) }

        LaunchedEffect(showDictionary) {
            if (showDictionary) entries = dictionaryRepository.getAll()
        }

        DictionaryScreen(
            entries = entries,
            onAddEntry = { word, phoneme, alphabet ->
                scope.launch {
                    dictionaryRepository.add(io.github.jdreioe.wingmate.domain.PronunciationEntry(word, phoneme, alphabet))
                    entries = dictionaryRepository.getAll()
                }
            },
            onDeleteEntry = { entry ->
                scope.launch {
                    dictionaryRepository.delete(entry.word)
                    entries = dictionaryRepository.getAll()
                }
            },
            onTestEntry = { word, phoneme, alphabet ->
                scope.launch {
                    val voice = runCatching { voiceUseCase.selected() }.getOrNull()
                    speechService.speak(
                        "<phoneme alphabet=\"$alphabet\" ph=\"$phoneme\">$word</phoneme>",
                        voice,
                        voice?.pitch,
                        voice?.rate
                    )
                }
            },
            onGuessPronunciation = { word ->
                val voice = runCatching { voiceUseCase.selected() }.getOrNull()
                speechService.guessPronunciation(word, voice?.primaryLanguage ?: "en")
            },
            onBack = { showDictionary = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoardSetLibraryScreen(
    boardSets: List<ObfBoardSet>,
    isLoading: Boolean,
    statusMessage: String?,
    onBack: () -> Unit,
    onOpenDictionary: () -> Unit,
    onCopyLastSound: () -> Unit,
    onShareLastSound: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenImportExport: () -> Unit,
    onCheckUpdates: () -> Unit,
    onOpenWelcome: () -> Unit,
    onCreate: () -> Unit,
    onOpen: (ObfBoardSet) -> Unit,
    onEdit: (ObfBoardSet) -> Unit,
    onDuplicate: (ObfBoardSet) -> Unit,
    onToggleLock: (ObfBoardSet) -> Unit,
    onDelete: (ObfBoardSet) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(Res.string.board_sets_title), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(Res.string.board_sets_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.Keyboard,
                            contentDescription = stringResource(Res.string.mode_switch_to_keyboard)
                        )
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(Res.string.board_workspace_actions)
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.phrase_screen_pronunciation_dictionary)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onOpenDictionary()
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.phrase_screen_copy_last_soundfile)) },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onCopyLastSound()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.phrase_screen_share_last_soundfile)) },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onShareLastSound()
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.phrase_screen_app_settings)) },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onOpenSettings()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.phrase_screen_import_export_data)) },
                                leadingIcon = { Icon(Icons.Default.ImportExport, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onOpenImportExport()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.phrase_screen_check_updates)) },
                                leadingIcon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onCheckUpdates()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.phrase_screen_welcome_screen)) },
                                leadingIcon = { Icon(Icons.Default.Home, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onOpenWelcome()
                                }
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
                text = { Text(stringResource(Res.string.board_sets_new)) }
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
                    Text(stringResource(Res.string.board_sets_empty_title), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(Res.string.board_sets_empty_body),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onCreate) { Text(stringResource(Res.string.board_sets_create)) }
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
private fun BoardSetLibraryCard(
    boardSet: ObfBoardSet,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onToggleLock: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        ListItem(
            headlineContent = { Text(boardSet.name, fontWeight = FontWeight.SemiBold) },
            supportingContent = {
                Text(pluralStringResource(Res.plurals.board_sets_board_count, boardSet.boardIds.size, boardSet.boardIds.size))
            },
            leadingContent = {
                Icon(
                    if (boardSet.isLocked) Icons.Default.Lock else Icons.Default.Home,
                    contentDescription = if (boardSet.isLocked) stringResource(Res.string.board_sets_locked) else null
                )
            },
            trailingContent = {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(Res.string.board_sets_actions))
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.board_sets_open)) },
                            onClick = { menuExpanded = false; onOpen() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.board_sets_edit)) },
                            enabled = !boardSet.isLocked,
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = { menuExpanded = false; onEdit() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.board_sets_duplicate)) },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                            onClick = { menuExpanded = false; onDuplicate() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(if (boardSet.isLocked) Res.string.board_sets_unlock else Res.string.board_sets_lock)) },
                            leadingIcon = {
                                Icon(
                                    if (boardSet.isLocked) Icons.Default.LockOpen else Icons.Default.Lock,
                                    contentDescription = null
                                )
                            },
                            onClick = { menuExpanded = false; onToggleLock() }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.common_delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            },
                            onClick = { menuExpanded = false; onDelete() }
                        )
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
    val phraseUseCase = koinInject<PhraseUseCase>()
    val speechService = koinInject<SpeechService>()
    val voiceUseCase = koinInject<VoiceUseCase>()
    val settings by rememberReactiveSettings()
    val shareService = koinInject<io.github.jdreioe.wingmate.platform.ShareService>()
    val scope = rememberCoroutineScope()
    var savedGraph by remember(boardSetId) { mutableStateOf<BoardSetGraph?>(null) }
    var editSession by remember(boardSetId) { mutableStateOf<BoardSetEditSession?>(null) }
    var mode by remember(boardSetId) { mutableStateOf(initialMode) }
    var selectedBoardId by remember(boardSetId) { mutableStateOf<String?>(null) }
    var boardStack by remember(boardSetId) { mutableStateOf<List<String>>(emptyList()) }
    var selectedButtons by remember(boardSetId) {
        mutableStateOf<List<Pair<ObfButton, ImageBitmap?>>>(emptyList())
    }
    var isLoading by remember(boardSetId) { mutableStateOf(true) }
    var statusMessage by remember(boardSetId) { mutableStateOf<String?>(null) }
    var showAddBoardDialog by remember { mutableStateOf(false) }
    var editingCell by remember { mutableStateOf<WorkspaceCellTarget?>(null) }
    var showFinishDialog by remember { mutableStateOf(false) }
    var editActionsExpanded by remember { mutableStateOf(false) }
    var showRenameBoardDialog by remember { mutableStateOf(false) }
    var showDeleteBoardDialog by remember { mutableStateOf(false) }
    var isSavingSentence by remember(boardSetId) { mutableStateOf(false) }
    var isExporting by remember(boardSetId) { mutableStateOf(false) }
    var isFullscreen by remember(boardSetId) { mutableStateOf(false) }
    val unlockToEditMessage = stringResource(Res.string.board_workspace_unlock_to_edit)
    val savedMessage = stringResource(Res.string.board_workspace_saved)
    val saveErrorMessage = stringResource(Res.string.board_workspace_save_error)
    val phraseSavedMessage = stringResource(Res.string.board_workspace_phrase_saved)
    val phraseSaveErrorMessage = stringResource(Res.string.board_workspace_phrase_save_error)
    val englishLanguageName = stringResource(Res.string.board_dialog_language_english)
    val danishLanguageName = stringResource(Res.string.board_dialog_language_danish)
    val primaryLanguageName = languageName(
        settings.primaryLanguage,
        englishLanguageName,
        danishLanguageName,
        stringResource(Res.string.language_primary)
    )
    val secondaryLanguageName = languageName(
        settings.secondaryLanguage,
        englishLanguageName,
        danishLanguageName,
        stringResource(Res.string.language_secondary)
    )
    val availableFieldLanguages = listOf(
        FieldLanguageOption(
            settings.primaryLanguage,
            stringResource(Res.string.board_dialog_language_primary_value, primaryLanguageName)
        ),
        FieldLanguageOption(
            settings.secondaryLanguage,
            stringResource(Res.string.board_dialog_language_secondary_value, secondaryLanguageName)
        )
    ).distinctBy { it.tag }

    LaunchedEffect(boardSetId) {
        isLoading = true
        val loaded = withContext(Dispatchers.Default) { useCase.loadBoardSetGraph(boardSetId) }
        savedGraph = loaded
        selectedBoardId = loaded?.boardSet?.rootBoardId
        if (loaded != null && initialMode == BoardWorkspaceMode.Edit && !loaded.boardSet.isLocked) {
            editSession = BoardSetEditSession(loaded, loaded)
        } else if (loaded?.boardSet?.isLocked == true) {
            mode = BoardWorkspaceMode.Run
        }
        isLoading = false
    }

    val activeGraph = editSession?.draft ?: savedGraph
    val activeBoard = activeGraph?.boardsById?.get(selectedBoardId)

    fun startEditing() {
        val graph = savedGraph ?: return
        if (graph.boardSet.isLocked) {
            statusMessage = unlockToEditMessage
            return
        }
        selectedButtons = emptyList()
        boardStack = emptyList()
        editSession = BoardSetEditSession(graph, graph)
        mode = BoardWorkspaceMode.Edit
    }

    fun requestFinishEditing() {
        val session = editSession ?: return
        if (session.isDirty) showFinishDialog = true
        else {
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
            onExitToLibrary()
        }
    }

    Scaffold(
        topBar = {
            if (!isFullscreen) TopAppBar(
                title = {
                    Column {
                        Text(
                            if (mode == BoardWorkspaceMode.Edit) {
                                stringResource(
                                    Res.string.board_workspace_editing,
                                    activeGraph?.boardSet?.name.orEmpty()
                                )
                            } else {
                                activeBoard?.name ?: stringResource(Res.string.board_workspace_board_fallback)
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.common_back))
                    }
                },
                actions = {
                    if (mode == BoardWorkspaceMode.Edit) {
                        IconButton(
                            onClick = { editSession = editSession?.undo() },
                            enabled = editSession?.undoStack?.isNotEmpty() == true
                        ) {
                            Icon(Icons.Default.Undo, contentDescription = stringResource(Res.string.board_workspace_undo))
                        }
                        TextButton(onClick = ::requestFinishEditing) {
                            Text(stringResource(Res.string.board_workspace_finish))
                        }
                        Box {
                            IconButton(onClick = { editActionsExpanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(Res.string.board_workspace_actions))
                            }
                            DropdownMenu(
                                expanded = editActionsExpanded,
                                onDismissRequest = { editActionsExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.board_workspace_rename)) },
                                    onClick = {
                                        editActionsExpanded = false
                                        showRenameBoardDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.board_workspace_set_home)) },
                                    enabled = activeBoard != null &&
                                        activeBoard.id != activeGraph?.boardSet?.rootBoardId,
                                    leadingIcon = { Icon(Icons.Default.Home, contentDescription = null) },
                                    onClick = {
                                        val session = editSession
                                        val board = activeBoard
                                        if (session != null && board != null) {
                                            editSession = session.apply(setDraftRoot(session.draft, board.id))
                                        }
                                        editActionsExpanded = false
                                    }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.board_workspace_delete), color = MaterialTheme.colorScheme.error) },
                                    enabled = activeGraph?.boards?.size?.let { it > 1 } == true,
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    onClick = {
                                        editActionsExpanded = false
                                        showDeleteBoardDialog = true
                                    }
                                )
                            }
                        }
                    } else {
                        IconButton(onClick = { isFullscreen = true }) {
                            Icon(
                                Icons.Default.Fullscreen,
                                contentDescription = stringResource(Res.string.board_workspace_enter_fullscreen)
                            )
                        }
                        IconButton(onClick = onSwitchToKeyboard) {
                            Icon(
                                Icons.Default.Keyboard,
                                contentDescription = stringResource(Res.string.mode_switch_to_keyboard)
                            )
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    isExporting = true
                                    statusMessage = null
                                    try {
                                        val graph = activeGraph
                                        if (graph == null) {
                                            statusMessage = "Export failed: no board set loaded"
                                        } else {
                                            val obzBytes = useCase.exportBoardSetAsObz(graph.boardSet.id)
                                            if (obzBytes != null) {
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
                                            } else {
                                                statusMessage = "Export failed: no boards"
                                            }
                                        }
                                    } catch (e: Exception) {
                                        statusMessage = "Export failed: ${e.message ?: "unknown error"}"
                                    } finally {
                                        isExporting = false
                                    }
                                }
                            },
                            enabled = !isExporting
                        ) {
                            Icon(
                                Icons.Default.ImportExport,
                                contentDescription = stringResource(Res.string.phrase_screen_import_export_data)
                            )
                        }
                        if (selectedBoardId != activeGraph?.boardSet?.rootBoardId) {
                            IconButton(onClick = {
                                selectedBoardId = activeGraph?.boardSet?.rootBoardId
                                boardStack = emptyList()
                            }) {
                                Icon(Icons.Default.Home, contentDescription = stringResource(Res.string.board_workspace_home))
                            }
                        }
                        if (activeGraph?.boardSet?.isLocked == false) {
                            IconButton(onClick = ::startEditing) {
                                Icon(Icons.Default.Edit, contentDescription = stringResource(Res.string.board_workspace_edit))
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
        },
        floatingActionButton = {
            if (mode == BoardWorkspaceMode.Edit) {
                ExtendedFloatingActionButton(
                    onClick = { showAddBoardDialog = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(Res.string.board_workspace_add)) }
                )
            }
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                activeGraph == null || activeBoard == null -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(Res.string.board_workspace_load_error))
                    TextButton(onClick = onExitToLibrary) {
                        Text(stringResource(Res.string.board_workspace_back_to_library))
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
                            }
                        )
                    }
                    ObfBoardView(
                        board = activeBoard,
                        isEditMode = mode == BoardWorkspaceMode.Edit,
                        showMessageBar = mode == BoardWorkspaceMode.Run,
                        showSentenceText = isFullscreen,
                        selectedButtons = selectedButtons,
                        onButtonClick = { button ->
                            val linkedBoard = activeGraph.resolveLinkedBoard(button.loadBoard)
                            if (linkedBoard != null) {
                                selectedBoardId?.let { boardStack = boardStack + it }
                                selectedBoardId = linkedBoard.id
                            } else {
                                val spokenText = (button.vocalization ?: button.label).orEmpty().trim()
                                if (spokenText.isNotEmpty()) {
                                    selectedButtons = selectedButtons + (button to null)
                                    scope.launch(Dispatchers.IO) {
                                        runCatching {
                                            val voice = voiceUseCase.selected()
                                                .withLanguageOverride(button.locale)
                                            speechService.speak(spokenText, voice, voice?.pitch, voice?.rate)
                                        }
                                    }
                                }
                            }
                        },
                        onCellClick = if (mode == BoardWorkspaceMode.Edit) {
                            { row, column, button -> editingCell = WorkspaceCellTarget(row, column, button) }
                        } else null,
                        onSpeakSentence = {
                            val speechParts = selectedButtons.mapNotNull { (button, _) ->
                                (button.vocalization ?: button.label)
                                    ?.trim()
                                    ?.takeIf(String::isNotEmpty)
                                    ?.let { text -> text to button.locale }
                            }
                            val sentence = speechParts.joinToString(" ") { it.first }
                            if (sentence.isNotEmpty()) {
                                scope.launch(Dispatchers.IO) {
                                    runCatching {
                                        val voice = voiceUseCase.selected()
                                        if (speechParts.any { !it.second.isNullOrBlank() }) {
                                            val segments = speechParts.mapIndexed { index, (text, language) ->
                                                SpeechSegment(
                                                    text = text + if (index < speechParts.lastIndex) " " else "",
                                                    languageTag = language
                                                )
                                            }
                                            speechService.speakSegments(segments, voice, voice?.pitch, voice?.rate)
                                        } else {
                                            speechService.speak(sentence, voice, voice?.pitch, voice?.rate)
                                        }
                                    }
                                }
                            }
                        },
                        onSaveSentence = {
                            val sentence = selectedButtons.joinToString(" ") {
                                (it.first.vocalization ?: it.first.label).orEmpty()
                            }.trim()
                            if (sentence.isNotEmpty() && !isSavingSentence) {
                                isSavingSentence = true
                                scope.launch {
                                    runCatching {
                                        phraseUseCase.add(
                                            Phrase(
                                                id = workspaceId("phrase"),
                                                text = sentence,
                                                name = null,
                                                backgroundColor = null,
                                                parentId = null,
                                                createdAt = Clock.System.now().toEpochMilliseconds()
                                            )
                                        )
                                    }.onSuccess {
                                        statusMessage = phraseSavedMessage
                                    }.onFailure {
                                        statusMessage = phraseSaveErrorMessage
                                    }
                                    isSavingSentence = false
                                }
                            }
                        },
                        isSaveSentenceEnabled = selectedButtons.isNotEmpty() && !isSavingSentence,
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
                            contentDescription = stringResource(Res.string.board_workspace_exit_fullscreen)
                        )
                    }
                }
            }
        }
    }

    if (showAddBoardDialog) {
        CreateBoardDialog(
            onDismiss = { showAddBoardDialog = false },
            onCreate = { name, rows, columns ->
                val session = editSession ?: return@CreateBoardDialog
                val updated = addDraftBoard(session.draft, name, rows, columns)
                editSession = session.apply(updated)
                selectedBoardId = updated.boards.last().id
                showAddBoardDialog = false
            }
        )
    }

    val target = editingCell
    if (target != null && activeBoard != null) {
        val currentSpan = activeBoard.grid?.fieldSpanAt(target.row, target.column)
            ?: GridFieldSpan(rows = 1, columns = 1)
        val availableSpans = activeBoard.grid
            ?.availableFieldSpansAt(target.row, target.column)
            .orEmpty()
            .map { FieldSpanOption(rows = it.rows, columns = it.columns) }
        val initialImageUrl = target.button?.imageId
            ?.let { id -> activeBoard.images.firstOrNull { it.id == id }?.url }
            .orEmpty()
        EditBoardCellDialog(
            boardName = activeBoard.name ?: stringResource(Res.string.board_workspace_board_fallback),
            row = target.row,
            column = target.column,
            initialLabel = target.button?.label.orEmpty(),
            initialVocalization = target.button?.vocalization.orEmpty(),
            initialImageUrl = initialImageUrl,
            initialBackgroundColor = target.button?.backgroundColor,
            availableLanguages = availableFieldLanguages,
            initialLanguage = target.button?.locale,
            availableBoards = activeGraph?.boards.orEmpty().filterNot { it.id == activeBoard.id },
            initialLinkedBoardId = activeGraph?.resolveLinkedBoard(target.button?.loadBoard)?.id,
            availableSpans = availableSpans,
            initialRowSpan = currentSpan.rows,
            initialColumnSpan = currentSpan.columns,
            hasExistingValue = target.button != null,
            onDismiss = { editingCell = null },
            onSave = { label, vocalization, imageUrl, backgroundColor, language, linkedBoardId,
                       rowSpan, columnSpan ->
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
                        backgroundColor = backgroundColor,
                        language = language,
                        linkedBoardId = linkedBoardId,
                        rowSpan = rowSpan,
                        columnSpan = columnSpan
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
            title = { Text(stringResource(Res.string.board_workspace_finish_title)) },
            text = { Text(stringResource(Res.string.board_workspace_finish_body)) },
            confirmButton = {
                Button(onClick = {
                    val session = editSession ?: return@Button
                    showFinishDialog = false
                    scope.launch {
                        useCase.saveBoardSetGraph(session.draft)
                            .onSuccess { saved ->
                                savedGraph = saved
                                editSession = null
                                mode = BoardWorkspaceMode.Run
                                statusMessage = savedMessage
                            }
                            .onFailure {
                                statusMessage = it.message ?: saveErrorMessage
                            }
                    }
                }) { Text(stringResource(Res.string.board_workspace_save_changes)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showFinishDialog = false
                        editSession = null
                        mode = BoardWorkspaceMode.Run
                        selectedBoardId = savedGraph?.boardSet?.rootBoardId
                    }) { Text(stringResource(Res.string.board_workspace_discard)) }
                    TextButton(onClick = { showFinishDialog = false }) {
                        Text(stringResource(Res.string.board_workspace_keep_editing))
                    }
                }
            }
        )
    }

    if (showRenameBoardDialog && activeBoard != null) {
        RenameBoardDialog(
            currentName = activeBoard.name.orEmpty(),
            onDismiss = { showRenameBoardDialog = false },
            onRename = { name ->
                val session = editSession
                if (session != null) {
                    editSession = session.apply(renameDraftBoard(session.draft, activeBoard.id, name))
                }
                showRenameBoardDialog = false
            }
        )
    }

    if (showDeleteBoardDialog && activeBoard != null) {
        AlertDialog(
            onDismissRequest = { showDeleteBoardDialog = false },
            title = { Text(stringResource(Res.string.board_workspace_delete_title)) },
            text = {
                Text(
                    stringResource(
                        Res.string.board_workspace_delete_body,
                        activeBoard.name ?: stringResource(Res.string.board_workspace_board_fallback)
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
                }) { Text(stringResource(Res.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteBoardDialog = false }) {
                    Text(stringResource(Res.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun RenameBoardDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var name by remember(currentName) { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.board_workspace_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(Res.string.board_workspace_board_name)) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(name.trim()) },
                enabled = name.isNotBlank()
            ) { Text(stringResource(Res.string.board_workspace_rename_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
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
    val fallbackName = stringResource(Res.string.board_workspace_board_fallback)
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
    columns: Int
): BoardSetGraph {
    val boardId = workspaceId("board")
    val safeRows = rows.coerceAtLeast(1)
    val safeColumns = columns.coerceAtLeast(1)
    val board = ObfBoard(
        format = "open-board-0.1",
        id = boardId,
        name = name.trim(),
        grid = ObfGrid(
            rows = safeRows,
            columns = safeColumns,
            order = List(safeRows) { List(safeColumns) { null } }
        )
    )
    return graph.copy(
        boardSet = graph.boardSet.copy(boardIds = graph.boardSet.boardIds + boardId),
        boards = graph.boards + board
    )
}

internal fun updateDraftCell(
    graph: BoardSetGraph,
    boardId: String,
    row: Int,
    column: Int,
    label: String,
    vocalization: String?,
    imageUrl: String?,
    backgroundColor: String?,
    language: String?,
    linkedBoardId: String?,
    rowSpan: Int = 1,
    columnSpan: Int = 1
): BoardSetGraph {
    val board = graph.boardsById[boardId] ?: return graph
    val grid = board.grid ?: return graph
    val existingId = grid.order.getOrNull(row)?.getOrNull(column)
    val existingButton = existingId?.let { id -> board.buttons.firstOrNull { it.id == id } }
    val buttonId = existingButton?.id ?: workspaceId("btn")
    var imageId = existingButton?.imageId
    val normalizedUrl = imageUrl?.trim()?.ifBlank { null }
    val images = when {
        normalizedUrl == null -> {
            imageId = null
            board.images
        }
        imageId != null -> board.images.map {
            if (it.id == imageId) it.copy(url = normalizedUrl, path = null, data = null) else it
        }
        else -> {
            imageId = workspaceId("img")
            board.images + ObfImage(id = imageId!!, url = normalizedUrl)
        }
    }
    val button = (existingButton ?: ObfButton(id = buttonId)).copy(
        label = label.trim(),
        vocalization = vocalization?.trim()?.ifBlank { null },
        backgroundColor = backgroundColor?.trim()?.ifBlank { null },
        locale = language?.trim()?.ifBlank { null },
        imageId = imageId,
        loadBoard = linkedBoardId?.let { targetId ->
            ObfLoadBoard(id = targetId, name = graph.boardsById[targetId]?.name)
        }
    )
    val buttons = if (existingButton == null) board.buttons + button else board.buttons.map {
        if (it.id == button.id) button else it
    }
    val updatedGrid = grid.withFieldSpan(row, column, buttonId, rowSpan, columnSpan)
        ?: return graph
    val updatedBoard = board.copy(buttons = buttons, images = images, grid = updatedGrid)
    return graph.copy(boards = graph.boards.map { if (it.id == boardId) updatedBoard else it })
}

internal fun clearDraftCell(
    graph: BoardSetGraph,
    boardId: String,
    row: Int,
    column: Int
): BoardSetGraph {
    val board = graph.boardsById[boardId] ?: return graph
    val grid = board.grid ?: return graph
    val removedButtonId = grid.order.getOrNull(row)?.getOrNull(column)
    val order = grid.normalizedOrder().map { values ->
        values.map { value -> if (value == removedButtonId) null else value }
    }
    val removedImageId = board.buttons.firstOrNull { it.id == removedButtonId }?.imageId
    val buttons = board.buttons.filterNot { it.id == removedButtonId }
    val images = if (removedImageId != null && buttons.none { it.imageId == removedImageId }) {
        board.images.filterNot { it.id == removedImageId }
    } else board.images
    val updatedBoard = board.copy(buttons = buttons, images = images, grid = grid.copy(order = order))
    return graph.copy(boards = graph.boards.map { if (it.id == boardId) updatedBoard else it })
}

internal fun ObfGrid.fieldSpanAt(row: Int, column: Int): GridFieldSpan {
    val buttonId = order.getOrNull(row)?.getOrNull(column)
        ?: return GridFieldSpan(rows = 1, columns = 1)
    val occupiedCells = normalizedOrder().flatMapIndexed { rowIndex, values ->
        values.mapIndexedNotNull { columnIndex, value ->
            if (value == buttonId) rowIndex to columnIndex else null
        }
    }
    if (occupiedCells.isEmpty()) return GridFieldSpan(rows = 1, columns = 1)
    val minRow = occupiedCells.minOf { it.first }
    val maxRow = occupiedCells.maxOf { it.first }
    val minColumn = occupiedCells.minOf { it.second }
    val maxColumn = occupiedCells.maxOf { it.second }
    return GridFieldSpan(
        rows = maxRow - minRow + 1,
        columns = maxColumn - minColumn + 1
    )
}

internal fun ObfGrid.availableFieldSpansAt(row: Int, column: Int): List<GridFieldSpan> {
    if (row !in 0 until rows || column !in 0 until columns) return emptyList()
    val normalized = normalizedOrder()
    val existingId = normalized[row][column]
    return buildList {
        for (rowSpan in 1..(rows - row)) {
            for (columnSpan in 1..(columns - column)) {
                val available = (row until row + rowSpan).all { rowIndex ->
                    (column until column + columnSpan).all { columnIndex ->
                        normalized[rowIndex][columnIndex] == null ||
                            normalized[rowIndex][columnIndex] == existingId
                    }
                }
                if (available) add(GridFieldSpan(rowSpan, columnSpan))
            }
        }
    }.sortedWith(compareBy<GridFieldSpan> { it.rows * it.columns }.thenBy { it.rows }.thenBy { it.columns })
}

internal fun ObfGrid.withFieldSpan(
    row: Int,
    column: Int,
    buttonId: String,
    rowSpan: Int,
    columnSpan: Int
): ObfGrid? {
    if (row !in 0 until rows || column !in 0 until columns) return null
    val safeRowSpan = rowSpan.coerceAtLeast(1)
    val safeColumnSpan = columnSpan.coerceAtLeast(1)
    if (row + safeRowSpan > rows || column + safeColumnSpan > columns) return null
    val normalized = normalizedOrder()
    val existingId = normalized[row][column]
    val targetIsAvailable = (row until row + safeRowSpan).all { rowIndex ->
        (column until column + safeColumnSpan).all { columnIndex ->
            normalized[rowIndex][columnIndex] == null || normalized[rowIndex][columnIndex] == existingId
        }
    }
    if (!targetIsAvailable) return null
    val cleared = normalized.map { values ->
        values.map { value -> if (value == existingId && existingId != null) null else value }.toMutableList()
    }
    for (rowIndex in row until row + safeRowSpan) {
        for (columnIndex in column until column + safeColumnSpan) {
            cleared[rowIndex][columnIndex] = buttonId
        }
    }
    return copy(order = cleared)
}

private fun ObfGrid.normalizedOrder(): List<List<String?>> =
    List(rows.coerceAtLeast(0)) { rowIndex ->
        List(columns.coerceAtLeast(0)) { columnIndex ->
            order.getOrNull(rowIndex)?.getOrNull(columnIndex)
        }
    }

private fun renameDraftBoard(graph: BoardSetGraph, boardId: String, name: String): BoardSetGraph {
    return graph.copy(
        boards = graph.boards.map { board ->
            if (board.id == boardId) board.copy(name = name.trim()) else board
        }
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
