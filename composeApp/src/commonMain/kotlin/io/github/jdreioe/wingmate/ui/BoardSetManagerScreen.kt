package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.application.BoardSetUseCase
import io.github.jdreioe.wingmate.application.FeatureUsageEvents
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.application.reportEvent
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.net.URL
import kotlin.random.Random

private data class BoardCellTarget(
    val row: Int,
    val column: Int,
    val button: ObfButton?
)

private data class SentenceToken(
    val id: String,
    val text: String,
    val image: ObfImage?
)

private val sentenceImageCache = mutableMapOf<String, ByteArray>()

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BoardSetManagerScreen(
    onBack: () -> Unit,
    onOpenBoard: (String) -> Unit
) {
    val useCase = koinInject<BoardSetUseCase>()
    val featureUsageReporter = koinInject<FeatureUsageReporter>()
    val scope = rememberCoroutineScope()

    var boardSets by remember { mutableStateOf<List<ObfBoardSet>>(emptyList()) }
    var selectedBoardSetId by remember { mutableStateOf<String?>(null) }
    var selectedBoardId by remember { mutableStateOf<String?>(null) }
    var boards by remember { mutableStateOf<List<ObfBoard>>(emptyList()) }
    var activeBoard by remember { mutableStateOf<ObfBoard?>(null) }

    var isLoading by remember { mutableStateOf(true) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isCellEditMode by remember { mutableStateOf(false) }

    var showCreateBoardSetDialog by remember { mutableStateOf(false) }
    var showCreateBoardDialog by remember { mutableStateOf(false) }
    var editingCellTarget by remember { mutableStateOf<BoardCellTarget?>(null) }

    val selectedBoardSet = boardSets.firstOrNull { it.id == selectedBoardSetId }
    val isLocked = selectedBoardSet?.isLocked == true

    LaunchedEffect(Unit) {
        featureUsageReporter.reportEvent(
            FeatureUsageEvents.SCREEN_VIEW,
            "screen" to "boardsets"
        )
    }

    fun refreshBoardSets(keepSelection: Boolean) {
        scope.launch {
            isLoading = true
            runCatching { useCase.listBoardSets() }
                .onSuccess { loaded ->
                    boardSets = loaded
                    if (loaded.isEmpty()) {
                        selectedBoardSetId = null
                        selectedBoardId = null
                        boards = emptyList()
                        activeBoard = null
                    } else {
                        val nextId = if (keepSelection) {
                            loaded.firstOrNull { it.id == selectedBoardSetId }?.id ?: loaded.first().id
                        } else {
                            loaded.first().id
                        }
                        selectedBoardSetId = nextId
                    }
                }
                .onFailure { statusMessage = it.message ?: "Failed to load boardsets" }
            isLoading = false
        }
    }

    fun refreshBoards(boardSetId: String, preferredBoardId: String? = selectedBoardId) {
        scope.launch {
            runCatching {
                val loadedBoards = useCase.listBoards(boardSetId)
                val boardSet = useCase.getBoardSet(boardSetId)
                loadedBoards to boardSet
            }.onSuccess { (loadedBoards, boardSet) ->
                boards = loadedBoards
                val resolvedBoardId = when {
                    preferredBoardId != null && loadedBoards.any { it.id == preferredBoardId } -> preferredBoardId
                    boardSet != null && loadedBoards.any { it.id == boardSet.rootBoardId } -> boardSet.rootBoardId
                    loadedBoards.isNotEmpty() -> loadedBoards.first().id
                    else -> null
                }
                selectedBoardId = resolvedBoardId
                activeBoard = loadedBoards.firstOrNull { it.id == resolvedBoardId }
            }.onFailure {
                statusMessage = it.message ?: "Failed to load boards"
                boards = emptyList()
                selectedBoardId = null
                activeBoard = null
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshBoardSets(keepSelection = false)
    }

    LaunchedEffect(selectedBoardSetId) {
        val boardSetId = selectedBoardSetId ?: return@LaunchedEffect
        refreshBoards(boardSetId)
    }

    LaunchedEffect(selectedBoardId, boards) {
        activeBoard = boards.firstOrNull { it.id == selectedBoardId }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = selectedBoardSet?.name ?: "Board Workspace",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Build and edit your boards",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        featureUsageReporter.reportEvent(
                            FeatureUsageEvents.SCREEN_VIEW,
                            "screen" to "phrase"
                        )
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateBoardSetDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "New boardset")
                    }
                }
            )
        },
        floatingActionButton = {
            if (selectedBoardSet != null) {
                ExtendedFloatingActionButton(
                    onClick = { showCreateBoardDialog = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add board") }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp)
                .padding(bottom = 8.dp)
        ) {
            statusMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (boardSets.isEmpty()) {
                EmptyBoardSetState(onCreate = { showCreateBoardSetDialog = true })
            } else {
                Text(
                    text = "Boardsets",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    boardSets.forEach { set ->
                        FilterChip(
                            selected = selectedBoardSetId == set.id,
                            onClick = {
                                selectedBoardSetId = set.id
                                statusMessage = null
                            },
                            label = { Text(set.name) },
                            leadingIcon = {
                                if (set.isLocked) {
                                    Icon(Icons.Default.Lock, contentDescription = null)
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (selectedBoardSet != null) {
                    BoardToolbar(
                        isLocked = isLocked,
                        isEditMode = isCellEditMode,
                        onSave = {
                            scope.launch {
                                val result = useCase.touchBoardSet(selectedBoardSet.id)
                                statusMessage = if (result == null) "Save failed" else "Boardset saved"
                                refreshBoardSets(keepSelection = true)
                            }
                        },
                        onToggleEdit = {
                            isCellEditMode = !isCellEditMode
                            featureUsageReporter.reportEvent(
                                FeatureUsageEvents.SETTINGS_UPDATED,
                                "action" to "board_edit_mode",
                                "enabled" to isCellEditMode.toString()
                            )
                            statusMessage = if (isCellEditMode) {
                                "Edit mode enabled. Tap a cell to edit."
                            } else {
                                "Edit mode disabled"
                            }
                        },
                        onToggleLock = {
                            scope.launch {
                                val result = useCase.toggleLocked(selectedBoardSet.id)
                                statusMessage = if (result == null) {
                                    "Unable to change lock state"
                                } else if (result.isLocked) {
                                    "Boardset locked"
                                } else {
                                    "Boardset unlocked"
                                }
                                refreshBoardSets(keepSelection = true)
                            }
                        },
                        onAddBoard = { showCreateBoardDialog = true },
                        onOpenInCommunicator = {
                            activeBoard?.let { board -> onOpenBoard(board.id) }
                            featureUsageReporter.reportEvent(
                                FeatureUsageEvents.SCREEN_VIEW,
                                "screen" to "phrase"
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (boards.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No boards in this boardset yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    BoardTabs(
                        boards = boards,
                        selectedBoardId = selectedBoardId,
                        onSelect = { boardId -> selectedBoardId = boardId }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    activeBoard?.let { board ->
                        BoardGridWorkspace(
                            board = board,
                            isLocked = isLocked,
                            isEditMode = isCellEditMode,
                            onCellEdit = { row, col, button ->
                                editingCellTarget = BoardCellTarget(row, col, button)
                            }
                        )
                    }
                }
            }
        }
    }

    if (showCreateBoardSetDialog) {
        CreateBoardSetDialog(
            onDismiss = { showCreateBoardSetDialog = false },
            onCreate = { name, rows, columns ->
                scope.launch {
                    runCatching { useCase.createBoardSet(name.trim(), rows, columns) }
                        .onSuccess { created ->
                            showCreateBoardSetDialog = false
                            selectedBoardSetId = created.id
                            statusMessage = "Boardset created"
                            refreshBoardSets(keepSelection = true)
                        }
                        .onFailure { statusMessage = it.message ?: "Failed to create boardset" }
                }
            }
        )
    }

    if (showCreateBoardDialog && selectedBoardSet != null) {
        CreateBoardDialog(
            onDismiss = { showCreateBoardDialog = false },
            onCreate = { name, rows, columns ->
                scope.launch {
                    val created = useCase.createBoard(
                        boardSetId = selectedBoardSet.id,
                        name = name.trim(),
                        rows = rows,
                        columns = columns
                    )
                    if (created == null) {
                        statusMessage = "Unable to add board. The boardset may be locked."
                    } else {
                        statusMessage = "Board added"
                        refreshBoardSets(keepSelection = true)
                        refreshBoards(selectedBoardSet.id, preferredBoardId = created.id)
                    }
                    showCreateBoardDialog = false
                }
            }
        )
    }

    val boardForEdit = activeBoard
    val cellTarget = editingCellTarget
    if (boardForEdit != null && cellTarget != null && selectedBoardSet != null) {
        EditBoardCellDialog(
            boardName = boardForEdit.name ?: "Board",
            row = cellTarget.row,
            column = cellTarget.column,
            initialLabel = cellTarget.button?.label.orEmpty(),
            initialVocalization = cellTarget.button?.vocalization.orEmpty(),
            initialImageUrl = cellTarget.button?.imageId
                ?.let { imageId -> boardForEdit.images.firstOrNull { it.id == imageId }?.url }
                .orEmpty(),
            hasExistingValue = cellTarget.button != null,
            onDismiss = { editingCellTarget = null },
            onSave = { label, vocalization, imageUrl ->
                scope.launch {
                    val updated = useCase.upsertBoardCellButton(
                        boardSetId = selectedBoardSet.id,
                        boardId = boardForEdit.id,
                        row = cellTarget.row,
                        column = cellTarget.column,
                        label = label,
                        vocalization = vocalization,
                        imageUrl = imageUrl
                    )
                    if (updated == null) {
                        statusMessage = "Unable to update cell. The boardset may be locked."
                    } else {
                        statusMessage = "Cell saved"
                        refreshBoards(selectedBoardSet.id, preferredBoardId = boardForEdit.id)
                    }
                    editingCellTarget = null
                }
            },
            onClearCell = {
                scope.launch {
                    val updated = useCase.clearBoardCellButton(
                        boardSetId = selectedBoardSet.id,
                        boardId = boardForEdit.id,
                        row = cellTarget.row,
                        column = cellTarget.column
                    )
                    if (updated == null) {
                        statusMessage = "Unable to clear cell. The boardset may be locked."
                    } else {
                        statusMessage = "Cell cleared"
                        refreshBoards(selectedBoardSet.id, preferredBoardId = boardForEdit.id)
                    }
                    editingCellTarget = null
                }
            }
        )
    }
}

@Composable
private fun EmptyBoardSetState(onCreate: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "No boardsets yet",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Create your first boardset to start building boards from scratch.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(20.dp))
        OutlinedButton(onClick = onCreate) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("New boardset")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardToolbar(
    isLocked: Boolean,
    isEditMode: Boolean,
    onSave: () -> Unit,
    onToggleEdit: () -> Unit,
    onToggleLock: () -> Unit,
    onAddBoard: () -> Unit,
    onOpenInCommunicator: () -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AssistChip(
            onClick = onSave,
            label = { Text("Save boardset") },
            leadingIcon = { Icon(Icons.Default.Save, contentDescription = null) },
            colors = AssistChipDefaults.assistChipColors()
        )

        AssistChip(
            onClick = onToggleEdit,
            label = { Text(if (isEditMode) "Done editing" else "Edit cells") },
            leadingIcon = { Icon(if (isEditMode) Icons.Default.Check else Icons.Default.Edit, contentDescription = null) }
        )

        AssistChip(
            onClick = onToggleLock,
            label = { Text(if (isLocked) "Unlock boardset" else "Lock boardset") },
            leadingIcon = { Icon(if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen, contentDescription = null) }
        )

        AssistChip(
            onClick = onAddBoard,
            label = { Text("Add board") },
            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) }
        )

        AssistChip(
            onClick = onOpenInCommunicator,
            label = { Text("Open in communicator") },
            leadingIcon = { Icon(Icons.Default.OpenInNew, contentDescription = null) }
        )
    }
}

@Composable
private fun BoardTabs(
    boards: List<ObfBoard>,
    selectedBoardId: String?,
    onSelect: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = "Boards",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                boards.forEach { board ->
                    FilterChip(
                        selected = board.id == selectedBoardId,
                        onClick = { onSelect(board.id) },
                        label = {
                            Text(
                                text = board.name ?: "Board",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = {
                            if (board.id == selectedBoardId) {
                                Icon(Icons.Default.Home, contentDescription = null)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun BoardGridWorkspace(
    board: ObfBoard,
    isLocked: Boolean,
    isEditMode: Boolean,
    onCellEdit: (row: Int, column: Int, button: ObfButton?) -> Unit
) {
    val grid = board.grid
    if (grid == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "This board has no grid",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val rows = grid.rows.coerceAtLeast(1)
    val columns = grid.columns.coerceAtLeast(1)
    val buttonsById = remember(board) { board.buttons.associateBy { it.id } }
    val imagesById = remember(board) { board.images.associateBy { it.id } }
    var sentenceTokens by remember(board.id) { mutableStateOf<List<SentenceToken>>(emptyList()) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Sentence",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    OutlinedButton(
                        onClick = {
                            if (sentenceTokens.isNotEmpty()) {
                                sentenceTokens = sentenceTokens.dropLast(1)
                            }
                        },
                        enabled = sentenceTokens.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Delete phrase")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (sentenceTokens.isEmpty()) {
                    Text(
                        text = "Tap a phrase cell to build your sentence",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().height(130.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(sentenceTokens, key = { it.id }) { token ->
                            SentenceTokenCard(
                                token = token,
                                onDelete = {
                                    sentenceTokens = sentenceTokens.filterNot { it.id == token.id }
                                }
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            repeat(rows) { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    repeat(columns) { column ->
                        val buttonId = grid.order.getOrNull(row)?.getOrNull(column)
                        val button = buttonId?.let { buttonsById[it] }
                        val label = button?.label?.takeIf { it.isNotBlank() } ?: "Tom celle"
                        val subtitle = button?.vocalization

                        ElevatedCard(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize(),
                            onClick = {
                                if (isEditMode && !isLocked) {
                                    onCellEdit(row, column, button)
                                } else if (!isEditMode && button != null) {
                                    val phrase = (button.vocalization ?: button.label).orEmpty().trim()
                                    if (phrase.isNotEmpty()) {
                                        sentenceTokens = sentenceTokens + SentenceToken(
                                            id = "${button.id}_${System.currentTimeMillis()}_${Random.nextInt(1000, 9999)}",
                                            text = phrase,
                                            image = button.imageId?.let { imageId -> imagesById[imageId] }
                                        )
                                    }
                                }
                            }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (button == null) FontWeight.Normal else FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (button == null) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )

                                if (!subtitle.isNullOrBlank() && subtitle != label) {
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SentenceTokenCard(
    token: SentenceToken,
    onDelete: () -> Unit
) {
    var urlLoadedBitmap by remember(token.image?.url) { mutableStateOf<ImageBitmap?>(null) }

    val syncBitmap = remember(token.image?.id, token.image?.data) {
        token.image?.data?.let { data ->
            runCatching {
                val base64 = if (data.contains(",")) data.substringAfter(",") else data
                val bytes = java.util.Base64.getDecoder().decode(base64)
                bytes.toComposeImageBitmap()
            }.getOrNull()
        }
    }

    val imageUrl = token.image?.url
    LaunchedEffect(imageUrl) {
        if (syncBitmap == null && !imageUrl.isNullOrBlank() && (imageUrl.startsWith("http") || imageUrl.startsWith("file:"))) {
            urlLoadedBitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val cached = sentenceImageCache[imageUrl]
                    val bytes = if (cached != null) {
                        cached
                    } else {
                        val downloaded = URL(imageUrl).readBytes()
                        sentenceImageCache[imageUrl] = downloaded
                        downloaded
                    }
                    bytes.toComposeImageBitmap()
                }.getOrNull()
            }
        }
    }

    val imageBitmap = syncBitmap ?: urlLoadedBitmap

    ElevatedCard(
        modifier = Modifier
            .width(120.dp)
            .fillMaxSize()
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (imageBitmap != null) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = token.text,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Text(
                    text = token.text,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete phrase")
            }
        }
    }
}

@Composable
private fun CreateBoardSetDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, rows: Int, columns: Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var rowsText by remember { mutableStateOf("4") }
    var columnsText by remember { mutableStateOf("8") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New boardset") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Boardset name") },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = rowsText,
                        onValueChange = { rowsText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Rows") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = columnsText,
                        onValueChange = { columnsText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Columns") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(name, rowsText.toIntOrNull() ?: 4, columnsText.toIntOrNull() ?: 8)
                },
                enabled = name.trim().isNotEmpty()
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun CreateBoardDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, rows: Int, columns: Int) -> Unit
) {
    var name by remember { mutableStateOf("Hjem") }
    var rowsText by remember { mutableStateOf("4") }
    var columnsText by remember { mutableStateOf("8") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add board") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Board name") },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = rowsText,
                        onValueChange = { rowsText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Rows") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = columnsText,
                        onValueChange = { columnsText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Columns") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(name, rowsText.toIntOrNull() ?: 4, columnsText.toIntOrNull() ?: 8)
                },
                enabled = name.trim().isNotEmpty()
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun EditBoardCellDialog(
    boardName: String,
    row: Int,
    column: Int,
    initialLabel: String,
    initialVocalization: String,
    initialImageUrl: String,
    hasExistingValue: Boolean,
    onDismiss: () -> Unit,
    onSave: (label: String, vocalization: String?, imageUrl: String?) -> Unit,
    onClearCell: () -> Unit
) {
    var label by remember { mutableStateOf(initialLabel) }
    var vocalization by remember { mutableStateOf(initialVocalization) }
    var imageUrl by remember { mutableStateOf(initialImageUrl) }
    var showSymbolSearch by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit cell (${row + 1}, ${column + 1})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = boardName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = vocalization,
                    onValueChange = { vocalization = it },
                    label = { Text("Vocalization (optional)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = imageUrl,
                    onValueChange = { imageUrl = it },
                    label = { Text("Image URL (optional)") },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showSymbolSearch = true }) {
                        Text("OpenSymbols")
                    }
                    if (imageUrl.isNotBlank()) {
                        OutlinedButton(onClick = { imageUrl = "" }) {
                            Text("Clear image")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        label.trim(),
                        vocalization.trim().ifBlank { null },
                        imageUrl.trim().ifBlank { null }
                    )
                },
                enabled = label.trim().isNotEmpty()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (hasExistingValue) {
                    TextButton(onClick = onClearCell) { Text("Clear cell") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )

    if (showSymbolSearch) {
        OpenSymbolsSearchDialog(
            onDismiss = { showSymbolSearch = false },
            onSelect = { selectedUrl ->
                imageUrl = selectedUrl
                showSymbolSearch = false
            }
        )
    }
}
