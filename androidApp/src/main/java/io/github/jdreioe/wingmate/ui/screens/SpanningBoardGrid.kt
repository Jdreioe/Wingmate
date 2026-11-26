package io.github.jdreioe.wingmate.ui

import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.FilterQuality
import io.github.jdreioe.wingmate.domain.obf.PageBackgroundFit
import io.github.jdreioe.wingmate.domain.obf.backgroundImage
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import io.github.jdreioe.wingmate.domain.obf.ObfLoadBoard
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
import io.github.jdreioe.wingmate.application.TouchExitController
import io.github.jdreioe.wingmate.application.TouchExitEffect
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import kotlin.time.Clock
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
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
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import io.github.jdreioe.wingmate.domain.AacLogger
import io.github.jdreioe.wingmate.domain.Base64Decoder
import io.github.jdreioe.wingmate.domain.CommunicationAction
import io.github.jdreioe.wingmate.domain.CommunicationSession
import io.github.jdreioe.wingmate.domain.MessagePart
import io.github.jdreioe.wingmate.domain.obf.resolvedBackgroundColor
import io.github.jdreioe.wingmate.application.VoiceUseCase
import org.koin.compose.koinInject
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.tooling.preview.Preview
import io.github.jdreioe.wingmate.domain.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource

import com.hojmoseit.wingmate.R
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

/**
 * Screen-reader actions (move, options) for the grid cell being composed. The grid owns
 * them, but they must sit on the Button's own focusable node for TalkBack to offer them.
 */
internal val LocalBoardCellActions = compositionLocalOf { emptyList<CustomAccessibilityAction>() }

internal class BoardTouchExit(
    val isButtonSelectable: (ObfButton) -> Boolean,
    val onPreview: (ObfButton) -> Unit,
    val onActivate: (BoardGridItem, ObfButton) -> Unit,
)

@Composable
internal fun SpanningBoardGrid(
    rows: Int,
    columns: Int,
    items: List<BoardGridItem>,
    modifier: Modifier = Modifier,
    horizontalGapDp: Dp = 4.dp,
    verticalGapDp: Dp = 8.dp,
    onMove: ((fromRow: Int, fromColumn: Int, toRow: Int, toColumn: Int) -> Unit)? = null,
    selectedField: Pair<Int, Int>? = null,
    selectedFieldSpans: List<GridFieldSpan> = emptyList(),
    onResizeField: ((anchorRow: Int, anchorColumn: Int, rowSpan: Int, columnSpan: Int) -> Unit)? = null,
    onRemoveField: ((row: Int, column: Int) -> Unit)? = null,
    focusedCell: Pair<Int, Int>? = null,
    reorder: BoardReorder? = null,
    cellMenu: BoardCellMenu? = null,
    touchExit: BoardTouchExit? = null,
    content: @Composable (BoardGridItem) -> Unit
) {
    // The Button whose launcher-style menu (Edit, Resize, Remove) is open, by its anchor cell.
    var cellMenuAnchor by remember(items) { mutableStateOf<Pair<Int, Int>?>(null) }
    var dragSource by remember(items) { mutableStateOf<Pair<Int, Int>?>(null) }
    var dragTarget by remember(items) { mutableStateOf<Pair<Int, Int>?>(null) }
    var resizeCell by remember(items) { mutableStateOf<Pair<Int, Int>?>(null) }
    var gridSizePx by remember { mutableStateOf(IntSize.Zero) }
    // Where each resize handle sits in the grid, and which one is being dragged.
    val handleOffsets = remember { mutableStateMapOf<ResizeEdge, Offset>() }
    var resizeEdge by remember { mutableStateOf(ResizeEdge.Corner) }
    var resizeStatus by remember { mutableStateOf<String?>(null) }
    val density = LocalDensity.current
    val horizontalGap = with(density) { horizontalGapDp.toPx() }
    val verticalGap = with(density) { verticalGapDp.toPx() }
    val touchExitController = remember { TouchExitController() }
    var touchExitActiveButtonId by remember(items, touchExit) { mutableStateOf<String?>(null) }
    DisposableEffect(items, touchExit) {
        onDispose {
            touchExitController.cancel()
            touchExitActiveButtonId = null
        }
    }

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
    val previewSpan = remember(resizeCell, selectedItem, resizeEdge) {
        val item = selectedItem ?: return@remember null
        val cell = resizeCell ?: return@remember null
        resizeEdge.spanTo(item, cell)
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
    val currentCellAt: (ResizeEdge, Offset) -> Pair<Int, Int>? by rememberUpdatedState { edge, position ->
        cellAt(position + (handleOffsets[edge] ?: Offset.Zero))
    }

    fun Modifier.resizeHandleAccess(): Modifier = this
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

    // Widget-style resizing: drag the corner, or the end or bottom edge for one direction.
    // The corner also takes keys and screen-reader actions; the edges are extra pointer targets.
    fun resizeHandleModifier(edge: ResizeEdge): Modifier = Modifier
        .size(48.dp)
        .testTag(if (edge == ResizeEdge.Corner) "resize-handle" else "resize-handle-${edge.name.lowercase()}")
        .onGloballyPositioned { handleOffsets[edge] = it.positionInParent() }
        .then(if (edge == ResizeEdge.Corner) Modifier.resizeHandleAccess() else Modifier.clearAndSetSemantics { })
        .pointerInput(items, allowedSpans, edge) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val touchAnchorAdjustment = Offset(
                    x = size.width / 2f - down.position.x,
                    y = size.height / 2f - down.position.y
                )
                val start = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    ?: return@awaitEachGesture
                resizeEdge = edge
                val startCell = currentCellAt(edge, start.position + touchAnchorAdjustment)
                resizeCell = startCell
                var lastCell = startCell
                var endedOutOfBounds = startCell == null
                drag(down.id) { change ->
                    change.consume()
                    val cell = currentCellAt(edge, change.position + touchAnchorAdjustment)
                    endedOutOfBounds = cell == null
                    cell?.let { lastCell = it }
                    resizeCell = lastCell
                }
                val item = currentSelectedItem
                val finalCell = lastCell
                val span = if (item != null && finalCell != null) edge.spanTo(item, finalCell) else null
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

    // Launcher-style reordering (see BoardReorder). Movable Buttons in reading
    // order; while dragging, the others shift one slot toward the gap.
    val movable = remember(items, reorder) {
        if (reorder == null) emptyList()
        else items
            .filter { it.rowSpan == 1 && it.columnSpan == 1 && it.button?.let(reorder.canMove) == true }
            .sortedWith(compareBy({ it.row }, { it.column }))
    }
    var menuButtonId by remember(items) { mutableStateOf<String?>(null) }
    var liftedButtonId by remember(items) { mutableStateOf<String?>(null) }
    var reorderFrom by remember(items) { mutableStateOf<Int?>(null) }
    var reorderTo by remember(items) { mutableStateOf<Int?>(null) }
    var reorderOffset by remember(items) { mutableStateOf(Offset.Zero) }
    var reorderDropped by remember(items) { mutableStateOf(false) }
    // The new order arrives as new items; if it doesn't (the move was refused or
    // editing needs unlocking first), settle back after a moment.
    LaunchedEffect(reorderDropped) {
        if (reorderDropped) {
            delay(1500)
            reorderFrom = null
            reorderTo = null
            liftedButtonId = null
            reorderDropped = false
        }
    }
    val haptics = LocalHapticFeedback.current
    fun movableIndexAt(position: Offset): Int? {
        val cell = cellAt(position) ?: return null
        return movable.indexOfFirst { it.row == cell.first && it.column == cell.second }.takeIf { it >= 0 }
    }
    fun slotOffset(fromIndex: Int, toIndex: Int): IntOffset {
        val cellWidth = (gridSizePx.width - horizontalGap * (columns - 1)).coerceAtLeast(0f) / columns
        val cellHeight = (gridSizePx.height - verticalGap * (rows - 1)).coerceAtLeast(0f) / rows
        val from = movable[fromIndex]
        val to = movable[toIndex]
        return IntOffset(
            x = ((to.column - from.column) * (cellWidth + horizontalGap)).roundToInt(),
            y = ((to.row - from.row) * (cellHeight + verticalGap)).roundToInt(),
        )
    }
    val reorderModifier = if (reorder != null && movable.isNotEmpty()) {
        Modifier.pointerInput(movable, reorder) {
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                // Watch in the Initial pass so taps and scrolls still reach the
                // Button and the scroller until the press becomes a long press.
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val from = movableIndexAt(down.position) ?: return@awaitEachGesture
                // A right click opens the menu at once, like a long press; it never reorders.
                // It also works when long presses are kept for hold-to-select.
                if (isSecondaryPress()) {
                    val pressed = movable[from].button ?: return@awaitEachGesture
                    down.consume()
                    menuButtonId = pressed.id
                    liftedButtonId = pressed.id
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    return@awaitEachGesture
                }
                if (!reorder.gesturesEnabled) return@awaitEachGesture
                val interrupted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes
                            .firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed ||
                            (change.position - down.position).getDistance() > slop
                        ) {
                            return@withTimeoutOrNull true
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    true
                }
                if (interrupted == true) return@awaitEachGesture
                val button = movable[from].button ?: return@awaitEachGesture
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                menuButtonId = button.id
                liftedButtonId = button.id
                var dragging = false
                while (true) {
                    // From here the gesture is ours: consuming it cancels the
                    // Button's tap and the scroller.
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes
                        .firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) break
                    val offset = change.position - down.position
                    if (!dragging && offset.getDistance() > slop) {
                        dragging = true
                        menuButtonId = null
                        reorderFrom = from
                        reorderTo = from
                    }
                    if (dragging) {
                        reorderOffset = offset
                        movableIndexAt(change.position)?.let { reorderTo = it }
                    }
                }
                val to = reorderTo
                if (dragging && to != null && to != from) {
                    reorderDropped = true
                    movable[to].button?.let { target -> reorder.onMove(button, target) }
                } else {
                    reorderFrom = null
                    reorderTo = null
                    // Stay lifted while the menu is open; dismissing it lowers the Button.
                    if (dragging) liftedButtonId = null
                }
            }
        }
    } else {
        Modifier
    }
    val moveEarlierLabel = stringResource(R.string.phrase_move_earlier)
    val moveLaterLabel = stringResource(R.string.phrase_move_later)
    val optionsLabel = stringResource(R.string.phrase_item_edit)

    // Editing, like app icons on a launcher: hold a Button for its menu (Edit, Resize, Remove),
    // keep holding and drag to move it. A right click opens the menu at once. Watched in the
    // Initial pass so a tap still reaches the Button (it opens the editor) until the press
    // becomes a long press; from then on the gesture is ours, so letting go opens nothing else.
    val dragModifier = if (onMove != null) {
        Modifier.pointerInput(rows, columns, items, onMove, cellMenu) {
            val slop = viewConfiguration.touchSlop
            fun fieldAt(position: Offset) = cellAt(position)?.let { (row, column) ->
                items.firstOrNull { item ->
                    row in item.row until item.row + item.rowSpan &&
                        column in item.column until item.column + item.columnSpan
                }
            }?.takeIf { it.button != null }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val field = fieldAt(down.position) ?: return@awaitEachGesture
                val anchor = field.row to field.column
                if (isSecondaryPress()) {
                    if (cellMenu == null) return@awaitEachGesture
                    down.consume()
                    cellMenuAnchor = anchor
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    return@awaitEachGesture
                }
                val interrupted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed || (change.position - down.position).getDistance() > slop) {
                            return@withTimeoutOrNull true
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    true
                }
                if (interrupted == true) return@awaitEachGesture
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                cellMenuAnchor = if (cellMenu != null) anchor else null
                var moving = false
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) break
                    if (!moving && (change.position - down.position).getDistance() > slop) {
                        // Dragging turns the hold into a move, as on a launcher.
                        moving = true
                        cellMenuAnchor = null
                        dragSource = anchor
                        dragTarget = anchor
                    }
                    if (moving) cellAt(change.position)?.let { dragTarget = it }
                }
                val source = dragSource
                val target = dragTarget
                dragSource = null
                dragTarget = null
                if (moving && source != null && target != null && source != target) {
                    onMove(source.first, source.second, target.first, target.second)
                }
            }
        }
    } else {
        Modifier
    }
    val currentTouchExit by rememberUpdatedState(touchExit)
    val currentItems by rememberUpdatedState(items)
    val currentHorizontalGap by rememberUpdatedState(horizontalGap)
    val currentVerticalGap by rememberUpdatedState(verticalGap)
    val touchExitModifier = if (touchExit != null && onMove == null) {
        Modifier.pointerInput(rows, columns) {
            fun selectableItemAt(position: Offset): BoardGridItem? {
                val activeTouchExit = currentTouchExit ?: return null
                if (gridSizePx.width <= 0 || gridSizePx.height <= 0) return null
                if (position.x < 0f || position.y < 0f || position.x >= gridSizePx.width || position.y >= gridSizePx.height) {
                    return null
                }
                val hGap = currentHorizontalGap
                val vGap = currentVerticalGap
                val cellWidth = (gridSizePx.width - hGap * (columns - 1)).coerceAtLeast(0f) / columns
                val cellHeight = (gridSizePx.height - vGap * (rows - 1)).coerceAtLeast(0f) / rows
                return currentItems.firstOrNull { item ->
                    val button = item.button ?: return@firstOrNull false
                    if (!activeTouchExit.isButtonSelectable(button)) return@firstOrNull false
                    val left = item.column * (cellWidth + hGap)
                    val top = item.row * (cellHeight + vGap)
                    val right = left + cellWidth * item.columnSpan + hGap * (item.columnSpan - 1)
                    val bottom = top + cellHeight * item.rowSpan + vGap * (item.rowSpan - 1)
                    position.x in left..right && position.y in top..bottom
                }
            }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (isSecondaryPress()) return@awaitEachGesture
                down.consume()
                try {
                    val startItem = selectableItemAt(down.position)
                    val beginEffect = touchExitController.begin(startItem?.button?.id)
                    touchExitActiveButtonId = touchExitController.state.activeTargetId
                    if (beginEffect is TouchExitEffect.Preview && startItem?.button != null) {
                        currentTouchExit?.onPreview(startItem.button)
                    }
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        event.changes.forEach { it.consume() }
                        if (change == null) {
                            touchExitController.cancel()
                            touchExitActiveButtonId = null
                            break
                        }
                        if (!change.pressed) {
                            if (change.changedToUpIgnoreConsumed()) {
                                val endItem = selectableItemAt(change.position)
                                val effect = touchExitController.release(endItem?.button?.id)
                                touchExitActiveButtonId = touchExitController.state.activeTargetId
                                if (effect is TouchExitEffect.Activate && endItem?.button != null) {
                                    currentTouchExit?.onActivate(endItem, endItem.button)
                                }
                            } else {
                                touchExitController.cancel()
                                touchExitActiveButtonId = null
                            }
                            break
                        }
                        val hovered = selectableItemAt(change.position)
                        val effect = touchExitController.move(hovered?.button?.id)
                        touchExitActiveButtonId = touchExitController.state.activeTargetId
                        if (effect is TouchExitEffect.Preview && hovered?.button != null) {
                            currentTouchExit?.onPreview(hovered.button)
                        }
                    }
                } finally {
                    if (touchExitController.state.isTracking) {
                        touchExitController.cancel()
                        touchExitActiveButtonId = null
                    }
                }
            }
        }
    } else {
        Modifier
    }
    Box(modifier = modifier) {
        Layout(
            modifier = Modifier
                .fillMaxSize()
                .then(dragModifier)
                .then(reorderModifier)
                .then(touchExitModifier)
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
                        val movableIndex = movable.indexOf(item)
                        val from = reorderFrom
                        val to = reorderTo
                        val isDragged = movableIndex >= 0 && movableIndex == from
                        // Slot this Button shows in while another one is dragged past it.
                        val displayIndex = when {
                            movableIndex < 0 || from == null || to == null -> movableIndex
                            isDragged -> to
                            from < to && movableIndex in (from + 1)..to -> movableIndex - 1
                            from > to && movableIndex in to until from -> movableIndex + 1
                            else -> movableIndex
                        }
                        val slotTarget = if (movableIndex >= 0 && displayIndex != movableIndex) {
                            slotOffset(movableIndex, displayIndex)
                        } else {
                            IntOffset.Zero
                        }
                        val animatedSlot by animateIntOffsetAsState(slotTarget, label = "reorder-slot")
                        val isLifted = item.button?.id != null && item.button.id == liftedButtonId
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(if (isLifted) 1f else 0f)
                                .offset {
                                    if (isDragged && !reorderDropped) {
                                        IntOffset(reorderOffset.x.roundToInt(), reorderOffset.y.roundToInt())
                                    } else {
                                        animatedSlot
                                    }
                                }
                                .then(
                                    if (isLifted) {
                                        Modifier.scale(1.06f).shadow(8.dp, ringShape, clip = false)
                                    } else {
                                        Modifier
                                    }
                                )
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
                            val cellActions = if (movableIndex >= 0 && reorder != null) {
                                val button = item.button!!
                                listOfNotNull(
                                    movable.getOrNull(movableIndex - 1)?.button?.let { previous ->
                                        CustomAccessibilityAction(moveEarlierLabel) {
                                            reorder.onMove(button, previous)
                                            true
                                        }
                                    },
                                    movable.getOrNull(movableIndex + 1)?.button?.let { next ->
                                        CustomAccessibilityAction(moveLaterLabel) {
                                            reorder.onMove(button, next)
                                            true
                                        }
                                    },
                                    CustomAccessibilityAction(optionsLabel) {
                                        menuButtonId = button.id
                                        true
                                    },
                                )
                            } else {
                                emptyList()
                            }
                            CompositionLocalProvider(LocalBoardCellActions provides cellActions) {
                                content(item)
                            }
                            if (item.button != null && item.button.id == touchExitActiveButtonId) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .testTag("touch-exit-highlight")
                                        .clip(ringShape)
                                        .background(selectionColor.copy(alpha = 0.16f))
                                        .border(4.dp, selectionColor, ringShape)
                                )
                            }
                            if (isSelected && onRemoveField != null && item.button != null) {
                                val removeLabel = stringResource(R.string.board_cell_menu_remove)
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(44.dp)
                                        .testTag("selected-field-remove")
                                        .semantics {
                                            role = Role.Button
                                            contentDescription = removeLabel
                                        }
                                        .combinedClickable(
                                            onClick = { onRemoveField(item.row, item.column) },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError,
                                        shadowElevation = 3.dp,
                                        modifier = Modifier.size(30.dp),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                            }
                            val menuButtonForCell = item.button
                            if (cellMenu != null && menuButtonForCell != null && cellMenuAnchor == (item.row to item.column)) {
                                BoardCellMenuPopup(
                                    menu = cellMenu,
                                    item = item,
                                    button = menuButtonForCell,
                                    onDismiss = { cellMenuAnchor = null },
                                )
                            }
                            val menuButton = item.button
                            if (reorder != null && menuButton != null && menuButton.id == menuButtonId) {
                                reorder.menu(menuButton) {
                                    menuButtonId = null
                                    liftedButtonId = null
                                }
                            }
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
                ResizeEdge.entries.forEach { edge ->
                    key("resize-handle", edge) {
                        Box(modifier = resizeHandleModifier(edge), contentAlignment = Alignment.Center) {
                            if (edge == ResizeEdge.Corner) {
                                // A filled grip with a resize glyph, the size of a fingertip, so the
                                // Supporter can see there is something to drag (also Size in the editor).
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(selectionColor, CircleShape)
                                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Default.OpenInFull,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.surface,
                                        modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = 90f },
                                    )
                                }
                            } else {
                                // Edge dots, as on a home-screen widget's resize frame.
                                Box(
                                    Modifier
                                        .size(20.dp)
                                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                                        .border(3.dp, selectionColor, CircleShape)
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { measurables, constraints ->
        val horizontalGapPx = horizontalGapDp.roundToPx()
        val verticalGapPx = verticalGapDp.roundToPx()
        val availableWidth = (constraints.maxWidth - horizontalGapPx * (columns - 1)).coerceAtLeast(0)
        val availableHeight = (constraints.maxHeight - verticalGapPx * (rows - 1)).coerceAtLeast(0)
        val cellWidth = availableWidth / columns
        val cellHeight = availableHeight / rows
        val handlePx = 48.dp.toPx()
        val previewIndex = items.size
        val handleIndex = items.size + if (showPreview) 1 else 0
        val handleSpan = previewSpan ?: selectedItem?.let { GridFieldSpan(it.rowSpan, it.columnSpan) }
        val isHandle = { index: Int -> showHandle && index >= handleIndex }
        val placeables = measurables.mapIndexed { index, measurable ->
            if (isHandle(index)) {
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
                if (isHandle(index)) {
                    val item = selectedItem ?: return@forEachIndexed
                    val span = handleSpan ?: return@forEachIndexed
                    val edge = ResizeEdge.entries[index - handleIndex]
                    val left = item.column * (cellWidth + horizontalGapPx).toFloat()
                    val top = item.row * (cellHeight + verticalGapPx).toFloat()
                    val right = (item.column + span.columns) * (cellWidth + horizontalGapPx) - horizontalGapPx.toFloat()
                    val bottom = (item.row + span.rows) * (cellHeight + verticalGapPx) - verticalGapPx.toFloat()
                    val centerX = if (edge == ResizeEdge.Bottom) (left + right) / 2f else right
                    val centerY = if (edge == ResizeEdge.End) (top + bottom) / 2f else bottom
                    val x = (centerX - handlePx / 2f).coerceIn(0f, (constraints.maxWidth - handlePx).coerceAtLeast(0f))
                    val y = (centerY - handlePx / 2f).coerceIn(0f, (constraints.maxHeight - handlePx).coerceAtLeast(0f))
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

/** A resize handle on the selected Button: the corner changes both directions, an edge one. */
internal enum class ResizeEdge {
    Corner, End, Bottom;

    /** The span [item] gets when this handle is dragged to [cell]. */
    fun spanTo(item: BoardGridItem, cell: Pair<Int, Int>): GridFieldSpan = GridFieldSpan(
        rows = if (this == End) item.rowSpan else (cell.first - item.row + 1).coerceAtLeast(1),
        columns = if (this == Bottom) item.columnSpan else (cell.second - item.column + 1).coerceAtLeast(1),
    )
}

/**
 * What a long press (or right click) on a Button offers while editing, like a launcher's menu
 * on an app icon. Each action is also reachable without the gesture: tap opens the editor,
 * which has Size and Clear.
 */
class BoardCellMenu(
    val onEdit: (row: Int, column: Int, button: ObfButton) -> Unit,
    val onResize: (row: Int, column: Int) -> Unit,
    val onRemove: (row: Int, column: Int) -> Unit,
)

@Composable
internal fun BoardCellMenuPopup(menu: BoardCellMenu, item: BoardGridItem, button: ObfButton, onDismiss: () -> Unit) {
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.board_cell_menu_edit)) },
            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
            onClick = { onDismiss(); menu.onEdit(item.row, item.column, button) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.board_cell_menu_resize)) },
            leadingIcon = { Icon(Icons.Default.OpenInFull, contentDescription = null) },
            onClick = { onDismiss(); menu.onResize(item.row, item.column) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.board_cell_menu_remove), color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            onClick = { onDismiss(); menu.onRemove(item.row, item.column) },
        )
    }
}
