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
@Composable
internal fun ObfButtonContent(
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
    enabled: Boolean = true,
    isSelectionHighlighted: Boolean = false,
    fieldFontScale: Float = 1f,
    rowSpan: Int = 1,
    onLongClick: (() -> Unit)? = null,
    settings: Settings,
    feedback: BoardFeedback = remember { BoardFeedback() },
    debouncer: SelectionDebouncer? = null,
) {
    // #118 / #405: per-target activation debounce. When the board supplies a shared
    // SelectionDebouncer, Touch Exit, tap, dwell, switch, and accessibility actions
    // all share one per-Button guard so switching input methods cannot bypass debounce.
    val selectionDebouncer = debouncer ?: remember(button.id) { SelectionDebouncer() }
    // #118: Edit-mode taps are deliberate field/dialog operations and must never be
    // blocked by the run-mode selection debounce.
    val hapticView = LocalView.current
    fun tryActivate(): Boolean {
        if (!enabled) return false
        if (isEditMode) return true
        val accepted = selectionDebouncer.tryActivate(
            button.id,
            Clock.System.now().toEpochMilliseconds(),
            settings.selectionDebounceMillis
        )
        if (!accepted) {
            // Rejected repeat activation inside the debounce window.
            hapticView.performAccessHaptic(AccessHaptic.REJECT)
        } else {
            hapticView.performAccessHaptic(AccessHaptic.CONFIRM)
        }
        return accepted
    }
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
    val displayLabel = labelOverride
        ?: if (button.type == ObfButtonType.NGramPrediction ||
            parseObfButtonActions(button).any { it === ObfButtonActionEffect.Predictions }
        ) "" else resolveObfLocalizedString(boardStrings, locale, button.label)
    val displayVocalization = resolveObfLocalizedString(boardStrings, locale, button.vocalization)
    val temporarilyRevealedDescription = stringResource(R.string.board_workspace_temporarily_revealed)
    val hiddenDescription = stringResource(R.string.board_dialog_hidden)
    val cellActions = LocalBoardCellActions.current
    val boundedFontScale = fieldFontScale.coerceIn(1f, 2f)
    val scaledLabelMedium = MaterialTheme.typography.labelMedium.copy(
        fontSize = MaterialTheme.typography.labelMedium.fontSize * boundedFontScale,
        lineHeight = MaterialTheme.typography.labelMedium.lineHeight * boundedFontScale,
        fontWeight = FontWeight.Bold
    )
    val scaledLabelSmall = MaterialTheme.typography.labelLarge.copy(
        fontSize = MaterialTheme.typography.labelLarge.fontSize * boundedFontScale,
        lineHeight = MaterialTheme.typography.labelLarge.lineHeight * boundedFontScale,
        fontWeight = FontWeight.Medium
    )
    // Text-only Buttons (most Phrases) read like the #299 prototype: sentence
    // text top-left, short words centered and larger.
    val phraseTextStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = MaterialTheme.typography.bodyLarge.fontSize * boundedFontScale,
        lineHeight = MaterialTheme.typography.bodyLarge.fontSize * boundedFontScale * 1.25f,
    )
    val largeWindow = currentWindowWidth().isLarge
    val readingTextStyle = MaterialTheme.typography.titleLarge.copy(
        fontSize = MaterialTheme.typography.titleLarge.fontSize * boundedFontScale,
        lineHeight = MaterialTheme.typography.titleLarge.lineHeight * boundedFontScale,
        fontWeight = FontWeight.Normal,
    )
    val shortWordStyle = MaterialTheme.typography.titleLarge.copy(
        fontSize = MaterialTheme.typography.titleLarge.fontSize * boundedFontScale,
        lineHeight = MaterialTheme.typography.titleLarge.lineHeight * boundedFontScale,
        fontWeight = FontWeight.Medium
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
    
    val primaryAction = {
        if (tryActivate()) {
            if (animateSelection) isSelected = true
            feedback.onActivated(button, displayLabel.orEmpty())
            onClick()
        }
    }
    if (!isEditMode && enabled) RegisterAccessTarget(accessTargetId, primaryAction)
    val dwellProgress = if (accessHost?.state?.currentTargetId == accessTargetId) accessHost.state.dwellProgress else 0f

    LaunchedEffect(isHovered, settings.auditoryFishingEnabled) {
        val label = displayLabel ?: displayVocalization ?: ""
        if (isHovered && accessHost?.state?.isPaused != true && settings.auditoryFishingEnabled && label.isNotBlank()) {
            feedback.onAuditoryCue(button, label)
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
            ?: MaterialTheme.colorScheme.surfaceContainerHigh
    }
    
    val borderColor = if (settings.highContrastMode) {
        highContrastContent
    } else {
        button.borderColor?.let { runCatching { parseHexToColor(it) }.getOrNull() }
    }
    
    val contentColor = when {
        settings.highContrastMode -> highContrastContent
        resolvedBackgroundColor != null -> contrastingContentColor(bgColor, backdrop = MaterialTheme.colorScheme.background)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val buttonShape = button.shape.toShape()

    
    // Spec priority: data → path → url → symbol (extracted zip bytes count as path).
    val imageSources = remember(image) { obfImageSources(image) }
    val imageBitmap = remember(imageSources, extractedImageBytes) {
        imageSources.firstNotNullOfOrNull { source ->
            when (source) {
                is ObfMediaSource.Data -> runCatching {
                    val base64 = source.value.substringAfter("base64,", source.value)
                    Base64Decoder.decode(base64).toComposeImageBitmap()
                }.getOrNull()
                is ObfMediaSource.Path -> extractedImageBytes?.toComposeImageBitmap()
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
            .alpha(if (!enabled || (button.hidden && isEditMode)) 0.5f else 1f)
            .semantics {
                role = Role.Button
                if (!enabled) disabled()
                // The visible label is already read; state only adds what sight alone shows.
                if (isTemporarilyRevealed) stateDescription = temporarilyRevealedDescription
                else if (button.hidden && isEditMode) stateDescription = hiddenDescription
                if (cellActions.isNotEmpty()) customActions = cellActions
            }
            // Right click does what a long press does, before hold or touch-down selection sees it.
            .onSecondaryClick(enabled && onLongClick != null) { onLongClick?.invoke() }
            .let { baseModifier ->
                if (!enabled) baseModifier else baseModifier.pointerInput(Unit) {
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
            }
            .let { baseModifier ->
                if (!enabled) {
                    baseModifier
                } else if (settings.holdToSelectMillis > 0 && !isEditMode) {
                    // A screen reader's double-tap is already deliberate, so it activates without the hold.
                    baseModifier.semantics(mergeDescendants = true) {
                        onClick { primaryAction(); true }
                    }.pointerInput(settings.holdToSelectMillis) {
                        detectTapGestures(
                            onLongPress = { onLongClick?.invoke() },
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
                } else if (settings.selectOnTouchDown && !isEditMode) {
                    baseModifier.semantics(mergeDescendants = true) {
                        onClick { primaryAction(); true }
                    }.selectOnTouchDown(enabled = true) { primaryAction() }
                } else {
                    baseModifier.combinedClickable(
                        onClick = { primaryAction() },
                        onLongClick = onLongClick
                    )
                }
            }
            .accessTargetFocus(accessTargetId, if (isEditMode || !enabled) null else accessHost),
        shape = buttonShape,
        colors = CardDefaults.elevatedCardColors(containerColor = bgColor),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
        border = if (borderColor != null || settings.highContrastMode) {
             androidx.compose.foundation.BorderStroke(if (settings.highContrastMode) 3.dp else 2.dp, borderColor ?: highContrastContent)
        } else null,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.Center) {
            // Labels follow the height of one grid cell so they scale up cleanly on tablets
            // and larger grids instead of staying at a fixed small point size.
            val unitHeightDp = maxHeight.value / rowSpan.coerceAtLeast(1)
            val symbolLabelSp = (unitHeightDp * 0.16f).coerceIn(14f, 32f) * boundedFontScale
            val dynamicSymbolLabelStyle = MaterialTheme.typography.titleMedium.copy(
                fontSize = symbolLabelSp.sp,
                lineHeight = (symbolLabelSp * 1.15f).sp,
                fontWeight = FontWeight.SemiBold,
            )
            val shortWordSp = (unitHeightDp * 0.22f).coerceIn(16f, 40f) * boundedFontScale
            val dynamicShortWordStyle = shortWordStyle.copy(
                fontSize = shortWordSp.sp,
                lineHeight = (shortWordSp * 1.15f).sp,
                fontWeight = FontWeight.SemiBold,
            )
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

                if (showLbl && !showImg) {
                    // A label that is a "…" cut of what the Button says (a Speech Screen's
                    // paragraph cue) shows the whole text, so the speaker can read along; it
                    // wraps to fill the cell. The saved label is unchanged.
                    val fullText = expandedButtonText(displayLabel, displayVocalization)
                    val labelText = fullText ?: displayLabel ?: displayVocalization ?: ""
                    // One rule for every text-only Button: a word or short phrase ("all done",
                    // "Hvordan går det?") is centred in the large style; a sentence reads from
                    // the top start, like text.
                    val shortLabel = isShortButtonLabel(labelText)
                    Box(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = if (shortLabel) Alignment.Center else Alignment.TopStart,
                    ) {
                        Text(
                            text = labelText,
                            style = when {
                                shortLabel -> dynamicShortWordStyle
                                // Larger reading text where a tablet's cells have room for it.
                                fullText != null && largeWindow -> readingTextStyle
                                else -> phraseTextStyle
                            },
                            textAlign = if (shortLabel) TextAlign.Center else TextAlign.Start,
                            color = contentColor,
                            maxLines = when {
                                shortLabel -> 2
                                fullText != null -> Int.MAX_VALUE
                                else -> 3
                            },
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else if (effectiveBoardSettings.labelAtTop && showImg && showLbl) {
                    val labelText = displayLabel ?: displayVocalization ?: ""
                    Text(
                        text = labelText,
                        style = dynamicSymbolLabelStyle,
                        textAlign = TextAlign.Center,
                        color = contentColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
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
                            // The label above already names the Button.
                            contentDescription = null,
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
                                // Only symbol-only Buttons need the image to carry their name.
                                contentDescription = if (showLbl) null else displayLabel,
                                modifier = Modifier.weight(1f).fillMaxWidth().padding(2.dp).clip(buttonShape)
                            )
                        }
                    }
                    if (showLbl) {
                        val labelText = displayLabel ?: displayVocalization ?: ""
                        Text(
                            text = labelText,
                            style = dynamicSymbolLabelStyle,
                            textAlign = TextAlign.Center,
                            color = contentColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            if (button.soundId != null && button.loadBoard == null && !isHomeLink && !isTemporarilyRevealed) {
                // Marks a Phrase that plays its own recording.
                Icon(
                    Icons.Default.Mic,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.TopEnd).size(16.dp)
                )
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
                        .testTag("selection-highlight")
                        .padding(8.dp)
                ) {
                    Box(Modifier.matchParentSize().border(6.dp, Color.Black, buttonShape))
                    Box(Modifier.matchParentSize().padding(2.dp).border(2.dp, Color.White, buttonShape))
                }
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
internal fun BoxWithConstraintsScope.RenderAbsoluteButtons(
    board: ObfBoard,
    imagesById: Map<String, ObfImage>,
    extractedImages: Map<String, ByteArray>,
    isEditMode: Boolean,
    onButtonClick: (ObfButton) -> Unit,
    homeBoardId: String?,
    boardSettings: ResolvedBoardSettings,
    showHiddenButtons: Boolean,
    predictionLabels: Map<String, String>,
    highlightedButtonId: String? = null,
    onButtonLongClick: ((ObfButton) -> Unit)? = null,
    settings: Settings,
    feedback: BoardFeedback,
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

internal fun ObfButton.isHomeNavigation(homeBoardId: String?): Boolean =
    resolvedActions().any { it.trim().equals(":home", ignoreCase = true) } ||
        (homeBoardId != null && loadBoard?.id == homeBoardId)

@Composable
internal fun BoardSymbolImage(
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
internal fun SymbolUnavailablePlaceholder(
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

/** A word or short phrase, shown centred and large on a text-only Button: up to 3 words and 24 characters. */
internal fun isShortButtonLabel(label: String): Boolean {
    val text = label.trim()
    return text.length <= 24 && text.split(Regex("\\s+")).size <= 3
}

/**
 * The whole spoken text when [label] is a shortened cue for it ("Good evening, everyone…"), as
 * Speech Screens store long paragraphs; otherwise null and the label shows as it is.
 */
internal fun expandedButtonText(label: String?, vocalization: String?): String? {
    if (label == null || vocalization == null) return null
    val trimmedLabel = label.trim()
    val cue = when {
        trimmedLabel.endsWith("…") -> trimmedLabel.removeSuffix("…").trimEnd()
        trimmedLabel.endsWith("...") -> trimmedLabel.removeSuffix("...").trimEnd()
        else -> return null
    }
    val spoken = vocalization.replace(Regex("\\s+"), " ").trim()
    return spoken.takeIf { cue.isNotEmpty() && it.length > cue.length && it.startsWith(cue) }
}
