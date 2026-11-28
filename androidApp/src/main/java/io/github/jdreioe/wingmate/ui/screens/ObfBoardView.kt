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
import io.github.jdreioe.wingmate.application.touchExitIsActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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

/**
 * Launcher-style editing for a grid of single-cell Buttons (the Typing Screen's
 * Phrase collection). Long-pressing a movable Button lifts it and opens [menu]
 * on it; dragging without lifting the finger closes the menu, moves the Button
 * and reflows the others. Screen readers and switch access get Move earlier /
 * Move later actions instead of the gesture.
 *
 * [onMove] receives the moved Button and the Button whose place it takes.
 * With [gesturesEnabled] false (for example hold-to-select), only the
 * accessibility actions remain, so a press is never taken over.
 */
class BoardReorder(
    val canMove: (ObfButton) -> Boolean,
    val onMove: (moved: ObfButton, target: ObfButton) -> Unit,
    val gesturesEnabled: Boolean,
    val menu: @Composable (button: ObfButton, dismiss: () -> Unit) -> Unit,
)

/**
 * Everything [ObfBoardContent] needs to draw a Page. Callers own these values;
 * the renderer only keeps pointer, animation, and layout mechanics locally.
 * Not annotated as stable: [extractedImages] holds mutable byte arrays, and
 * strong skipping already compares the remaining values by instance.
 */
internal data class ObfBoardUiState(
    val board: ObfBoard,
    val extractedImages: Map<String, ByteArray> = emptyMap(),
    val isEditMode: Boolean = false,
    val isMaskMode: Boolean = false,
    val selectedButtons: List<Pair<ObfButton, ImageBitmap?>> = emptyList(),
    val showSpeakControl: Boolean = true,
    val showDeleteControl: Boolean = true,
    val showClearControl: Boolean = true,
    val showMessageBar: Boolean = !isEditMode,
    val messageBarEditable: Boolean = false,
    val messageText: String = "",
    val symbolBarPresentation: SymbolBarPresentation = SymbolBarPresentation.Normal,
    val boardSettings: ResolvedBoardSettings? = null,
    val showHiddenButtons: Boolean = false,
    val predictionLabels: Map<String, String> = emptyMap(),
    val highlightedButtonId: String? = null,
    val guidedButtonId: String? = null,
    val guidedButtonDescription: String? = null,
    /** The Page's decoded background image (#103); the caller loads it from [ObfBoard.backgroundImage]. */
    val backgroundImage: ImageBitmap? = null,
    val selectedFieldAnchor: Pair<Int, Int>? = null,
    val selectedFieldSpans: List<GridFieldSpan> = emptyList(),
    val homeBoardId: String? = null,
    val scrollingMinimumCellHeight: Dp? = null,
    /**
     * With [scrollingMinimumCellHeight], caps row height and pins rows to the top, so a
     * short list in a tall viewport keeps normal-sized Buttons that do not move or grow as
     * Buttons are added or the keyboard opens. Used by the wide Typing layout.
     */
    val scrollingMaximumCellHeight: Dp? = null,
    /**
     * Whether Touch Exit (slide-to-correct) may take over touch input on this board.
     * Typing Phrase trays keep scrolling and finger-only long-press Edit/Delete menus.
     */
    val allowTouchExit: Boolean = true,
    val settings: Settings = Settings(),
    val focusedCell: Pair<Int, Int>? = null,
)

/** Platform services are resolved once at the root, never by individual Buttons. */
@Composable
internal fun rememberBoardFeedback(): BoardFeedback {
    val session: CommunicationSession = koinInject()
    val voices: VoiceUseCase = koinInject()
    val logger: AacLogger = koinInject()
    return remember(session, voices, logger) {
        BoardFeedback(
            onActivated = { button, label -> logger.logButtonClick(label, phraseId = button.id) },
            onAuditoryCue = { button, label ->
                // An unavailable voice falls back to the default instead of silencing the cue.
                val voice = try {
                    voices.selected()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                session.accept(
                    CommunicationAction.SpeakPart(
                        part = MessagePart(label, languageTag = button.locale),
                        voice = voice,
                        rateOverride = 0.8,
                        isPreview = true,
                    )
                )
            },
            onCancelAuditoryCue = {
                session.accept(CommunicationAction.CancelPreview)
            },
        )
    }
}

/**
 * Side effects a Button triggers besides its click callback: activation logging
 * and the auditory-fishing cue spoken while a pointer hovers. The default does
 * nothing, which keeps previews and Compose tests free of platform services.
 */
internal class BoardFeedback(
    val onActivated: (ObfButton, String) -> Unit = { _, _ -> },
    val onAuditoryCue: suspend (ObfButton, String) -> Unit = { _, _ -> },
    val onCancelAuditoryCue: () -> Unit = {},
)

private class PreviewJobHolder {
    var job: Job? = null
}

private fun ObfButton.isPredictionButton(): Boolean =
    type == ObfButtonType.NGramPrediction ||
        parseObfButtonActions(this).any { it === ObfButtonActionEffect.Predictions }

private fun resolveTouchExitPreviewLabel(
    button: ObfButton,
    boardStrings: Map<String, Map<String, String>>,
    locale: String,
    predictionLabels: Map<String, String>,
): String {
    if (button.isPredictionButton()) {
        return predictionLabels[button.id]?.trim().orEmpty()
    }
    return predictionLabels[button.id]
        ?: resolveObfLocalizedString(boardStrings, locale, button.vocalization)
        ?: resolveObfLocalizedString(boardStrings, locale, button.label)
        ?: ""
}

private fun resolveTouchExitActivationLabel(
    button: ObfButton,
    boardStrings: Map<String, Map<String, String>>,
    locale: String,
    predictionLabels: Map<String, String>,
): String {
    if (button.isPredictionButton()) {
        return predictionLabels[button.id]?.trim().orEmpty()
    }
    return predictionLabels[button.id]
        ?: resolveObfLocalizedString(boardStrings, locale, button.label)
        ?: ""
}

@Composable
fun ObfBoardView(
    board: ObfBoard,
    onButtonClick: (ObfButton) -> Unit,
    onButtonLongClick: ((ObfButton) -> Unit)? = null,
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
    // When true (and a Message callback is provided) the message bar accepts
    // typed text; resolved from BoardSettings.messageBarEditable by callers.
    messageBarEditable: Boolean = false,
    onSentenceChanged: ((String) -> Unit)? = null,
    messageText: String = "",
    symbolBarPresentation: SymbolBarPresentation = SymbolBarPresentation.Normal,
    boardSettings: ResolvedBoardSettings? = null,
    showHiddenButtons: Boolean = false,
    isMaskMode: Boolean = false,
    predictionLabels: Map<String, String> = emptyMap(),
    highlightedButtonId: String? = null,
    guidedButtonId: String? = null,
    guidedButtonDescription: String? = null,
    backgroundImage: ImageBitmap? = null,
    onCellClick: ((row: Int, column: Int, button: ObfButton?) -> Unit)? = null,
    onCellMove: ((fromRow: Int, fromColumn: Int, toRow: Int, toColumn: Int) -> Unit)? = null,
    /** Editing: what a long press or right click on a Button offers. */
    cellMenu: BoardCellMenu? = null,
    /** Running a Screen with several Pages: Back and Start page in the Message bar. */
    pageNavigation: PageNavigation? = null,
    selectedFieldAnchor: Pair<Int, Int>? = null,
    selectedFieldSpans: List<GridFieldSpan> = emptyList(),
    onResizeField: ((anchorRow: Int, anchorColumn: Int, rowSpan: Int, columnSpan: Int) -> Unit)? = null,
    onRemoveField: ((row: Int, column: Int) -> Unit)? = null,
    onGridHeightFractionChange: ((Float) -> Unit)? = null,
    homeBoardId: String? = null,
    // When set, fit whole rows to the viewport while keeping this minimum
    // height, and scroll any remaining rows. Used by the Phrase collection.
    scrollingMinimumCellHeight: Dp? = null,
    reorder: BoardReorder? = null,
) {
    val settings by rememberReactiveSettings()
    val feedback = rememberBoardFeedback()
    var focusRow by rememberSaveable(board.id) { mutableStateOf<Int?>(null) }
    var focusColumn by rememberSaveable(board.id) { mutableStateOf<Int?>(null) }
    ObfBoardContent(
        state = ObfBoardUiState(
            board = board,
            extractedImages = extractedImages,
            isEditMode = isEditMode,
            isMaskMode = isMaskMode,
            selectedButtons = selectedButtons,
            showSpeakControl = showSpeakControl,
            showDeleteControl = showDeleteControl,
            showClearControl = showClearControl,
            showMessageBar = showMessageBar,
            messageBarEditable = messageBarEditable,
            messageText = messageText,
            symbolBarPresentation = symbolBarPresentation,
            boardSettings = boardSettings,
            showHiddenButtons = showHiddenButtons,
            predictionLabels = predictionLabels,
            highlightedButtonId = highlightedButtonId,
            guidedButtonId = guidedButtonId,
            guidedButtonDescription = guidedButtonDescription,
            backgroundImage = backgroundImage,
            selectedFieldAnchor = selectedFieldAnchor,
            selectedFieldSpans = selectedFieldSpans,
            homeBoardId = homeBoardId,
            scrollingMinimumCellHeight = scrollingMinimumCellHeight,
            settings = settings,
            focusedCell = focusRow?.let { row -> focusColumn?.let { column -> row to column } },
        ),
        onButtonClick = onButtonClick,
        onButtonLongClick = onButtonLongClick,
        onSpeakSentence = onSpeakSentence,
        onDeleteLast = onDeleteLast,
        onClearSentence = onClearSentence,
        onSentenceChanged = onSentenceChanged,
        onCellClick = onCellClick,
        onCellMove = onCellMove,
        cellMenu = cellMenu,
        pageNavigation = pageNavigation,
        onResizeField = onResizeField,
        onRemoveField = onRemoveField,
        onGridHeightFractionChange = onGridHeightFractionChange,
        modifier = modifier,
        reorder = reorder,
        feedback = feedback,
        onFocusCell = { cell -> focusRow = cell?.first; focusColumn = cell?.second },
    )
}

/** Supplied-state renderer. Only pointer, animation, and layout mechanics are local. */
@Composable
internal fun ObfBoardContent(
    state: ObfBoardUiState,
    onButtonClick: (ObfButton) -> Unit,
    onButtonLongClick: ((ObfButton) -> Unit)? = null,
    onSpeakSentence: () -> Unit = {},
    onDeleteLast: () -> Unit = {},
    onClearSentence: () -> Unit = {},
    onSentenceChanged: ((String) -> Unit)? = null,
    onCellClick: ((row: Int, column: Int, button: ObfButton?) -> Unit)? = null,
    onCellMove: ((fromRow: Int, fromColumn: Int, toRow: Int, toColumn: Int) -> Unit)? = null,
    onResizeField: ((anchorRow: Int, anchorColumn: Int, rowSpan: Int, columnSpan: Int) -> Unit)? = null,
    onRemoveField: ((row: Int, column: Int) -> Unit)? = null,
    onGridHeightFractionChange: ((Float) -> Unit)? = null,
    modifier: Modifier = Modifier,
    reorder: BoardReorder? = null,
    cellMenu: BoardCellMenu? = null,
    pageNavigation: PageNavigation? = null,
    feedback: BoardFeedback = remember { BoardFeedback() },
    onFocusCell: (Pair<Int, Int>?) -> Unit = {},
) {
    val board = state.board
    val extractedImages = state.extractedImages
    val isEditMode = state.isEditMode
    val isMaskMode = state.isMaskMode
    val selectedButtons = state.selectedButtons
    val showSpeakControl = state.showSpeakControl
    val showDeleteControl = state.showDeleteControl
    val showClearControl = state.showClearControl
    val showMessageBar = state.showMessageBar
    val messageBarEditable = state.messageBarEditable
    val messageText = state.messageText
    val symbolBarPresentation = state.symbolBarPresentation
    val boardSettings = state.boardSettings
    val showHiddenButtons = state.showHiddenButtons
    val predictionLabels = state.predictionLabels
    val highlightedButtonId = state.highlightedButtonId
    val guidedButtonId = state.guidedButtonId
    val guidedButtonDescription = state.guidedButtonDescription
    val selectedFieldAnchor = state.selectedFieldAnchor
    val selectedFieldSpans = state.selectedFieldSpans
    val homeBoardId = state.homeBoardId
    val scrollingMinimumCellHeight = state.scrollingMinimumCellHeight
    val scrollingMaximumCellHeight = state.scrollingMaximumCellHeight
    val settings = state.settings
    val focusedCell = state.focusedCell

    val boardBackground = if (settings.highContrastMode) {
        MaterialTheme.colorScheme.background
    } else {
        parseObfColorOrNull(board.backgroundColor) ?: MaterialTheme.colorScheme.background
    }
    // High contrast keeps a plain background so Buttons stay legible.
    val pageBackgroundImage = state.backgroundImage.takeUnless { settings.highContrastMode }
    val backgroundFit = board.backgroundImage?.fit ?: PageBackgroundFit.Fill
    val effectiveBoardSettings = boardSettings ?: resolveBoardSettings(
        appShowLabels = settings.showLabels,
        appShowSymbols = settings.showSymbols,
        appLabelAtTop = settings.labelAtTop,
        appShowMessageBar = settings.boardShowMessageBar,
        appShowSpeakButton = settings.boardShowSpeakButton,
        appMessageBarEditable = settings.boardMessageBarEditable,
        appActivationBehavior = settings.boardActivationBehavior,
        appReturnBehavior = settings.boardReturnBehavior
    )
    val imagesById = remember(board) { board.images.associateBy { it.id } }
    // Absolute positioning: if every button has top/left/width/height, render fractionally
    val isAbsoluteLayout = remember(board) { board.isAbsoluteLayout }
    // If grid is defined, use it. Otherwise, just listing buttons (fallback)
    val grid = board.grid
    val buttonsById = remember(board) { board.buttons.associateBy { it.id } }
    // Edit mode always offers empty cells to fill, so only run mode can be empty.
    val isEmptyPage = remember(board, isEditMode, showHiddenButtons) {
        !isEditMode && !board.hasVisibleButton(showHiddenButtons)
    }
    // The Message bar is the same in every layout; only its placement differs.
    val messageBar: @Composable (Modifier) -> Unit = { barModifier ->
        SymbolBar(
            selectedButtons = selectedButtons,
            messageText = messageText,
            imagesById = imagesById,
            extractedImages = extractedImages,
            onSpeak = onSpeakSentence,
            onDelete = onDeleteLast,
            onClear = onClearSentence,
            showSpeak = showSpeakControl,
            showDelete = showDeleteControl,
            showClear = showClearControl,
            presentation = symbolBarPresentation,
            editable = messageBarEditable && onSentenceChanged != null,
            onTextChange = onSentenceChanged,
            pageNavigation = pageNavigation,
            modifier = barModifier,
        )
    }

    if (isEmptyPage) {
        // A Page with no usable Buttons (empty, or a grid that only references
        // missing Buttons) explains itself and keeps the Message usable.
        BoxWithConstraints(modifier = modifier.fillMaxSize().pageBackground(boardBackground, pageBackgroundImage, backgroundFit)) {
            val symbolBarMaxHeight = maxHeight * symbolBarPresentation.maximumViewportFraction
            Column(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (showMessageBar) {
                    messageBar(Modifier.fillMaxWidth().heightIn(max = symbolBarMaxHeight))
                }
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("board-empty"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.board_page_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }
        }
    } else if (isAbsoluteLayout) {
        if (showMessageBar) {
            BoxWithConstraints(modifier = modifier.fillMaxSize().pageBackground(boardBackground, pageBackgroundImage, backgroundFit)) {
                val symbolBarMaxHeight = maxHeight * symbolBarPresentation.maximumViewportFraction
                Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    messageBar(Modifier.fillMaxWidth().heightIn(max = symbolBarMaxHeight))
                    Spacer(modifier = Modifier.height(8.dp))
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        RenderAbsoluteButtons(
                            board,
                            imagesById,
                            extractedImages,
                            isEditMode,
                            isMaskMode,
                            onButtonClick,
                            homeBoardId,
                            effectiveBoardSettings,
                            showHiddenButtons,
                            predictionLabels,
                            highlightedButtonId,
                            guidedButtonId,
                            guidedButtonDescription,
                            onButtonLongClick, settings, feedback
                        )
                    }
                }
            }
        } else {
            BoxWithConstraints(modifier = modifier.fillMaxSize().pageBackground(boardBackground, pageBackgroundImage, backgroundFit).padding(8.dp)) {
                RenderAbsoluteButtons(
                    board,
                    imagesById,
                    extractedImages,
                    isEditMode,
                    isMaskMode,
                    onButtonClick,
                    homeBoardId,
                    effectiveBoardSettings,
                    showHiddenButtons,
                    predictionLabels,
                    highlightedButtonId,
                    guidedButtonId,
                    guidedButtonDescription,
                    onButtonLongClick, settings, feedback
                )
            }
        }
    } else if (grid != null) {
        val columns = grid.columns.coerceAtLeast(1)
        val rows = grid.rows.coerceAtLeast(1)
        val desiredHorizontalGapDp = effectiveBoardSettings.cellGapDp?.toDouble() ?: 4.0
        val desiredVerticalGapDp = effectiveBoardSettings.cellGapDp?.toDouble() ?: 8.0
        val desiredOuterMarginDp = effectiveBoardSettings.outerMarginDp?.toDouble() ?: 8.0
        val touchExitEligible = state.allowTouchExit &&
            board.hasVisibleButton(showHiddenButtons) &&
            touchExitIsActive(
                isEditMode = isEditMode,
                holdToSelectMillis = settings.holdToSelectMillis,
                selectOnTouchDown = settings.selectOnTouchDown,
                selectOnTouchExit = settings.selectOnTouchExit,
            )
        // Shared across Touch Exit and per-Button actions (tap, dwell, switch, accessibility)
        // so switching access methods within the debounce window cannot bypass the guard.
        val boardDebouncer = remember(homeBoardId, board.id) { SelectionDebouncer() }
        val hapticView = LocalView.current
        val accessHost = LocalAccessInputHost.current
        val previewScope = rememberCoroutineScope()
        val previewJobHolder = remember(homeBoardId, board.id) { PreviewJobHolder() }
        
        // Use Column/Row for fixed grid that fills the space
        BoxWithConstraints(modifier = modifier.fillMaxSize().pageBackground(boardBackground, pageBackgroundImage, backgroundFit)) {
            val symbolBarMaxHeight = maxHeight * symbolBarPresentation.maximumViewportFraction
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                if (showMessageBar) {
                    messageBar(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, top = 8.dp, end = 8.dp)
                            .heightIn(max = symbolBarMaxHeight)
                    )
                }

                val gridItems = remember(grid, buttonsById) {
                    buildBoardGridItems(grid, buttonsById)
                }
                val pageScrollState = rememberScrollState()
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
                                    onFocusCell(stepFocusableBoardCell(grid, buttonsById, isVisible, focusedCell, 0, -1)); true
                                }
                                Key.DirectionRight -> {
                                    onFocusCell(stepFocusableBoardCell(grid, buttonsById, isVisible, focusedCell, 0, 1)); true
                                }
                                Key.DirectionUp -> {
                                    onFocusCell(stepFocusableBoardCell(grid, buttonsById, isVisible, focusedCell, -1, 0)); true
                                }
                                Key.DirectionDown -> {
                                    onFocusCell(stepFocusableBoardCell(grid, buttonsById, isVisible, focusedCell, 1, 0)); true
                                }
                                Key.MoveHome -> {
                                    onFocusCell(firstFocusableBoardCell(grid, buttonsById, isVisible)); true
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
                    // Keep scrollable Phrase trays scrollable when too many rows would crush Buttons below usable size.
                    val rowsFitForTouchExit = scrollingMinimumCellHeight == null ||
                        io.github.jdreioe.wingmate.domain.obf.rowsFitWithoutScrolling(
                            availableHeightDp = (maxHeight.value - desiredOuterMarginDp * 2.0).coerceAtLeast(0.0),
                            rows = rows,
                            verticalGapDp = desiredVerticalGapDp,
                            minimumRowHeightDp = minOf(scrollingMinimumCellHeight.value.toDouble(), 44.0),
                        )
                    val effectiveTouchExitActive = touchExitEligible && rowsFitForTouchExit
                    val disableGridScroll = settings.selectOnTouchDown || effectiveTouchExitActive
                    val constrainHeight = scrollingMinimumCellHeight == null || disableGridScroll
                    DisposableEffect(homeBoardId, board.id, effectiveTouchExitActive) {
                        onDispose {
                            previewJobHolder.job?.cancel()
                            previewJobHolder.job = null
                            feedback.onCancelAuditoryCue()
                        }
                    }
                    val boardTouchExit = if (effectiveTouchExitActive) {
                        BoardTouchExit(
                            isButtonSelectable = isVisible,
                            onPreview = { button ->
                                if (settings.auditoryFishingEnabled && accessHost?.state?.isPaused != true) {
                                    val label = resolveTouchExitPreviewLabel(
                                        button = button,
                                        boardStrings = board.strings,
                                        locale = settings.primaryLanguage,
                                        predictionLabels = predictionLabels,
                                    )
                                    previewJobHolder.job?.cancel()
                                    previewJobHolder.job = null
                                    // Stop any currently playing cue immediately before resolving the next Button's voice.
                                    feedback.onCancelAuditoryCue()
                                    if (label.isNotBlank()) {
                                        previewJobHolder.job = previewScope.launch {
                                            feedback.onAuditoryCue(button, label)
                                        }
                                    }
                                }
                            },
                            onActivate = { item, button ->
                                previewJobHolder.job?.cancel()
                                previewJobHolder.job = null
                                feedback.onCancelAuditoryCue()
                                val accepted = boardDebouncer.tryActivate(
                                    button.id,
                                    Clock.System.now().toEpochMilliseconds(),
                                    settings.selectionDebounceMillis,
                                )
                                if (!accepted) {
                                    hapticView.performAccessHaptic(AccessHaptic.REJECT)
                                } else {
                                    hapticView.performAccessHaptic(AccessHaptic.CONFIRM)
                                    val label = resolveTouchExitActivationLabel(
                                        button = button,
                                        boardStrings = board.strings,
                                        locale = settings.primaryLanguage,
                                        predictionLabels = predictionLabels,
                                    )
                                    feedback.onActivated(button, label)
                                    onCellClick?.invoke(item.row, item.column, button) ?: onButtonClick(button)
                                }
                            },
                            onCancel = {
                                previewJobHolder.job?.cancel()
                                previewJobHolder.job = null
                                feedback.onCancelAuditoryCue()
                            },
                        )
                    } else {
                        null
                    }
                    val prelimMarginDp = minOf(
                        desiredOuterMarginDp,
                        ((maxWidth.value - io.github.jdreioe.wingmate.domain.obf.MIN_KEYGUARD_CELL_SIZE_DP * columns) / 2.0)
                            .coerceAtLeast(0.0),
                        if (constrainHeight) {
                            ((maxHeight.value - io.github.jdreioe.wingmate.domain.obf.MIN_KEYGUARD_CELL_SIZE_DP * rows) / 2.0)
                                .coerceAtLeast(0.0)
                        } else {
                            desiredOuterMarginDp
                        },
                    ).dp
                    val prelimAvailableHeight = (maxHeight - prelimMarginDp * 2).coerceAtLeast(0.dp)
                    val minimumCellHeight = (if (board.compactGrid) 72.dp else 96.dp) *
                        settings.inputFieldScale.coerceIn(0.5f, 2f)
                    val minimumContentHeight = minimumCellHeight * rows + desiredVerticalGapDp.dp * (rows - 1)
                    val defaultHeightFraction = if (board.compactGrid && prelimAvailableHeight > 0.dp) {
                        (minimumContentHeight / prelimAvailableHeight).coerceIn(0.15f, 1f)
                    } else {
                        1f
                    }
                    var previewHeightFraction by remember(
                        board.id,
                        board.gridHeightFraction,
                        defaultHeightFraction,
                    ) {
                        mutableFloatStateOf((board.gridHeightFraction ?: defaultHeightFraction).coerceIn(0.15f, 1f))
                    }
                    val effectiveSpacing = io.github.jdreioe.wingmate.domain.obf.effectiveKeyguardSpacing(
                        viewportWidthDp = maxWidth.value.toDouble(),
                        viewportHeightDp = maxHeight.value.toDouble(),
                        columns = columns,
                        rows = rows,
                        desiredHorizontalGapDp = desiredHorizontalGapDp,
                        desiredVerticalGapDp = desiredVerticalGapDp,
                        desiredOuterMarginDp = desiredOuterMarginDp,
                        heightFraction = previewHeightFraction.toDouble(),
                        constrainHeight = constrainHeight,
                    )
                    val horizontalGapDp = effectiveSpacing.horizontalGapDp.dp
                    val verticalGapDp = effectiveSpacing.verticalGapDp.dp
                    val outerMarginDp = effectiveSpacing.outerMarginDp.dp
                    val availableGridHeight = (maxHeight - outerMarginDp * 2).coerceAtLeast(0.dp)
                    val fittedHeight = availableGridHeight * effectiveSpacing.effectiveHeightFraction.toFloat()
                    // Touch-down and Touch Exit turn scrolling off, so every row must fit.
                    val contentHeight = scrollingMinimumCellHeight
                        ?.takeUnless { disableGridScroll }
                        ?.let { minimumHeight ->
                            val gap = verticalGapDp
                            val visibleRows = ((availableGridHeight + gap) / (minimumHeight + gap))
                                .toInt().coerceIn(1, rows)
                            val rowHeight = maxOf(
                                minimumHeight,
                                (availableGridHeight - gap * (visibleRows - 1)) / visibleRows,
                            ).let { fitted -> scrollingMaximumCellHeight?.let { minOf(fitted, maxOf(it, minimumHeight)) } ?: fitted }
                            rowHeight * rows + gap * (rows - 1)
                        }
                        ?: fittedHeight
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(outerMarginDp)
                            .verticalScroll(pageScrollState, enabled = !disableGridScroll)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(contentHeight)
                                .align(if (scrollingMaximumCellHeight != null) Alignment.TopCenter else Alignment.BottomCenter)
                        ) {
                            SpanningBoardGrid(
                                rows = rows,
                                columns = columns,
                                items = gridItems,
                                modifier = gridNavModifier,
                                horizontalGapDp = horizontalGapDp,
                                verticalGapDp = verticalGapDp,
                                onMove = onCellMove,
                                cellMenu = cellMenu,
                                reorder = reorder,
                                touchExit = boardTouchExit,
                                gestureKey = homeBoardId to board.id,
                                selectedField = selectedFieldAnchor,
                                selectedFieldSpans = selectedFieldSpans,
                                onResizeField = onResizeField,
                                onRemoveField = onRemoveField,
                                focusedCell = focusedCell
                            ) { item ->
                            val button = item.button
                            val isVisible = button != null && isBoardButtonVisible(button, isEditMode, showHiddenButtons)
                            Box(modifier = Modifier.fillMaxSize()) {
                                if (button != null && isVisible) {
                                    val image = button.imageId?.let { imagesById[it] }
                                    ObfButtonContent(
                                        settings = settings,
                                        feedback = feedback,
                                        debouncer = boardDebouncer,
                                        button = button,
                                        image = image,
                                        extractedImageBytes = button.imageId?.let {
                                            image?.path?.let { path -> extractedImages[path] }
                                        },
                                        onClick = {
                                            onCellClick?.invoke(item.row, item.column, button)
                                                ?: onButtonClick(button)
                                        },
                                        onLongClick = onButtonLongClick?.let { callback -> { callback(button) } },
                                        isEditMode = isEditMode,
                                        isMaskMode = isMaskMode,
                                        isTemporarilyRevealed = button.hidden && !isEditMode && showHiddenButtons,
                                        isHomeLink = button.isHomeNavigation(homeBoardId),
                                        boardStrings = board.strings,
                                        locale = settings.primaryLanguage,
                                        boardSettings = effectiveBoardSettings,
                                        labelOverride = predictionLabels[button.id],
                                        isSelectionHighlighted = button.id == highlightedButtonId,
                                        isGuidedHighlighted = button.id == guidedButtonId,
                                        guidedDescription = guidedButtonDescription,
                                        fieldFontScale = fieldFontScale(item.rowSpan, item.columnSpan),
                                        rowSpan = item.rowSpan
                                    )
                                } else if (isEditMode && button == null && !isMaskMode) {
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
        BoxWithConstraints(modifier = modifier.fillMaxSize().pageBackground(boardBackground, pageBackgroundImage, backgroundFit)) {
            val symbolBarMaxHeight = maxHeight * symbolBarPresentation.maximumViewportFraction
            Column(
                modifier = Modifier.fillMaxSize().padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (showMessageBar) {
                    messageBar(Modifier.fillMaxWidth().heightIn(max = symbolBarMaxHeight))
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
                                    ObfButtonContent(
                                        settings = settings,
                                        feedback = feedback,
                                        button = button,
                                        image = image,
                                        extractedImageBytes = button.imageId?.let {
                                            image?.path?.let { path -> extractedImages[path] }
                                        },
                                        onClick = { onButtonClick(button) },
                                        onLongClick = onButtonLongClick?.let { cb -> ({ cb(button) }) },
                                        isEditMode = isEditMode,
                                        isMaskMode = isMaskMode,
                                        isTemporarilyRevealed = button.hidden && !isEditMode && showHiddenButtons,
                                        isHomeLink = button.isHomeNavigation(homeBoardId),
                                        boardStrings = board.strings,
                                        locale = settings.primaryLanguage,
                                        boardSettings = effectiveBoardSettings,
                                        labelOverride = predictionLabels[button.id],
                                        isSelectionHighlighted = button.id == highlightedButtonId,
                                        isGuidedHighlighted = button.id == guidedButtonId,
                                        guidedDescription = guidedButtonDescription,
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

@Preview(showBackground = true, widthDp = 600, heightDp = 400)
@Composable
internal fun ObfBoardContentPreview() {
    val buttons = listOf(
        ObfButton(id = "yes", label = "Yes"),
        ObfButton(id = "no", label = "No"),
        ObfButton(id = "help", label = "I need help"),
        ObfButton(id = "food", label = "Food", loadBoard = ObfLoadBoard(id = "food", name = "Food")),
    )
    AppTheme {
        ObfBoardContent(
            state = ObfBoardUiState(
                board = ObfBoard(
                    format = "open-board-0.1",
                    id = "preview",
                    buttons = buttons,
                    grid = ObfGrid(2, 2, listOf(listOf("yes", "no"), listOf("help", "food"))),
                ),
                messageText = "I need help",
                focusedCell = 1 to 0,
            ),
            onButtonClick = {},
        )
    }
}

/** Whether run mode renders at least one Button; a grid only renders Buttons its order places. */
internal fun ObfBoard.hasVisibleButton(showHiddenButtons: Boolean): Boolean {
    val visibleIds = buttons
        .filter { isBoardButtonVisible(it, isEditMode = false, showHiddenButtons = showHiddenButtons) }
        .mapTo(HashSet()) { it.id }
    val grid = grid
    return if (grid == null || isAbsoluteLayout) {
        visibleIds.isNotEmpty()
    } else {
        grid.fieldItems().any { it.buttonId in visibleIds }
    }
}

/** Paints the Page background color, then its background image (#103) scaled by [fit] and centered. */
internal fun Modifier.pageBackground(color: Color, image: ImageBitmap?, fit: PageBackgroundFit): Modifier {
    val colored = background(color)
    if (image == null || image.width == 0 || image.height == 0) return colored
    return colored.drawBehind {
        val scale = when (fit) {
            PageBackgroundFit.Fill -> maxOf(size.width / image.width, size.height / image.height)
            PageBackgroundFit.Fit -> minOf(size.width / image.width, size.height / image.height)
        }
        val drawn = IntSize((image.width * scale).roundToInt(), (image.height * scale).roundToInt())
        val offset = IntOffset(((size.width - drawn.width) / 2).roundToInt(), ((size.height - drawn.height) / 2).roundToInt())
        clipRect { drawImage(image, dstOffset = offset, dstSize = drawn, filterQuality = FilterQuality.Medium) }
    }
}
