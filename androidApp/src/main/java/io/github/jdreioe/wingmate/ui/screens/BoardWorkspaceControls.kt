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
fun SymbolBar(
    selectedButtons: List<Pair<ObfButton, ImageBitmap?>>,
    messageText: String,
    imagesById: Map<String, io.github.jdreioe.wingmate.domain.obf.ObfImage>,
    extractedImages: Map<String, ByteArray>,
    onSpeak: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    showSpeak: Boolean = true,
    showDelete: Boolean = true,
    showClear: Boolean = true,
    presentation: SymbolBarPresentation = SymbolBarPresentation.Normal,
    // When enabled (with onTextChange) the Message area becomes an editable
    // text field sharing the same bar; otherwise it renders read-only.
    editable: Boolean = false,
    onTextChange: ((String) -> Unit)? = null,
    pageNavigation: PageNavigation? = null,
) {
    val textScrollState = rememberScrollState()
    val large = currentWindowWidth().isLarge
    val textStyle = when (presentation) {
        SymbolBarPresentation.Normal -> if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium
        SymbolBarPresentation.Fullscreen -> MaterialTheme.typography.headlineSmall
    }
    // A fixed number of whole lines (2 on phones, 3 on larger windows; more scroll inside), so
    // the Page below never moves while the Message grows. Fullscreen shows it to a partner and
    // keeps growing up to its cap.
    val lineHeight = with(LocalDensity.current) { textStyle.lineHeight.toDp() }
    val textHeightModifier = when (presentation) {
        SymbolBarPresentation.Normal -> Modifier.height(lineHeight * (if (large) 3 else 2) + 8.dp)
        SymbolBarPresentation.Fullscreen -> Modifier.heightIn(max = lineHeight * presentation.maxTextLines + 8.dp)
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
        val canEdit = editable && onTextChange != null
        // The Message itself, read-only or editable; the same in both arrangements below.
        val messageArea: @Composable (Modifier) -> Unit = { areaModifier ->
            Box(modifier = areaModifier.then(textHeightModifier), contentAlignment = Alignment.CenterStart) {
                if (canEdit) {
                    var fieldValue by remember { mutableStateOf(TextFieldValue(messageText)) }
                    LaunchedEffect(messageText) {
                        // External updates (button taps, clear) win; typing echoes back
                        // identical text so this branch is a no-op while composing.
                        if (fieldValue.text != messageText) {
                            fieldValue = TextFieldValue(messageText, selection = TextRange(messageText.length))
                        }
                    }
                    BasicTextField(
                        value = fieldValue,
                        onValueChange = { newValue ->
                            // The input session echoes the current text again once the
                            // field is focused. Parents store a string, so only an edit
                            // should notify them.
                            val textChanged = newValue.text != fieldValue.text
                            fieldValue = newValue
                            if (textChanged) onTextChange(newValue.text)
                        },
                        textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .verticalScroll(textScrollState)
                            .clearAndSetSemantics {
                                contentDescription = messageText
                            },
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = messageText,
                            style = textStyle,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
        val controls: @Composable () -> Unit = {
            // Large targets in a fixed order (Delete word, Clear, Speak), labelled where there is
            // room. Delete word uses Backspace: the trash can means deleting things elsewhere.
            MessageBarControls(
                large = large,
                showSpeak = showSpeak,
                showDelete = showDelete,
                showClear = showClear,
                onSpeak = onSpeak,
                onDelete = onDelete,
                onClear = onClear,
            )
        }
        if (large) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                pageNavigation?.let {
                    PageNavigationControls(it, large = true)
                    VerticalDivider(modifier = Modifier.padding(horizontal = 8.dp).height(40.dp))
                }
                messageArea(Modifier.weight(1f))
                if (showSpeak || showDelete || showClear) {
                    VerticalDivider(modifier = Modifier.padding(horizontal = 8.dp).height(40.dp))
                }
                controls()
            }
        } else {
            // A phone keeps the Message the full width, with its controls in a row below it.
            Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                messageArea(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    pageNavigation?.let { PageNavigationControls(it, large = false) }
                    Spacer(Modifier.weight(1f))
                    controls()
                }
            }
        }
    }
}

/**
 * Back (the previous Page followed) and Start page for a Screen with several Pages. Always in
 * the same place at the start of the Message bar, disabled rather than hidden when there is
 * nowhere to go, so nothing shifts as the Communicator moves between Pages.
 */
class PageNavigation(
    val canGoBack: Boolean,
    val atStartPage: Boolean,
    val onBack: () -> Unit,
    val onStartPage: () -> Unit,
)

@Composable
internal fun PageNavigationControls(navigation: PageNavigation, large: Boolean) {
    val back = stringResource(R.string.board_page_back)
    val start = stringResource(R.string.board_page_start)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (large) {
            FilledTonalButton(onClick = navigation.onBack, enabled = navigation.canGoBack, modifier = Modifier.heightIn(min = 56.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(back)
            }
            FilledTonalButton(onClick = navigation.onStartPage, enabled = !navigation.atStartPage, modifier = Modifier.heightIn(min = 56.dp)) {
                Icon(Icons.Default.Home, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(start)
            }
        } else {
            FilledTonalIconButton(onClick = navigation.onBack, enabled = navigation.canGoBack, modifier = Modifier.size(56.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = back)
            }
            FilledTonalIconButton(onClick = navigation.onStartPage, enabled = !navigation.atStartPage, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Default.Home, contentDescription = start)
            }
        }
    }
}

@Composable
internal fun MessageBarControls(
    large: Boolean,
    showSpeak: Boolean,
    showDelete: Boolean,
    showClear: Boolean,
    onSpeak: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
) {
    val speak = stringResource(R.string.board_workspace_speak_sentence)
    val delete = stringResource(R.string.board_workspace_delete_last)
    val clear = stringResource(R.string.board_workspace_clear_sentence)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (large) {
            if (showDelete) {
                OutlinedButton(onClick = onDelete, modifier = Modifier.heightIn(min = 56.dp).semantics { contentDescription = delete }) {
                    Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.board_message_delete_word))
                }
            }
            if (showClear) {
                OutlinedButton(onClick = onClear, modifier = Modifier.heightIn(min = 56.dp).semantics { contentDescription = clear }) {
                    Icon(Icons.Default.Clear, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.board_message_clear))
                }
            }
            if (showSpeak) {
                Button(onClick = onSpeak, modifier = Modifier.heightIn(min = 56.dp).semantics { contentDescription = speak }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.playback_play), style = MaterialTheme.typography.titleMedium)
                }
            }
        } else {
            if (showDelete) {
                IconButton(onClick = onDelete, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = delete)
                }
            }
            if (showClear) {
                IconButton(onClick = onClear, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.Clear, contentDescription = clear)
                }
            }
            if (showSpeak) {
                FilledIconButton(onClick = onSpeak, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = speak, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}

@Composable
internal fun GridHeightResizeHandle(
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
    val latestAvailableHeightPx by rememberUpdatedState(availableHeightPx)
    val latestOnFractionPreview by rememberUpdatedState(onFractionPreview)
    val latestOnFractionCommit by rememberUpdatedState(onFractionCommit)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = {
                        dragStartFraction = latestCurrentFraction
                        dragFraction = latestCurrentFraction
                    },
                    onDragEnd = { latestOnFractionCommit(dragFraction) },
                    onDragCancel = { latestOnFractionPreview(dragStartFraction) },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        dragFraction = (dragFraction - dragAmount / latestAvailableHeightPx).coerceIn(0.15f, 1f)
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
