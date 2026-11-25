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

internal data class WorkspaceCellTarget(
    val row: Int,
    val column: Int,
    val button: ObfButton?
)

/**
 * Stateless workspace shell. It keeps loading and failure rendering independent from the
 * orchestration-heavy root while allowing the real board surface to remain a cohesive section.
 */
@Composable
internal fun BoardWorkspaceContent(
    state: BoardWorkspaceState,
    hasActiveBoard: Boolean,
    onBackToLibrary: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        when {
            state.contentStatus == BoardWorkspaceContentStatus.Loading && !hasActiveBoard -> {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            !hasActiveBoard -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val failure = state.contentStatus as? BoardWorkspaceContentStatus.RecoverableFailure
                Text(failure?.message ?: stringResource(R.string.board_workspace_load_error))
                if (failure != null) {
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.common_retry))
                    }
                }
                TextButton(onClick = onBackToLibrary) {
                    Text(stringResource(R.string.board_workspace_back_to_library))
                }
            }
            else -> Column(Modifier.fillMaxSize()) {
                if (!state.isFullscreen) state.statusMessage?.let { message ->
                    Text(
                        message,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                content()
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 600, heightDp = 400)
@Composable
internal fun BoardWorkspaceContentPreview() {
    AppTheme {
        BoardWorkspaceContent(
            state = BoardWorkspaceState(
                contentStatus = BoardWorkspaceContentStatus.Ready,
                statusMessage = "Board saved",
            ),
            hasActiveBoard = true,
            onBackToLibrary = {},
            onRetry = {},
        ) {
            Text(
                text = "Communication board",
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.headlineMedium,
            )
        }
    }
}

@Composable
internal fun BoardStrip(
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

internal fun addDraftBoard(
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


internal fun setDraftRoot(graph: BoardSetGraph, boardId: String): BoardSetGraph {
    if (boardId !in graph.boardSet.boardIds) return graph
    return graph.copy(boardSet = graph.boardSet.copy(rootBoardId = boardId))
}

internal fun deleteDraftBoard(graph: BoardSetGraph, boardId: String): BoardSetGraph {
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

internal fun workspaceId(prefix: String): String {
    return "${prefix}_${Clock.System.now().toEpochMilliseconds()}_${Random.nextInt(1000, 9999)}"
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

/**
 * Copies a picked background image into app storage, beside imported Screen media, so it
 * survives cache clearing, OBZ export and backups. Returns null when it is not a usable picture.
 */
internal suspend fun storeBackgroundImage(
    boardSetId: String,
    source: String,
    fileStorage: FileStorage,
    urlLoader: ObfMediaUrlLoader?,
): ObfImage? = withContext(Dispatchers.IO) {
    val bytes = try {
        if (source.startsWith("http")) urlLoader?.load(source) else java.io.File(source.removePrefix("file://")).readBytes()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    } ?: return@withContext null
    // Background images are drawn as bitmaps, so only formats BitmapFactory decodes are accepted.
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val type = bounds.outMimeType ?: return@withContext null
    val id = "bg_${java.util.UUID.randomUUID()}"
    val path = "boardsets/$boardSetId/images/$id.${type.substringAfter('/')}"
    fileStorage.saveBytes(path, bytes)
    ObfImage(id = id, contentType = type, path = path, width = bounds.outWidth, height = bounds.outHeight)
}

/** Loads an OBF image's bytes from inline data, app storage, or its URL. Symbols have no bytes. */
internal suspend fun loadObfImageBytes(
    image: ObfImage,
    fileStorage: FileStorage,
    urlLoader: ObfMediaUrlLoader?,
): ByteArray? {
    for (source in obfImageSources(image)) {
        val bytes = try {
            when (source) {
                is ObfMediaSource.Data -> decodeObfDataUri(source.value)
                is ObfMediaSource.Path -> fileStorage.loadBytes(source.value)
                is ObfMediaSource.Url -> urlLoader?.load(source.value)
                is ObfMediaSource.Symbol -> null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null // Try the next source; a missing image prints as its label.
        }
        if (bytes != null && bytes.isNotEmpty()) return bytes
    }
    return null
}

internal fun decodeObfDataUri(data: String): ByteArray? {
    return Base64Decoder.decodeOrNull(data.substringAfter("base64,", data))
}

internal fun languageName(
    languageTag: String,
    englishName: String,
    danishName: String,
    fallbackName: String
): String = when (languageTag.substringBefore('-').lowercase()) {
    "en" -> englishName
    "da" -> danishName
    else -> fallbackName
}
