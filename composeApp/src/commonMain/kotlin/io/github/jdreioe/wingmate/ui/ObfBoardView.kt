package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import io.github.jdreioe.wingmate.ui.toComposeImageBitmap
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.draw.scale
import io.github.jdreioe.wingmate.domain.AacLogger
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.withLanguageOverride
import io.github.jdreioe.wingmate.application.VoiceUseCase
import org.koin.compose.koinInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import org.jetbrains.compose.resources.stringResource
import wingmatekmp.composeapp.generated.resources.Res
import wingmatekmp.composeapp.generated.resources.board_workspace_clear_sentence
import wingmatekmp.composeapp.generated.resources.board_workspace_delete_last
import wingmatekmp.composeapp.generated.resources.board_workspace_save_phrase
import wingmatekmp.composeapp.generated.resources.board_workspace_speak_sentence

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
    showSentenceText: Boolean = false,
    onCellClick: ((row: Int, column: Int, button: ObfButton?) -> Unit)? = null
) {
    val settings by rememberReactiveSettings()
    val imagesById = remember(board) { board.images.associateBy { it.id } }
    // If grid is defined, use it. Otherwise, just listing buttons (fallback)
    val grid = board.grid
    val buttonsById = remember(board) { board.buttons.associateBy { it.id } }

    if (grid != null) {
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
                    onSpeak = onSpeakSentence,
                    onSave = onSaveSentence,
                    isSaveEnabled = isSaveSentenceEnabled,
                    onDelete = onDeleteLast,
                    onClear = onClearSentence,
                    showSentenceText = showSentenceText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (showSentenceText) 260.dp else 100.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))
            }

            val gridItems = remember(grid, buttonsById) {
                buildBoardGridItems(grid, buttonsById)
            }
            SpanningBoardGrid(
                rows = rows,
                columns = columns,
                items = gridItems,
                modifier = Modifier.weight(1f).fillMaxWidth()
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
                            isEditMode = isEditMode
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
                                onClick = { onButtonClick(button) }
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
    content: @Composable (BoardGridItem) -> Unit
) {
    Layout(
        modifier = modifier,
        content = {
            items.forEach { item -> content(item) }
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
    onSpeak: () -> Unit,
    onSave: () -> Unit,
    isSaveEnabled: Boolean,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    showSentenceText: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.shadow(2.dp, RoundedCornerShape(16.dp)),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            if (showSentenceText) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = selectedButtons.joinToString(" ") { (button, _) ->
                            (button.label ?: button.vocalization).orEmpty()
                        },
                        fontSize = 48.sp,
                        lineHeight = 56.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 64.dp, top = 4.dp, bottom = 4.dp)
                    )
                }
            }

            Row(
                modifier = if (showSentenceText) {
                    Modifier.fillMaxWidth().height(80.dp)
                } else {
                    Modifier.weight(1f).fillMaxWidth()
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                LazyRow(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(selectedButtons) { (button, bitmap) ->
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
    isEditMode: Boolean = false
) {
    val speechService: SpeechService = koinInject()
    val voiceUseCase: VoiceUseCase = koinInject()
    val aacLogger: AacLogger = koinInject()
    val settings by rememberReactiveSettings()
    
    // Pulse animation state
    var isSelected by remember { mutableStateOf(false) }
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
                val label = button.label ?: button.vocalization ?: ""
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
                
                if (elapsed.toLong() >= duration.toLong()) {
                    isSelected = true
                    onClick()
                    aacLogger.logButtonClick(button.label ?: "", phraseId = button.id)
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
    
    // Try to load image from various sources synchronously first
    val syncBitmap = remember(image, extractedImageBytes) {
        extractedImageBytes?.let { bytes ->
            runCatching { bytes.toComposeImageBitmap() }.getOrNull()
        } ?: image?.data?.let { data ->
            runCatching {
                val base64 = if (data.contains(",")) data.substringAfter(",") else data
                val bytes = java.util.Base64.getDecoder().decode(base64)
                bytes.toComposeImageBitmap()
            }.getOrNull()
        }
    }
    
    val imageBitmap = syncBitmap
    val imageModel = image?.url ?: image?.path
    
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
                    isSelected = true
                    onClick()
                    aacLogger.logButtonClick(button.label ?: "", phraseId = button.id)
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
                val showImg = settings.showSymbols && (imageBitmap != null || !imageModel.isNullOrBlank())
                val showLbl = settings.showLabels && !(button.label.isNullOrBlank() && button.vocalization.isNullOrBlank())

                if (settings.labelAtTop && showImg && showLbl) {
                    // Label at Top
                    val labelText = button.label ?: button.vocalization ?: ""
                    Text(
                        text = labelText,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.Center,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    BoardSymbolImage(
                        bitmap = imageBitmap,
                        model = imageModel,
                        contentDescription = button.label,
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(2.dp)
                    )
                } else {
                    // Normal order (Image at Top)
                    if (showImg) {
                        BoardSymbolImage(
                            bitmap = imageBitmap,
                            model = imageModel,
                            contentDescription = button.label,
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(2.dp)
                        )
                    }
                    if (showLbl) {
                        val labelText = button.label ?: button.vocalization ?: ""
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
        }
    }
}

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
