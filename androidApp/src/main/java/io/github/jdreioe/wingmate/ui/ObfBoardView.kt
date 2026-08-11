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
import io.github.jdreioe.wingmate.domain.obf.ObfMediaSource
import io.github.jdreioe.wingmate.domain.obf.obfImageSources
import io.github.jdreioe.wingmate.domain.obf.ObfButtonActionEffect
import io.github.jdreioe.wingmate.domain.obf.ObfButtonType
import io.github.jdreioe.wingmate.domain.obf.parseObfButtonActions
import io.github.jdreioe.wingmate.domain.obf.resolveObfLocalizedString
import io.github.jdreioe.wingmate.domain.obf.fieldItems
import io.github.jdreioe.wingmate.domain.obf.isBoardButtonVisible
import io.github.jdreioe.wingmate.domain.obf.fieldFontScale
import io.github.jdreioe.wingmate.domain.obf.ResolvedBoardSettings
import io.github.jdreioe.wingmate.domain.obf.GridFieldSpan
import io.github.jdreioe.wingmate.domain.obf.resolveBoardSettings
import io.github.jdreioe.wingmate.application.SelectionDebouncer
import kotlin.time.Clock
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Visibility
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import io.github.jdreioe.wingmate.domain.AacLogger
import io.github.jdreioe.wingmate.domain.Base64Decoder
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.withLanguageOverride
import io.github.jdreioe.wingmate.domain.obf.resolvedBackgroundColor
import io.github.jdreioe.wingmate.application.VoiceUseCase
import org.koin.compose.koinInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource

import com.hojmoseit.wingmate.R
internal data class BoardGridItem(
    val row: Int,
    val column: Int,
    val rowSpan: Int,
    val columnSpan: Int,
    val button: ObfButton?
)

internal class HiddenButtonsSession {
    var revealed by mutableStateOf(false)
        private set

    fun toggle() {
        revealed = !revealed
    }

    fun reset() {
        revealed = false
    }
}

enum class SymbolBarPresentation(val maxTextLines: Int, val maximumViewportFraction: Float) {
    Normal(maxTextLines = 4, maximumViewportFraction = 0.5f),
    Fullscreen(maxTextLines = 6, maximumViewportFraction = 0.6f)
}

@Composable
fun ObfBoardView(
    board: ObfBoard,
    onButtonClick: (ObfButton) -> Unit,
    modifier: Modifier = Modifier,
    extractedImages: Map<String, ByteArray> = emptyMap(),
    isEditMode: Boolean = false,
    selectedButtons: List<Pair<ObfButton, ImageBitmap?>> = emptyList(),
    onSpeakSentence: () -> Unit = {},
    onDeleteLast: () -> Unit = {},
    onClearSentence: () -> Unit = {},
    showSpeakControl: Boolean = true,
    showDeleteControl: Boolean = true,
    showClearControl: Boolean = true,
    showMessageBar: Boolean = !isEditMode,
    sentenceText: String = "",
    symbolBarPresentation: SymbolBarPresentation = SymbolBarPresentation.Normal,
    boardSettings: ResolvedBoardSettings? = null,
    showHiddenButtons: Boolean = false,
    predictionLabels: Map<String, String> = emptyMap(),
    highlightedButtonId: String? = null,
    onCellClick: ((row: Int, column: Int, button: ObfButton?) -> Unit)? = null,
    onCellMove: ((fromRow: Int, fromColumn: Int, toRow: Int, toColumn: Int) -> Unit)? = null,
    selectedFieldAnchor: Pair<Int, Int>? = null,
    selectedFieldSpans: List<GridFieldSpan> = emptyList(),
    onResizeField: ((anchorRow: Int, anchorColumn: Int, rowSpan: Int, columnSpan: Int) -> Unit)? = null,
    onGridHeightFractionChange: ((Float) -> Unit)? = null,
    homeBoardId: String? = null
) {
    val settings by rememberReactiveSettings()
    val boardBackground = if (settings.highContrastMode) {
        MaterialTheme.colorScheme.background
    } else {
        parseObfColorOrNull(board.backgroundColor) ?: MaterialTheme.colorScheme.background
    }
    val effectiveBoardSettings = boardSettings ?: resolveBoardSettings(
        appShowLabels = settings.showLabels,
        appShowSymbols = settings.showSymbols,
        appLabelAtTop = settings.labelAtTop,
        appShowMessageBar = settings.boardShowMessageBar,
        appActivationBehavior = settings.boardActivationBehavior,
        appReturnBehavior = settings.boardReturnBehavior
    )
    val imagesById = remember(board) { board.images.associateBy { it.id } }
    // Absolute positioning: if every button has top/left/width/height, render fractionally
    val isAbsoluteLayout = remember(board) { board.isAbsoluteLayout }
    // If grid is defined, use it. Otherwise, just listing buttons (fallback)
    val grid = board.grid
    val buttonsById = remember(board) { board.buttons.associateBy { it.id } }

    if (isAbsoluteLayout) {
        if (showMessageBar) {
            BoxWithConstraints(modifier = modifier.fillMaxSize().background(boardBackground)) {
                val symbolBarMaxHeight = maxHeight * symbolBarPresentation.maximumViewportFraction
                Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    SymbolBar(
                        selectedButtons = selectedButtons,
                        sentenceText = sentenceText,
                        imagesById = imagesById,
                        extractedImages = extractedImages,
                        onSpeak = onSpeakSentence,
                        onDelete = onDeleteLast,
                        onClear = onClearSentence,
                        showSpeak = showSpeakControl,
                        showDelete = showDeleteControl,
                        showClear = showClearControl,
                        presentation = symbolBarPresentation,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = symbolBarMaxHeight)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        renderAbsoluteButtons(
                            board,
                            imagesById,
                            extractedImages,
                            isEditMode,
                            onButtonClick,
                            homeBoardId,
                            effectiveBoardSettings,
                            showHiddenButtons,
                            predictionLabels,
                            highlightedButtonId
                        )
                    }
                }
            }
        } else {
            BoxWithConstraints(modifier = modifier.fillMaxSize().background(boardBackground).padding(8.dp)) {
                renderAbsoluteButtons(
                    board,
                    imagesById,
                    extractedImages,
                    isEditMode,
                    onButtonClick,
                    homeBoardId,
                    effectiveBoardSettings,
                    showHiddenButtons,
                    predictionLabels,
                    highlightedButtonId
                )
            }
        }
    } else if (grid != null) {
        val columns = grid.columns.coerceAtLeast(1)
        val rows = grid.rows.coerceAtLeast(1)
        
        // Use Column/Row for fixed grid that fills the space
        BoxWithConstraints(modifier = modifier.fillMaxSize().background(boardBackground)) {
            val symbolBarMaxHeight = maxHeight * symbolBarPresentation.maximumViewportFraction
            Column(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (showMessageBar) {
                    SymbolBar(
                        selectedButtons = selectedButtons,
                        sentenceText = sentenceText,
                        imagesById = imagesById,
                        extractedImages = extractedImages,
                        onSpeak = onSpeakSentence,
                        onDelete = onDeleteLast,
                        onClear = onClearSentence,
                        showSpeak = showSpeakControl,
                        showDelete = showDeleteControl,
                        showClear = showClearControl,
                        presentation = symbolBarPresentation,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = symbolBarMaxHeight)
                    )
                }

                val gridItems = remember(grid, buttonsById) {
                    buildBoardGridItems(grid, buttonsById)
                }
                val pageScrollState = rememberScrollState()
                var focusedCell by remember(board.id) { mutableStateOf<Pair<Int, Int>?>(null) }
                val isVisible: (ObfButton) -> Boolean = {
                    isBoardButtonVisible(it, isEditMode, showHiddenButtons)
                }
                val buttonAtCell: (Int, Int) -> ObfButton? = { row, column ->
                    boardCellButton(grid, buttonsById, isVisible, row, column)
                }
                val activateFocused: () -> Unit = {
                    focusedCell?.let { cell ->
                        buttonAtCell(cell.first, cell.second)?.let { button ->
                            onCellClick?.invoke(cell.first, cell.second, button) ?: onButtonClick(button)
                        }
                    }
                }
                val gridNavModifier = if (!isEditMode) {
                    Modifier
                        .fillMaxSize()
                        .testTag("board-grid")
                        .focusable()
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (event.key) {
                                Key.DirectionLeft -> {
                                    focusedCell = stepFocusableBoardCell(grid, buttonsById, isVisible, focusedCell, 0, -1); true
                                }
                                Key.DirectionRight -> {
                                    focusedCell = stepFocusableBoardCell(grid, buttonsById, isVisible, focusedCell, 0, 1); true
                                }
                                Key.DirectionUp -> {
                                    focusedCell = stepFocusableBoardCell(grid, buttonsById, isVisible, focusedCell, -1, 0); true
                                }
                                Key.DirectionDown -> {
                                    focusedCell = stepFocusableBoardCell(grid, buttonsById, isVisible, focusedCell, 1, 0); true
                                }
                                Key.MoveHome -> {
                                    focusedCell = firstFocusableBoardCell(grid, buttonsById, isVisible); true
                                }
                                Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
                                    activateFocused(); true
                                }
                                else -> false
                            }
                        }
                } else {
                    Modifier.fillMaxSize()
                }
                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val availableGridHeight = maxHeight
                    val minimumCellHeight = (if (board.compactGrid) 72.dp else 96.dp) *
                        settings.inputFieldScale.coerceIn(0.5f, 2f)
                    val minimumContentHeight = minimumCellHeight * rows + 8.dp * (rows - 1)
                    val defaultHeightFraction = if (board.compactGrid) {
                        (minimumContentHeight / availableGridHeight).coerceIn(0.15f, 1f)
                    } else {
                        1f
                    }
                    var previewHeightFraction by remember(
                        board.id,
                        board.gridHeightFraction,
                        availableGridHeight
                    ) {
                        mutableFloatStateOf(board.gridHeightFraction ?: defaultHeightFraction)
                    }
                    val contentHeight = availableGridHeight * previewHeightFraction
                    Box(modifier = Modifier.fillMaxSize().verticalScroll(pageScrollState)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(contentHeight)
                                .align(Alignment.BottomCenter)
                        ) {
                            SpanningBoardGrid(
                                rows = rows,
                                columns = columns,
                                items = gridItems,
                                modifier = gridNavModifier,
                                onMove = onCellMove,
                                selectedField = selectedFieldAnchor,
                                selectedFieldSpans = selectedFieldSpans,
                                onResizeField = onResizeField,
                                focusedCell = focusedCell
                            ) { item ->
                            val button = item.button
                            val isVisible = button != null && isBoardButtonVisible(button, isEditMode, showHiddenButtons)
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
                                        isTemporarilyRevealed = button.hidden && !isEditMode && showHiddenButtons,
                                        isHomeLink = button.isHomeNavigation(homeBoardId),
                                        boardStrings = board.strings,
                                        locale = settings.primaryLanguage,
                                        boardSettings = effectiveBoardSettings,
                                        labelOverride = predictionLabels[button.id],
                                        isSelectionHighlighted = button.id == highlightedButtonId,
                                        fieldFontScale = fieldFontScale(item.rowSpan, item.columnSpan)
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
                            if (isEditMode && onGridHeightFractionChange != null) {
                                GridHeightResizeHandle(
                                    currentFraction = previewHeightFraction,
                                    availableHeight = availableGridHeight,
                                    onFractionPreview = { previewHeightFraction = it },
                                    onFractionCommit = onGridHeightFractionChange,
                                    modifier = Modifier.align(Alignment.TopCenter)
                                )
                            }
                        }
                    }
                }
            }
        }
    } else {
        // Fallback: scrollable grid for boards without explicit grid
        BoxWithConstraints(modifier = modifier.fillMaxSize().background(boardBackground)) {
            val symbolBarMaxHeight = maxHeight * symbolBarPresentation.maximumViewportFraction
            Column(
                modifier = Modifier.fillMaxSize().padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (showMessageBar) {
                    SymbolBar(
                        selectedButtons = selectedButtons,
                        sentenceText = sentenceText,
                        imagesById = imagesById,
                        extractedImages = extractedImages,
                        onSpeak = onSpeakSentence,
                        onDelete = onDeleteLast,
                        onClear = onClearSentence,
                        showSpeak = showSpeakControl,
                        showDelete = showDeleteControl,
                        showClear = showClearControl,
                        presentation = symbolBarPresentation,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = symbolBarMaxHeight)
                    )
                }
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    board.buttons.filter { isBoardButtonVisible(it, isEditMode, showHiddenButtons) }.chunked(4).forEach { rowButtons ->
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
                                        isEditMode = isEditMode,
                                        isTemporarilyRevealed = button.hidden && !isEditMode && showHiddenButtons,
                                        isHomeLink = button.isHomeNavigation(homeBoardId),
                                        boardStrings = board.strings,
                                        locale = settings.primaryLanguage,
                                        boardSettings = effectiveBoardSettings,
                                        labelOverride = predictionLabels[button.id]
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
        }
    }

internal fun buildBoardGridItems(
    grid: io.github.jdreioe.wingmate.domain.obf.ObfGrid,
    buttonsById: Map<String, ObfButton>
): List<BoardGridItem> = grid.fieldItems().map { field ->
    BoardGridItem(
        row = field.row,
        column = field.column,
        rowSpan = field.rowSpan,
        columnSpan = field.columnSpan,
        button = field.buttonId?.let { buttonsById[it] }
    )
}

@Composable
internal fun SpanningBoardGrid(
    rows: Int,
    columns: Int,
    items: List<BoardGridItem>,
    modifier: Modifier = Modifier,
    onMove: ((fromRow: Int, fromColumn: Int, toRow: Int, toColumn: Int) -> Unit)? = null,
    selectedField: Pair<Int, Int>? = null,
    selectedFieldSpans: List<GridFieldSpan> = emptyList(),
    onResizeField: ((anchorRow: Int, anchorColumn: Int, rowSpan: Int, columnSpan: Int) -> Unit)? = null,
    focusedCell: Pair<Int, Int>? = null,
    content: @Composable (BoardGridItem) -> Unit
) {
    var dragSource by remember(items) { mutableStateOf<Pair<Int, Int>?>(null) }
    var dragTarget by remember(items) { mutableStateOf<Pair<Int, Int>?>(null) }
    var resizeCell by remember(items) { mutableStateOf<Pair<Int, Int>?>(null) }
    var gridSizePx by remember { mutableStateOf(IntSize.Zero) }
    var handleOffsetInGrid by remember { mutableStateOf(Offset.Zero) }
    var resizeStatus by remember { mutableStateOf<String?>(null) }
    val density = LocalDensity.current
    val horizontalGap = with(density) { 4.dp.toPx() }
    val verticalGap = with(density) { 8.dp.toPx() }

    LaunchedEffect(resizeStatus) {
        if (resizeStatus != null) {
            delay(2000)
            resizeStatus = null
        }
    }

    val selectedItem = remember(items, selectedField) {
        items.firstOrNull { item ->
            selectedField != null &&
                item.row == selectedField.first &&
                item.column == selectedField.second &&
                item.button != null
        }
    }
    val allowedSpans = remember(selectedFieldSpans) { selectedFieldSpans.toSet() }
    val previewSpan = remember(resizeCell, selectedItem) {
        val item = selectedItem ?: return@remember null
        val cell = resizeCell ?: return@remember null
        GridFieldSpan(
            rows = (cell.first - item.row + 1).coerceAtLeast(1),
            columns = (cell.second - item.column + 1).coerceAtLeast(1)
        )
    }
    val previewValid = previewSpan?.let { it in allowedSpans } ?: true
    val showPreview = resizeCell != null && selectedItem != null
    val showHandle = selectedItem != null && onResizeField != null

    val selectionColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
    val focusColor = MaterialTheme.colorScheme.tertiary
    val resizeLabel = stringResource(R.string.board_resize_field_label)
    val increaseWidthLabel = stringResource(R.string.board_resize_increase_width)
    val decreaseWidthLabel = stringResource(R.string.board_resize_decrease_width)
    val increaseHeightLabel = stringResource(R.string.board_resize_increase_height)
    val decreaseHeightLabel = stringResource(R.string.board_resize_decrease_height)
    val sizeAnnouncementFormat = stringResource(R.string.board_resize_size)
    val blockedBoundsMessage = stringResource(R.string.board_resize_blocked_bounds)
    val blockedOccupiedMessage = stringResource(R.string.board_resize_blocked_occupied)

    fun cellAt(position: Offset): Pair<Int, Int>? {
        if (position.x < 0f || position.y < 0f) return null
        if (position.x >= gridSizePx.width || position.y >= gridSizePx.height) return null
        val cellWidth = (gridSizePx.width - horizontalGap * (columns - 1)).coerceAtLeast(0f) / columns
        val cellHeight = (gridSizePx.height - verticalGap * (rows - 1)).coerceAtLeast(0f) / rows
        val column = (position.x / (cellWidth + horizontalGap)).toInt().coerceIn(0, columns - 1)
        val row = (position.y / (cellHeight + verticalGap)).toInt().coerceIn(0, rows - 1)
        return row to column
    }

    fun announceSize(width: Int, height: Int) {
        resizeStatus = String.format(sizeAnnouncementFormat, width, height)
    }

    fun blockedReason(span: GridFieldSpan): String {
        val item = selectedItem ?: return ""
        val outOfBounds = span.rows < 1 || span.columns < 1 ||
            item.row + span.rows > rows || item.column + span.columns > columns
        return if (outOfBounds) blockedBoundsMessage else blockedOccupiedMessage
    }

    fun resizeBy(deltaRows: Int, deltaColumns: Int): Boolean {
        val item = selectedItem ?: return false
        val current = GridFieldSpan(item.rowSpan, item.columnSpan)
        val target = GridFieldSpan(current.rows + deltaRows, current.columns + deltaColumns)
        if (target in allowedSpans && target != current) {
            onResizeField?.invoke(item.row, item.column, target.rows, target.columns)
            announceSize(target.columns, target.rows)
            return true
        }
        resizeStatus = blockedReason(target)
        return false
    }

    val currentSelectedItem by rememberUpdatedState(selectedItem)
    val currentAllowedSpans by rememberUpdatedState(allowedSpans)
    val currentOnResizeField by rememberUpdatedState(onResizeField)
    val currentCellAt: (Offset) -> Pair<Int, Int>? by rememberUpdatedState { position ->
        cellAt(position + handleOffsetInGrid)
    }

    val handleModifier = Modifier
        .size(48.dp)
        .testTag("resize-handle")
        .onGloballyPositioned { handleOffsetInGrid = it.positionInParent() }
        .focusable()
        .onKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.isShiftPressed) {
                when (event.key) {
                    Key.DirectionRight -> resizeBy(0, 1)
                    Key.DirectionLeft -> resizeBy(0, -1)
                    Key.DirectionDown -> resizeBy(1, 0)
                    Key.DirectionUp -> resizeBy(-1, 0)
                    else -> false
                }
            } else {
                false
            }
        }
        .semantics {
            contentDescription = resizeLabel
            customActions = listOf(
                CustomAccessibilityAction(increaseWidthLabel) { resizeBy(0, 1) },
                CustomAccessibilityAction(decreaseWidthLabel) { resizeBy(0, -1) },
                CustomAccessibilityAction(increaseHeightLabel) { resizeBy(1, 0) },
                CustomAccessibilityAction(decreaseHeightLabel) { resizeBy(-1, 0) }
            )
        }
        .pointerInput(items, allowedSpans) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val touchAnchorAdjustment = Offset(
                    x = size.width / 2f - down.position.x,
                    y = size.height / 2f - down.position.y
                )
                val start = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    ?: return@awaitEachGesture
                val startCell = currentCellAt(start.position + touchAnchorAdjustment)
                resizeCell = startCell
                var lastCell = startCell
                var endedOutOfBounds = startCell == null
                drag(down.id) { change ->
                    change.consume()
                    val cell = currentCellAt(change.position + touchAnchorAdjustment)
                    endedOutOfBounds = cell == null
                    cell?.let { lastCell = it }
                    resizeCell = lastCell
                }
                val item = currentSelectedItem
                val finalCell = lastCell
                val span = if (item != null && finalCell != null) {
                    GridFieldSpan(
                        rows = (finalCell.first - item.row + 1).coerceAtLeast(1),
                        columns = (finalCell.second - item.column + 1).coerceAtLeast(1)
                    )
                } else {
                    null
                }
                resizeCell = null
                if (item != null && endedOutOfBounds) {
                    resizeStatus = blockedBoundsMessage
                } else if (item != null && span != null) {
                    val current = GridFieldSpan(item.rowSpan, item.columnSpan)
                    if (span != current && span in currentAllowedSpans) {
                        currentOnResizeField?.invoke(item.row, item.column, span.rows, span.columns)
                        announceSize(span.columns, span.rows)
                    } else if (span != current) {
                        resizeStatus = blockedReason(span)
                    }
                }
            }
        }

    val dragModifier = if (onMove != null) {
        Modifier.pointerInput(rows, columns, items, onMove) {
            detectDragGesturesAfterLongPress(
                onDragStart = { position ->
                    val cell = cellAt(position)
                    val field = cell?.let { cellAt ->
                        items.firstOrNull { item ->
                            cellAt.first in item.row until item.row + item.rowSpan &&
                                cellAt.second in item.column until item.column + item.columnSpan
                        }
                    }?.takeIf { it.button != null }
                    dragSource = field?.let { it.row to it.column }
                    dragTarget = dragSource
                },
                onDrag = { change, _ ->
                    if (dragSource != null) {
                        change.consume()
                        cellAt(change.position)?.let { dragTarget = it }
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
    Box(modifier = modifier) {
        Layout(
            modifier = Modifier
                .fillMaxSize()
                .then(dragModifier)
                .onSizeChanged { gridSizePx = it },
            content = {
                items.forEach { item ->
                    key(item.row, item.column, item.button?.id) {
                        val isDropTarget = dragTarget?.let { target ->
                            target.first in item.row until item.row + item.rowSpan &&
                                target.second in item.column until item.column + item.columnSpan
                        } == true
                        val isSelected = selectedItem?.let {
                            it.row == item.row && it.column == item.column
                        } == true
                        val isFocused = focusedCell?.let { focus ->
                            focus.first in item.row until item.row + item.rowSpan &&
                                focus.second in item.column until item.column + item.columnSpan
                        } == true && item.button != null
                        val ringShape = item.button?.shape?.toShape() ?: squareShape()
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (isSelected) {
                                        Modifier.border(3.dp, selectionColor, ringShape)
                                    } else {
                                        Modifier
                                    }
                                )
                                .then(
                                    if (isDropTarget) {
                                        Modifier.border(3.dp, selectionColor, ringShape)
                                    } else {
                                        Modifier
                                    }
                                )
                                .then(
                                    if (isFocused) {
                                        Modifier
                                            .testTag("board-focus-ring")
                                            .border(3.dp, focusColor, ringShape)
                                    } else {
                                        Modifier
                                    }
                                )
                        ) {
                            content(item)
                        }
                    }
            }
            if (showPreview) {
                key("resize-preview") {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("resize-preview")
                            .semantics {
                                contentDescription = if (previewValid) {
                                    "resize-preview-valid"
                                } else {
                                    "resize-preview-invalid"
                                }
                            }
                            .border(
                                3.dp,
                                if (previewValid) selectionColor else errorColor,
                                RoundedCornerShape(12.dp)
                            )
                            .background(
                                if (previewValid) {
                                    selectionColor.copy(alpha = 0.2f)
                                } else {
                                    errorColor.copy(alpha = 0.2f)
                                }
                            )
                    )
                }
            }
            if (showHandle) {
                key("resize-handle") {
                    Box(modifier = handleModifier, contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .border(2.dp, selectionColor, RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.surface)
                        )
                    }
                }
            }
        }
    ) { measurables, constraints ->
        val horizontalGapPx = 4.dp.roundToPx()
        val verticalGapPx = 8.dp.roundToPx()
        val availableWidth = (constraints.maxWidth - horizontalGapPx * (columns - 1)).coerceAtLeast(0)
        val availableHeight = (constraints.maxHeight - verticalGapPx * (rows - 1)).coerceAtLeast(0)
        val cellWidth = availableWidth / columns
        val cellHeight = availableHeight / rows
        val handlePx = 48.dp.toPx()
        val previewIndex = items.size
        val handleIndex = items.size + if (showPreview) 1 else 0
        val handleSpan = previewSpan ?: selectedItem?.let { GridFieldSpan(it.rowSpan, it.columnSpan) }
        val placeables = measurables.mapIndexed { index, measurable ->
            if (showHandle && index == handleIndex) {
                measurable.measure(Constraints.fixed(handlePx.roundToInt(), handlePx.roundToInt()))
            } else {
                val span = if (showPreview && index == previewIndex) {
                    previewSpan ?: GridFieldSpan(1, 1)
                } else {
                    val item = items[index]
                    GridFieldSpan(item.rowSpan, item.columnSpan)
                }
                val width = cellWidth * span.columns + horizontalGapPx * (span.columns - 1)
                val height = cellHeight * span.rows + verticalGapPx * (span.rows - 1)
                measurable.measure(Constraints.fixed(width.coerceAtLeast(0), height.coerceAtLeast(0)))
            }
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { index, placeable ->
                if (showHandle && index == handleIndex) {
                    val item = selectedItem
                    val span = handleSpan ?: return@forEachIndexed
                    val x = (
                        (item.column + span.columns) * (cellWidth + horizontalGapPx) -
                            horizontalGapPx - handlePx / 2f
                        )
                        .coerceIn(0f, (constraints.maxWidth - handlePx).coerceAtLeast(0f))
                    val y = (
                        (item.row + span.rows) * (cellHeight + verticalGapPx) -
                            verticalGapPx - handlePx / 2f
                        )
                        .coerceIn(0f, (constraints.maxHeight - handlePx).coerceAtLeast(0f))
                    placeable.placeRelative(x.roundToInt(), y.roundToInt())
                } else {
                    val item = if (showPreview && index == previewIndex) {
                        selectedItem.copy(
                            rowSpan = previewSpan?.rows ?: 1,
                            columnSpan = previewSpan?.columns ?: 1
                        )
                    } else {
                        items[index]
                    }
                    placeable.placeRelative(
                        x = item.column * (cellWidth + horizontalGapPx),
                        y = item.row * (cellHeight + verticalGapPx)
                    )
                }
            }
        }
    }
        resizeStatus?.let { status ->
            Box(
                modifier = Modifier
                    .size(1.dp)
                    .alpha(0f)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = status
                    }
            )
        }
    }
}

@Composable
fun SymbolBar(
    selectedButtons: List<Pair<ObfButton, ImageBitmap?>>,
    sentenceText: String,
    imagesById: Map<String, io.github.jdreioe.wingmate.domain.obf.ObfImage>,
    extractedImages: Map<String, ByteArray>,
    onSpeak: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    showSpeak: Boolean = true,
    showDelete: Boolean = true,
    showClear: Boolean = true,
    presentation: SymbolBarPresentation = SymbolBarPresentation.Normal,
    modifier: Modifier = Modifier
) {
    val textScrollState = rememberScrollState()
    val textStyle = when (presentation) {
        SymbolBarPresentation.Normal -> MaterialTheme.typography.titleMedium
        SymbolBarPresentation.Fullscreen -> MaterialTheme.typography.headlineSmall
    }
    val maximumTextHeight = with(LocalDensity.current) {
        textStyle.lineHeight.toDp() * presentation.maxTextLines + 8.dp
    }

    LaunchedEffect(selectedButtons.size) {
        if (selectedButtons.isNotEmpty()) {
            withFrameNanos { }
            textScrollState.animateScrollTo(textScrollState.maxValue)
        }
    }

    Surface(
        modifier = modifier.shadow(2.dp, RoundedCornerShape(16.dp)),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = maximumTextHeight)
                    .verticalScroll(textScrollState)
                    .clearAndSetSemantics {
                        contentDescription = sentenceText
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = sentenceText,
                    style = textStyle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            if (showSpeak || showDelete || showClear) {
                VerticalDivider(modifier = Modifier.padding(horizontal = 8.dp).height(40.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (showSpeak) {
                    FilledIconButton(
                        onClick = onSpeak,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.board_workspace_speak_sentence))
                    }
                }
                if (showDelete) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.board_workspace_delete_last))
                    }
                }
                if (showClear) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.board_workspace_clear_sentence))
                    }
                }
            }
        }
    }
}

@Composable
private fun GridHeightResizeHandle(
    currentFraction: Float,
    availableHeight: androidx.compose.ui.unit.Dp,
    onFractionPreview: (Float) -> Unit,
    onFractionCommit: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val availableHeightPx = with(LocalDensity.current) { availableHeight.toPx().coerceAtLeast(1f) }
    var dragFraction by remember { mutableFloatStateOf(currentFraction) }
    var dragStartFraction by remember { mutableFloatStateOf(currentFraction) }
    val latestCurrentFraction by rememberUpdatedState(currentFraction)
    val latestOnFractionPreview by rememberUpdatedState(onFractionPreview)
    val latestOnFractionCommit by rememberUpdatedState(onFractionCommit)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(availableHeightPx) {
                detectVerticalDragGestures(
                    onDragStart = {
                        dragStartFraction = latestCurrentFraction
                        dragFraction = latestCurrentFraction
                    },
                    onDragEnd = { latestOnFractionCommit(dragFraction) },
                    onDragCancel = { latestOnFractionPreview(dragStartFraction) },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        dragFraction = (dragFraction - dragAmount / availableHeightPx).coerceIn(0.15f, 1f)
                        latestOnFractionPreview(dragFraction)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(72.dp)
                .height(5.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
        )
    }
}

@Composable
fun ObfButtonItem(
    button: ObfButton,
    image: ObfImage? = null,
    extractedImageBytes: ByteArray? = null,
    onClick: () -> Unit,
    isEditMode: Boolean = false,
    isTemporarilyRevealed: Boolean = false,
    isHomeLink: Boolean = false,
    boardStrings: Map<String, Map<String, String>> = emptyMap(),
    locale: String? = null,
    boardSettings: ResolvedBoardSettings? = null,
    labelOverride: String? = null,
    isSelectionHighlighted: Boolean = false,
    fieldFontScale: Float = 1f
) {
    val speechService: SpeechService = koinInject()
    val voiceUseCase: VoiceUseCase = koinInject()
    val aacLogger: AacLogger = koinInject()
    val settings by rememberReactiveSettings()
    // #118: per-target activation debounce. Each button instance maps to a single target,
    // so a per-item guard enforces the per-target rules without shared state.
    val selectionDebouncer = remember(button.id) { SelectionDebouncer() }
    // #118: Edit-mode taps are deliberate field/dialog operations and must never be
    // blocked by the run-mode selection debounce.
    fun tryActivate(): Boolean =
        isEditMode || selectionDebouncer.tryActivate(
            button.id,
            Clock.System.now().toEpochMilliseconds(),
            settings.selectionDebounceMillis
        )
    val effectiveBoardSettings = boardSettings ?: resolveBoardSettings(
        appShowLabels = settings.showLabels,
        appShowSymbols = settings.showSymbols,
        appLabelAtTop = settings.labelAtTop,
        appShowMessageBar = settings.boardShowMessageBar,
        appActivationBehavior = settings.boardActivationBehavior,
        appReturnBehavior = settings.boardReturnBehavior
    )
    val displayLabel = labelOverride
        ?: if (button.type == ObfButtonType.NGramPrediction ||
            parseObfButtonActions(button).any { it === ObfButtonActionEffect.Predictions }
        ) "" else resolveObfLocalizedString(boardStrings, locale, button.label)
    val displayVocalization = resolveObfLocalizedString(boardStrings, locale, button.vocalization)
    val temporarilyRevealedDescription = stringResource(R.string.board_workspace_temporarily_revealed)
    val boundedFontScale = fieldFontScale.coerceIn(1f, 2f)
    val scaledLabelMedium = MaterialTheme.typography.labelMedium.copy(
        fontSize = MaterialTheme.typography.labelMedium.fontSize * boundedFontScale,
        lineHeight = MaterialTheme.typography.labelMedium.lineHeight * boundedFontScale,
        fontWeight = FontWeight.Bold
    )
    val scaledLabelSmall = MaterialTheme.typography.labelSmall.copy(
        fontSize = MaterialTheme.typography.labelSmall.fontSize * boundedFontScale,
        lineHeight = MaterialTheme.typography.labelSmall.lineHeight * boundedFontScale,
        fontWeight = FontWeight.Bold
    )
    
    // Page links navigate immediately; pulsing the outgoing button makes the
    // destination page appear to animate as the grid composition is reused.
    val animateSelection = button.loadBoard == null
    var isSelected by remember(button.id) { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.1f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        finishedListener = { isSelected = false }
    )

    val accessHost = LocalAccessInputHost.current
    val accessTargetId = "board:${button.id}"
    var isHovered by remember { mutableStateOf(false) }
    var isPointerDown by remember { mutableStateOf(false) }
    
    // Stable scope for fire-and-forget speech (survives hover changes)
    val fishingScope = rememberCoroutineScope()

    val primaryAction = {
        if (tryActivate()) {
            if (animateSelection) isSelected = true
            aacLogger.logButtonClick(displayLabel ?: "", phraseId = button.id)
            onClick()
        }
    }
    if (!isEditMode) RegisterAccessTarget(accessTargetId, primaryAction)
    val dwellProgress = if (accessHost?.state?.currentTargetId == accessTargetId) accessHost.state.dwellProgress else 0f

    LaunchedEffect(isHovered, settings.auditoryFishingEnabled) {
        val label = displayLabel ?: displayVocalization ?: ""
        if (isHovered && accessHost?.state?.isPaused != true && settings.auditoryFishingEnabled && label.isNotBlank()) {
            fishingScope.launch {
                runCatching {
                    val voice = voiceUseCase.selected().withLanguageOverride(button.locale)
                    speechService.speak(label, voice, voice?.pitch, rate = 0.8)
                }
            }
        }
    }

    // High Contrast Overrides
    val highContrastContainer = if (MaterialTheme.colorScheme.surface == Color.Black || settings.forceDarkTheme == true) Color.Black else Color.White
    val highContrastContent = if (highContrastContainer == Color.Black) Color.White else Color.Black
    
    val resolvedBackgroundColor = button.resolvedBackgroundColor(
        settings.wordTypeColorScheme,
        locale,
        displayLabel
    )
    val bgColor = if (settings.highContrastMode) {
        highContrastContainer
    } else {
        resolvedBackgroundColor?.let { runCatching { parseHexToColor(it) }.getOrNull() }
            ?: MaterialTheme.colorScheme.surfaceVariant
    }
    
    val borderColor = if (settings.highContrastMode) {
        highContrastContent
    } else {
        button.borderColor?.let { runCatching { parseHexToColor(it) }.getOrNull() }
    }
    
    val contentColor = when {
        settings.highContrastMode -> highContrastContent
        resolvedBackgroundColor != null -> contrastingContentColor(bgColor)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val buttonShape = button.shape.toShape()

    // #120: high-contrast outline for the time-bounded selection highlight. Uses a distinct
    // hue so it stays distinguishable from focus (primary) and the press pulse.
    val selectionHighlightColor = if (settings.highContrastMode) {
        highContrastContent
    } else {
        MaterialTheme.colorScheme.tertiary
    }
    
    // Spec priority: data → path → url → symbol (extracted zip bytes count as path).
    val imageSources = remember(image) { obfImageSources(image) }
    val imageBitmap = remember(imageSources, extractedImageBytes) {
        imageSources.firstNotNullOfOrNull { source ->
            when (source) {
                is ObfMediaSource.Data -> runCatching {
                    val base64 = source.value.substringAfter("base64,", source.value)
                    Base64Decoder.decode(base64).toComposeImageBitmap()
                }.getOrNull()
                is ObfMediaSource.Path -> extractedImageBytes?.let { bytes ->
                    runCatching { bytes.toComposeImageBitmap() }.getOrNull()
                }
                is ObfMediaSource.Url, is ObfMediaSource.Symbol -> null
            }
        }
    }
    val imageModel = if (imageBitmap == null) {
        imageSources.firstNotNullOfOrNull { source ->
            when (source) {
                is ObfMediaSource.Url -> source.value
                // Local paths are decoded above; if decoding failed, continue to the URL fallback.
                is ObfMediaSource.Path -> null
                else -> null
            }
        }
    } else null
    val symbolUnavailable = imageSources.any { it is ObfMediaSource.Symbol } && imageBitmap == null && imageModel == null
    val symbolSet = (imageSources.firstOrNull { it is ObfMediaSource.Symbol } as? ObfMediaSource.Symbol)?.value?.set
    
    Card(
        modifier = Modifier
            .fillMaxSize()
            .padding(if (settings.highContrastMode) 2.dp else 0.dp)
            .scale(scale)
            .alpha(if (button.hidden && isEditMode) 0.5f else 1f)
            .semantics {
                if (isTemporarilyRevealed) {
                    contentDescription = listOfNotNull(
                        displayLabel,
                        temporarilyRevealedDescription
                    ).joinToString(", ")
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Enter -> { isHovered = true; if (!isEditMode) accessHost?.enter(accessTargetId) }
                            PointerEventType.Exit -> { isHovered = false; accessHost?.exit(accessTargetId) }
                            PointerEventType.Press -> { isPointerDown = true; accessHost?.exit(accessTargetId) }
                            PointerEventType.Release -> { isPointerDown = false; if (isHovered && !isEditMode) accessHost?.enter(accessTargetId) }
                        }
                    }
                }
            }
            .let { baseModifier ->
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
            }
            .accessTargetFocus(accessTargetId, if (isEditMode) null else accessHost),
        shape = buttonShape,
        colors = CardDefaults.elevatedCardColors(containerColor = bgColor),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        border = if (borderColor != null || settings.highContrastMode) {
             androidx.compose.foundation.BorderStroke(if (settings.highContrastMode) 3.dp else 2.dp, borderColor ?: highContrastContent)
        } else null,
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.Center) {
            if (isTemporarilyRevealed) {
                Icon(
                    Icons.Default.Visibility,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).size(18.dp)
                )
            }
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
                val showImg = effectiveBoardSettings.showSymbols &&
                    (imageBitmap != null || !imageModel.isNullOrBlank() || symbolUnavailable)
                val showLbl = effectiveBoardSettings.showLabels &&
                    !(displayLabel.isNullOrBlank() && displayVocalization.isNullOrBlank())

                if (effectiveBoardSettings.labelAtTop && showImg && showLbl) {
                    val labelText = displayLabel ?: displayVocalization ?: ""
                    Text(
                        text = labelText,
                        style = scaledLabelMedium,
                        textAlign = TextAlign.Center,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    if (symbolUnavailable) {
                        SymbolUnavailablePlaceholder(
                            symbolSet = symbolSet,
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(2.dp),
                            contentColor = contentColor
                        )
                    } else {
                        BoardSymbolImage(
                            bitmap = imageBitmap,
                            model = imageModel,
                            contentDescription = displayLabel,
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(2.dp).clip(buttonShape)
                        )
                    }
                } else {
                    // Normal order (Image at Top)
                    if (showImg) {
                        if (symbolUnavailable) {
                            SymbolUnavailablePlaceholder(
                                symbolSet = symbolSet,
                                modifier = Modifier.weight(1f).fillMaxWidth().padding(2.dp),
                                contentColor = contentColor
                            )
                        } else {
                            BoardSymbolImage(
                                bitmap = imageBitmap,
                                model = imageModel,
                                contentDescription = button.label,
                                modifier = Modifier.weight(1f).fillMaxWidth().padding(2.dp).clip(buttonShape)
                            )
                        }
                    }
                    if (showLbl) {
                        val labelText = displayLabel ?: displayVocalization ?: ""
                        Text(
                            text = labelText,
                            style = scaledLabelSmall,
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
                    stringResource(R.string.board_workspace_home)
                } else {
                    stringResource(
                        R.string.board_cell_opens_board,
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
            if (isSelectionHighlighted) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .border(4.dp, selectionHighlightColor, RoundedCornerShape(12.dp))
                )
            }
            if (accessHost?.state?.currentTargetId == accessTargetId &&
                settings.pointerEmphasisStyle != io.github.jdreioe.wingmate.domain.PointerEmphasisStyle.System
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .padding((6.dp / settings.pointerEmphasisScale.coerceIn(1f, 3f)))
                        .border(
                            (3.dp * settings.pointerEmphasisScale.coerceIn(1f, 3f)),
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(if (settings.pointerEmphasisStyle == io.github.jdreioe.wingmate.domain.PointerEmphasisStyle.Ring) 24.dp else 2.dp)
                        )
                )
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
    homeBoardId: String?,
    boardSettings: ResolvedBoardSettings,
    showHiddenButtons: Boolean,
    predictionLabels: Map<String, String>,
    highlightedButtonId: String? = null
) {
    val containerWidth = maxWidth
    val containerHeight = maxHeight
    board.buttons.forEach { button ->
        val left = (button.left ?: 0.0) * containerWidth.value
        val top = (button.top ?: 0.0) * containerHeight.value
        val w = (button.width ?: 0.1) * containerWidth.value
        val h = (button.height ?: 0.1) * containerHeight.value
        if (isBoardButtonVisible(button, isEditMode, showHiddenButtons)) {
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
                    isTemporarilyRevealed = button.hidden && !isEditMode && showHiddenButtons,
                    isHomeLink = button.isHomeNavigation(homeBoardId),
                    boardSettings = boardSettings,
                    labelOverride = predictionLabels[button.id],
                    isSelectionHighlighted = button.id == highlightedButtonId
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
        stringResource(R.string.board_symbol_unavailable)
    } else {
        stringResource(R.string.board_symbol_unavailable_set, symbolSet)
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
