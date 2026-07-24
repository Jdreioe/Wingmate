package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import coil3.compose.AsyncImage
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import io.github.jdreioe.wingmate.domain.obf.ObfImageSource
import io.github.jdreioe.wingmate.domain.obf.resolveObfImageSource
import io.github.jdreioe.wingmate.domain.obf.resolveObfLocalizedString
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import io.github.jdreioe.wingmate.ui.toComposeImageBitmap
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.draw.scale
import io.github.jdreioe.wingmate.domain.AacLogger
import io.github.jdreioe.wingmate.domain.Base64Decoder
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.withLanguageOverride
import io.github.jdreioe.wingmate.application.VoiceUseCase
import org.koin.compose.koinInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import org.jetbrains.compose.resources.stringResource
import wingmatekmp.composeapp.generated.resources.Res
import wingmatekmp.composeapp.generated.resources.board_symbol_unavailable
import wingmatekmp.composeapp.generated.resources.board_symbol_unavailable_set
import wingmatekmp.composeapp.generated.resources.board_workspace_clear_sentence
import wingmatekmp.composeapp.generated.resources.board_workspace_delete_last
import wingmatekmp.composeapp.generated.resources.board_workspace_save_phrase
import wingmatekmp.composeapp.generated.resources.board_workspace_speak_sentence
import wingmatekmp.composeapp.generated.resources.board_workspace_home
import wingmatekmp.composeapp.generated.resources.board_cell_opens_board

enum class SentencePresentationMode { Normal, Fullscreen }

private data class BoardGridItem(
    val row: Int,
    val column: Int,
    val rowSpan: Int,
    val columnSpan: Int,
    val button: ObfButton?
)

@Composable
fun ObfBoardView(
    board: ObfBoard,
    onButtonClick: (ObfButton) -> Unit,
    modifier: Modifier = Modifier,
    extractedImages: Map<String, ByteArray> = emptyMap(),
    isEditMode: Boolean = false,
    selectedButtons: List<Pair<ObfButton, ImageBitmap?>> = emptyList(),
    onSpeakSentence: () -> Unit = {},
    onSaveSentence: () -> Unit = {},
    isSaveSentenceEnabled: Boolean = selectedButtons.isNotEmpty(),
    onDeleteLast: () -> Unit = {},
    onClearSentence: () -> Unit = {},
    showMessageBar: Boolean = !isEditMode,
    sentenceText: String = "",
    presentationMode: SentencePresentationMode = SentencePresentationMode.Normal,
    onCellClick: ((row: Int, column: Int, button: ObfButton?) -> Unit)? = null,
    onCellMove: ((fromRow: Int, fromColumn: Int, toRow: Int, toColumn: Int) -> Unit)? = null,
    homeBoardId: String? = null
) {
    val settings by rememberReactiveSettings()
    val imagesById = remember(board) { board.images.associateBy { it.id } }
    // Absolute positioning: if every button has top/left/width/height, render fractionally
    val isAbsoluteLayout = remember(board) { board.isAbsoluteLayout }
    // If grid is defined, use it. Otherwise, just listing buttons (fallback)
    val grid = board.grid
    val buttonsById = remember(board) { board.buttons.associateBy { it.id } }

    if (isAbsoluteLayout) {
        if (showMessageBar) {
            Column(modifier = modifier.fillMaxSize().padding(8.dp)) {
                SymbolBar(
                    selectedButtons = selectedButtons,
                    imagesById = imagesById,
                    extractedImages = extractedImages,
                    sentenceText = sentenceText,
                    presentationMode = presentationMode,
                    onSpeak = onSpeakSentence,
                    onSave = onSaveSentence,
                    isSaveEnabled = isSaveSentenceEnabled,
                    onDelete = onDeleteLast,
                    onClear = onClearSentence,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    renderAbsoluteButtons(board, imagesById, extractedImages, isEditMode, onButtonClick, homeBoardId)
                }
            }
        } else {
            BoxWithConstraints(modifier = modifier.fillMaxSize().padding(8.dp)) {
                renderAbsoluteButtons(board, imagesById, extractedImages, isEditMode, onButtonClick, homeBoardId)
            }
        }
    } else if (grid != null) {
        val columns = grid.columns.coerceAtLeast(1)
        val rows = grid.rows.coerceAtLeast(1)
        
        // Use Column/Row for fixed grid that fills the space
        Column(
            modifier = modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showMessageBar) {
                SymbolBar(
                    selectedButtons = selectedButtons,
                    imagesById = imagesById,
                    extractedImages = extractedImages,
                    sentenceText = sentenceText,
                    presentationMode = presentationMode,
                    onSpeak = onSpeakSentence,
                    onSave = onSaveSentence,
                    isSaveEnabled = isSaveSentenceEnabled,
                    onDelete = onDeleteLast,
                    onClear = onClearSentence,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))
            }

            val gridItems = remember(grid, buttonsById) {
                buildBoardGridItems(grid, buttonsById)
            }
            val pageScrollState = rememberScrollState()
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val minimumCellHeight = 96.dp * settings.inputFieldScale.coerceIn(0.5f, 2f)
                val minimumContentHeight = minimumCellHeight * rows + 8.dp * (rows - 1)
                val contentHeight = maxOf(maxHeight, minimumContentHeight)
                Box(modifier = Modifier.fillMaxSize().verticalScroll(pageScrollState)) {
                    SpanningBoardGrid(
                        rows = rows,
                        columns = columns,
                        items = gridItems,
                        modifier = Modifier.fillMaxWidth().height(contentHeight),
                        onMove = onCellMove
                    ) { item ->
                        val button = item.button
                        val isVisible = button != null && (!button.hidden || isEditMode)
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (button != null && isVisible) {
                                val image = button.imageId?.let { imagesById[it] }
                                ObfButtonItem(
                                    button = button,
                                    image = image,
                                    extractedImageBytes = button.imageId?.let {
                                        image?.path?.let { path -> extractedImages[path] }
                                    },
                                    onClick = {
                                        onCellClick?.invoke(item.row, item.column, button)
                                            ?: onButtonClick(button)
                                    },
                                    isEditMode = isEditMode,
                                    isHomeLink = button.isHomeNavigation(homeBoardId),
                                    boardStrings = board.strings,
                                    locale = settings.primaryLanguage
                                )
                            } else if (isEditMode && button == null) {
                                OutlinedCard(
                                    modifier = Modifier.fillMaxSize(),
                                    onClick = { onCellClick?.invoke(item.row, item.column, null) }
                                ) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "+",
                                            style = MaterialTheme.typography.headlineMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            } else {
                                Spacer(modifier = Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
    } else {
        // Fallback: scrollable grid for boards without explicit grid
        Column(
            modifier = modifier.padding(4.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            board.buttons.chunked(4).forEach { rowButtons ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    rowButtons.forEach { button ->
                        val image = button.imageId?.let { imagesById[it] }
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            ObfButtonItem(
                                button = button,
                                image = image,
                                extractedImageBytes = button.imageId?.let { 
                                    image?.path?.let { path -> extractedImages[path] }
                                },
                                onClick = { onButtonClick(button) },
                                isHomeLink = button.isHomeNavigation(homeBoardId),
                                boardStrings = board.strings,
                                locale = settings.primaryLanguage
                            )
                        }
                    }
                    // Fill remaining space if row is not complete
                    repeat(4 - rowButtons.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private fun buildBoardGridItems(
    grid: io.github.jdreioe.wingmate.domain.obf.ObfGrid,
    buttonsById: Map<String, ObfButton>
): List<BoardGridItem> {
    val rows = grid.rows.coerceAtLeast(1)
    val columns = grid.columns.coerceAtLeast(1)
    val order = List(rows) { row ->
        List(columns) { column -> grid.order.getOrNull(row)?.getOrNull(column) }
    }
    val visited = mutableSetOf<Pair<Int, Int>>()
    return buildList {
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                if ((row to column) in visited) continue
                val buttonId = order[row][column]
                if (buttonId == null) {
                    visited += row to column
                    add(BoardGridItem(row, column, 1, 1, null))
                    continue
                }
                val occurrences = buildList {
                    for (candidateRow in 0 until rows) {
                        for (candidateColumn in 0 until columns) {
                            if (order[candidateRow][candidateColumn] == buttonId) {
                                add(candidateRow to candidateColumn)
                            }
                        }
                    }
                }
                val minRow = occurrences.minOf { it.first }
                val maxRow = occurrences.maxOf { it.first }
                val minColumn = occurrences.minOf { it.second }
                val maxColumn = occurrences.maxOf { it.second }
                val isRectangle = (minRow..maxRow).all { candidateRow ->
                    (minColumn..maxColumn).all { candidateColumn ->
                        order[candidateRow][candidateColumn] == buttonId
                    }
                }
                if (isRectangle) {
                    visited += occurrences
                    add(
                        BoardGridItem(
                            row = minRow,
                            column = minColumn,
                            rowSpan = maxRow - minRow + 1,
                            columnSpan = maxColumn - minColumn + 1,
                            button = buttonsById[buttonId]
                        )
                    )
                } else {
                    visited += row to column
                    add(BoardGridItem(row, column, 1, 1, buttonsById[buttonId]))
                }
            }
        }
    }
}

@Composable
private fun SpanningBoardGrid(
    rows: Int,
    columns: Int,
    items: List<BoardGridItem>,
    modifier: Modifier = Modifier,
    onMove: ((fromRow: Int, fromColumn: Int, toRow: Int, toColumn: Int) -> Unit)? = null,
    content: @Composable (BoardGridItem) -> Unit
) {
    var dragSource by remember(items) { mutableStateOf<Pair<Int, Int>?>(null) }
    var dragTarget by remember(items) { mutableStateOf<Pair<Int, Int>?>(null) }
    val dragModifier = if (onMove != null) {
        Modifier.pointerInput(rows, columns, items, onMove) {
            val horizontalGap = 4.dp.toPx()
            val verticalGap = 8.dp.toPx()

            fun cellAt(position: Offset): Pair<Int, Int>? {
                if (position.x < 0f || position.y < 0f || position.x >= size.width || position.y >= size.height) {
                    return null
                }
                val cellWidth = (size.width - horizontalGap * (columns - 1)).coerceAtLeast(0f) / columns
                val cellHeight = (size.height - verticalGap * (rows - 1)).coerceAtLeast(0f) / rows
                val column = (position.x / (cellWidth + horizontalGap)).toInt().coerceIn(0, columns - 1)
                val row = (position.y / (cellHeight + verticalGap)).toInt().coerceIn(0, rows - 1)
                return row to column
            }

            fun fieldAt(cell: Pair<Int, Int>): BoardGridItem? = items.firstOrNull { item ->
                cell.first in item.row until item.row + item.rowSpan &&
                    cell.second in item.column until item.column + item.columnSpan
            }

            detectDragGesturesAfterLongPress(
                onDragStart = { position ->
                    val cell = cellAt(position)
                    val field = cell?.let(::fieldAt)?.takeIf { it.button != null }
                    dragSource = field?.let { it.row to it.column }
                    dragTarget = dragSource
                },
                onDrag = { change, _ ->
                    if (dragSource != null) {
                        change.consume()
                        dragTarget = cellAt(change.position)
                    }
                },
                onDragEnd = {
                    val source = dragSource
                    val target = dragTarget
                    dragSource = null
                    dragTarget = null
                    if (source != null && target != null && source != target) {
                        onMove(source.first, source.second, target.first, target.second)
                    }
                },
                onDragCancel = {
                    dragSource = null
                    dragTarget = null
                }
            )
        }
    } else {
        Modifier
    }
    Layout(
        modifier = modifier.then(dragModifier),
        content = {
            items.forEach { item ->
                val isDropTarget = dragTarget?.let { target ->
                    target.first in item.row until item.row + item.rowSpan &&
                        target.second in item.column until item.column + item.columnSpan
                } == true
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (isDropTarget) {
                                Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                            } else {
                                Modifier
                            }
                        )
                ) {
                    content(item)
                }
            }
        }
    ) { measurables, constraints ->
        val horizontalGap = 4.dp.roundToPx()
        val verticalGap = 8.dp.roundToPx()
        val availableWidth = (constraints.maxWidth - horizontalGap * (columns - 1)).coerceAtLeast(0)
        val availableHeight = (constraints.maxHeight - verticalGap * (rows - 1)).coerceAtLeast(0)
        val cellWidth = availableWidth / columns
        val cellHeight = availableHeight / rows
        val placeables = measurables.mapIndexed { index, measurable ->
            val item = items[index]
            val width = cellWidth * item.columnSpan + horizontalGap * (item.columnSpan - 1)
            val height = cellHeight * item.rowSpan + verticalGap * (item.rowSpan - 1)
            measurable.measure(Constraints.fixed(width.coerceAtLeast(0), height.coerceAtLeast(0)))
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { index, placeable ->
                val item = items[index]
                placeable.placeRelative(
                    x = item.column * (cellWidth + horizontalGap),
                    y = item.row * (cellHeight + verticalGap)
                )
            }
        }
    }
}

@Composable
fun SymbolBar(
    selectedButtons: List<Pair<ObfButton, ImageBitmap?>>,
    imagesById: Map<String, io.github.jdreioe.wingmate.domain.obf.ObfImage>,
    extractedImages: Map<String, ByteArray>,
    sentenceText: String = "",
    presentationMode: SentencePresentationMode = SentencePresentationMode.Normal,
    onSpeak: () -> Unit,
    onSave: () -> Unit,
    isSaveEnabled: Boolean,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val maxTextLines = when (presentationMode) {
        SentencePresentationMode.Normal -> 4
        SentencePresentationMode.Fullscreen -> 6
    }
    val textScrollState = rememberScrollState()
    val symbolsScrollState = rememberLazyListState()

    val hasContent = selectedButtons.isNotEmpty()
    val effectiveSentenceText = if (hasContent && sentenceText.isBlank()) {
        selectedButtons.joinToString(" ") { (button, _) ->
            (button.label ?: button.vocalization).orEmpty()
        }
    } else sentenceText

    val currentSentenceLength = effectiveSentenceText.length
    var previousLength by remember { mutableStateOf(currentSentenceLength) }
    LaunchedEffect(currentSentenceLength) {
        if (currentSentenceLength > previousLength) {
            val isNearBottom = textScrollState.value >= textScrollState.maxValue - 32
            if (isNearBottom) {
                textScrollState.animateScrollTo(textScrollState.maxValue)
            }
        }
        previousLength = currentSentenceLength
    }

    LaunchedEffect(selectedButtons.size) {
        if (selectedButtons.isNotEmpty()) {
            symbolsScrollState.animateScrollToItem(selectedButtons.size - 1)
        }
    }

    Surface(
        modifier = modifier.shadow(2.dp, RoundedCornerShape(16.dp)),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.widthIn(max = 1200.dp).padding(8.dp)) {
            if (effectiveSentenceText.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = (maxTextLines * 28).dp)
                        .verticalScroll(textScrollState)
                        .semantics {
                            contentDescription = effectiveSentenceText
                        },
                    contentAlignment = Alignment.TopStart
                ) {
                    Text(
                        text = effectiveSentenceText,
                        fontSize = 48.sp,
                        lineHeight = 56.sp,
                        maxLines = maxTextLines,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 64.dp, top = 4.dp, bottom = 4.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LazyRow(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    state = symbolsScrollState,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(selectedButtons, key = { it.first.id }) { (button, bitmap) ->
                        val resolvedBitmap = remember(button, bitmap) {
                            bitmap ?: button.imageId?.let { id ->
                                imagesById[id]?.path?.let { path ->
                                    extractedImages[path]?.toComposeImageBitmap()
                                }
                            }
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(60.dp)
                        ) {
                            if (resolvedBitmap != null) {
                                Image(
                                    bitmap = resolvedBitmap,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp))
                                )
                            }
                            Text(
                                text = button.label ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                VerticalDivider(modifier = Modifier.padding(horizontal = 8.dp).height(40.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledIconButton(
                        onClick = onSpeak,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = stringResource(Res.string.board_workspace_speak_sentence))
                    }
                    IconButton(onClick = onSave, enabled = isSaveEnabled) {
                        Icon(Icons.Default.Save, contentDescription = stringResource(Res.string.board_workspace_save_phrase))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(Res.string.board_workspace_delete_last))
                    }
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(Res.string.board_workspace_clear_sentence))
                    }
                }
            }
        }
    }
}

@Composable
fun ObfButtonItem(
    button: ObfButton,
    image: ObfImage? = null,
    extractedImageBytes: ByteArray? = null,
    onClick: () -> Unit,
    isEditMode: Boolean = false,
    isHomeLink: Boolean = false,
    boardStrings: Map<String, Map<String, String>> = emptyMap(),
    locale: String? = null
) {
    val speechService: SpeechService = koinInject()
    val voiceUseCase: VoiceUseCase = koinInject()
    val aacLogger: AacLogger = koinInject()
    val settings by rememberReactiveSettings()
    val displayLabel = resolveObfLocalizedString(boardStrings, locale, button.label)
    val displayVocalization = resolveObfLocalizedString(boardStrings, locale, button.vocalization)
    
    // Page links navigate immediately; pulsing the outgoing button makes the
    // destination page appear to animate as the grid composition is reused.
    val animateSelection = button.loadBoard == null
    var isSelected by remember(button.id) { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.1f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        finishedListener = { isSelected = false }
    )

    // Dwell logic state
    var isHovered by remember { mutableStateOf(false) }
    var dwellProgress by remember { mutableStateOf(0f) }
    
    // Stable scope for fire-and-forget speech (survives hover changes)
    val fishingScope = rememberCoroutineScope()

    LaunchedEffect(isHovered, settings.dwellToSelectMillis) {
        if (isHovered && settings.dwellToSelectMillis > 0 && !isEditMode) {
            val startTime = System.currentTimeMillis()
            val duration = settings.dwellToSelectMillis
            
            // Auditory Fishing: Whisper label on hover start (fire-and-forget)
            if (settings.auditoryFishingEnabled) {
                val label = displayLabel ?: displayVocalization ?: ""
                if (label.isNotBlank()) {
                    fishingScope.launch {
                        runCatching {
                            val voice = voiceUseCase.selected()
                                .withLanguageOverride(button.locale)
                            speechService.speak(label, voice, voice?.pitch, rate = 0.8)
                        }
                    }
                }
            }
            
            while (isHovered) {
                val now = System.currentTimeMillis()
                val elapsed = now - startTime
                dwellProgress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                
                if (elapsed >= duration) {
                    if (animateSelection) isSelected = true
                    onClick()
                    aacLogger.logButtonClick(displayLabel ?: "", phraseId = button.id)
                    dwellProgress = 0f
                    break
                }
                delay(16)
            }
        } else {
            dwellProgress = 0f
        }
    }

    // High Contrast Overrides
    val highContrastContainer = if (MaterialTheme.colorScheme.surface == Color.Black || settings.forceDarkTheme == true) Color.Black else Color.White
    val highContrastContent = if (highContrastContainer == Color.Black) Color.White else Color.Black
    
    val bgColor = if (settings.highContrastMode) {
        highContrastContainer
    } else {
        button.backgroundColor?.let { runCatching { parseHexToColor(it) }.getOrNull() } 
            ?: MaterialTheme.colorScheme.surfaceVariant
    }
    
    val borderColor = if (settings.highContrastMode) {
        highContrastContent
    } else {
        button.borderColor?.let { runCatching { parseHexToColor(it) }.getOrNull() }
    }
    
    val contentColor = when {
        settings.highContrastMode -> highContrastContent
        button.backgroundColor != null -> contrastingContentColor(bgColor)
        else -> MaterialTheme.colorScheme.onSurface
    }
    
    // Spec priority: data → path → url → symbol (extracted zip bytes count as path).
    val imageSource = remember(image) { resolveObfImageSource(image) }
    val syncBitmap = remember(imageSource, extractedImageBytes) {
        when {
            extractedImageBytes != null && imageSource is ObfImageSource.Path -> {
                runCatching { extractedImageBytes.toComposeImageBitmap() }.getOrNull()
            }
            imageSource is ObfImageSource.DataUri -> {
                runCatching {
                    val data = imageSource.data
                    val base64 = if (data.contains(",")) data.substringAfter(",") else data
                    val bytes = Base64Decoder.decode(base64)
                    bytes.toComposeImageBitmap()
                }.getOrNull()
            }
            extractedImageBytes != null -> {
                runCatching { extractedImageBytes.toComposeImageBitmap() }.getOrNull()
            }
            else -> null
        }
    }

    val imageBitmap = syncBitmap
    val imageModel = when (imageSource) {
        is ObfImageSource.Url -> imageSource.url
        is ObfImageSource.Path -> imageSource.path
        else -> null
    }
    val symbolUnavailable = imageSource is ObfImageSource.Symbol && imageBitmap == null
    
    Card(
        modifier = Modifier
            .fillMaxSize()
            .padding(if (settings.highContrastMode) 2.dp else 0.dp)
            .scale(scale)
            .alpha(if (button.hidden && isEditMode) 0.5f else 1f)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Enter -> isHovered = true
                            PointerEventType.Exit -> isHovered = false
                        }
                    }
                }
            }
            .let { baseModifier ->
                val primaryAction = { 
                    if (animateSelection) isSelected = true
                    onClick()
                    aacLogger.logButtonClick(displayLabel ?: "", phraseId = button.id)
                }
                
                if (settings.holdToSelectMillis > 0 && !isEditMode) {
                    baseModifier.pointerInput(settings.holdToSelectMillis) {
                        detectTapGestures(
                            onPress = {
                                val completed = withTimeoutOrNull(settings.holdToSelectMillis) {
                                    tryAwaitRelease()
                                    false
                                } ?: true
                                if (completed) {
                                    primaryAction()
                                    tryAwaitRelease()
                                }
                            }
                        )
                    }
                } else {
                    baseModifier.combinedClickable(
                        onClick = { primaryAction() }
                    )
                }
            },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = bgColor),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        border = if (borderColor != null || settings.highContrastMode) {
             androidx.compose.foundation.BorderStroke(if (settings.highContrastMode) 3.dp else 2.dp, borderColor ?: highContrastContent)
        } else null,
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.Center) {
            // Dwell Progress Overlay
            if (dwellProgress > 0f) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = 4.dp.toPx()
                    drawArc(
                        color = contentColor.copy(alpha = 0.3f),
                        startAngle = -90f,
                        sweepAngle = 360f * dwellProgress,
                        useCenter = false,
                        style = Stroke(width = strokeWidth)
                    )
                }
            }
            
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                val showImg = settings.showSymbols &&
                    (imageBitmap != null || !imageModel.isNullOrBlank() || symbolUnavailable)
                val showLbl = settings.showLabels && !(displayLabel.isNullOrBlank() && displayVocalization.isNullOrBlank())

                if (settings.labelAtTop && showImg && showLbl) {
                    val labelText = displayLabel ?: displayVocalization ?: ""
                    Text(
                        text = labelText,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.Center,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    if (symbolUnavailable) {
                        SymbolUnavailablePlaceholder(
                            symbolSet = imageSource.symbol.set,
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(2.dp),
                            contentColor = contentColor
                        )
                    } else {
                        BoardSymbolImage(
                            bitmap = imageBitmap,
                            model = imageModel,
                            contentDescription = displayLabel,
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(2.dp)
                        )
                    }
                } else {
                    // Normal order (Image at Top)
                    if (showImg) {
                        if (symbolUnavailable) {
                            SymbolUnavailablePlaceholder(
                                symbolSet = imageSource.symbol.set,
                                modifier = Modifier.weight(1f).fillMaxWidth().padding(2.dp),
                                contentColor = contentColor
                            )
                        } else {
                            BoardSymbolImage(
                                bitmap = imageBitmap,
                                model = imageModel,
                                contentDescription = button.label,
                                modifier = Modifier.weight(1f).fillMaxWidth().padding(2.dp)
                            )
                        }
                    }
                    if (showLbl) {
                        val labelText = displayLabel ?: displayVocalization ?: ""
                        Text(
                            text = labelText,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            textAlign = TextAlign.Center,
                            color = contentColor,
                            maxLines = if (showImg) 1 else 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (button.loadBoard != null || isHomeLink) {
                val destinationDescription = if (isHomeLink) {
                    stringResource(Res.string.board_workspace_home)
                } else {
                    stringResource(
                        Res.string.board_cell_opens_board,
                        button.loadBoard?.name.orEmpty()
                    )
                }
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).size(28.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    tonalElevation = 3.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isHomeLink) {
                                Icons.Default.Home
                            } else {
                                Icons.AutoMirrored.Filled.ArrowForward
                            },
                            contentDescription = destinationDescription,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxWithConstraintsScope.renderAbsoluteButtons(
    board: ObfBoard,
    imagesById: Map<String, ObfImage>,
    extractedImages: Map<String, ByteArray>,
    isEditMode: Boolean,
    onButtonClick: (ObfButton) -> Unit,
    homeBoardId: String?
) {
    val containerWidth = maxWidth
    val containerHeight = maxHeight
    board.buttons.forEach { button ->
        val left = (button.left ?: 0.0) * containerWidth.value
        val top = (button.top ?: 0.0) * containerHeight.value
        val w = (button.width ?: 0.1) * containerWidth.value
        val h = (button.height ?: 0.1) * containerHeight.value
        if (!button.hidden || isEditMode) {
            val image = button.imageId?.let { imagesById[it] }
            Box(
                modifier = Modifier
                    .offset(x = left.dp, y = top.dp)
                    .size(width = w.dp, height = h.dp)
            ) {
                ObfButtonItem(
                    button = button,
                    image = image,
                    extractedImageBytes = button.imageId?.let {
                        image?.path?.let { path -> extractedImages[path] }
                    },
                    onClick = { onButtonClick(button) },
                    isEditMode = isEditMode,
                    isHomeLink = button.isHomeNavigation(homeBoardId)
                )
            }
        }
    }
}

private fun ObfButton.isHomeNavigation(homeBoardId: String?): Boolean =
    resolvedActions().any { it.trim().equals(":home", ignoreCase = true) } ||
        (homeBoardId != null && loadBoard?.id == homeBoardId)

@Composable
private fun BoardSymbolImage(
    bitmap: ImageBitmap?,
    model: String?,
    contentDescription: String?,
    modifier: Modifier
) {
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = modifier
        )
    } else {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = modifier
        )
    }
}

@Composable
private fun SymbolUnavailablePlaceholder(
    symbolSet: String?,
    modifier: Modifier,
    contentColor: Color
) {
    val message = if (symbolSet.isNullOrBlank()) {
        stringResource(Res.string.board_symbol_unavailable)
    } else {
        stringResource(Res.string.board_symbol_unavailable_set, symbolSet)
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}
