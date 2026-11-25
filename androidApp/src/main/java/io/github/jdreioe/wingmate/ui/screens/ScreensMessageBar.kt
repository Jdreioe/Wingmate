package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.domain.Base64Decoder
import io.github.jdreioe.wingmate.domain.CommunicationAction
import io.github.jdreioe.wingmate.domain.Message
import io.github.jdreioe.wingmate.domain.MessagePartSource
import io.github.jdreioe.wingmate.domain.TextSpan
import io.github.jdreioe.wingmate.domain.obf.BoardSetGraph
import io.github.jdreioe.wingmate.domain.obf.MAX_MESSAGE_BAR_ACTIONS
import io.github.jdreioe.wingmate.domain.obf.MessageBarAction
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import io.github.jdreioe.wingmate.domain.obf.ObfMediaSource
import io.github.jdreioe.wingmate.domain.obf.obfImageSources
import io.github.jdreioe.wingmate.domain.obf.resolveObfLocalizedString

internal data class ScreensMessagePartItem(
    val index: Int,
    val title: String,
    val image: ObfImage? = null,
)

/** Builds chip items for every part in the active Message, resolving symbols from the Screen. */
internal fun buildScreensMessagePartItems(
    message: Message,
    graph: BoardSetGraph?,
    locale: String?,
): List<ScreensMessagePartItem> = message.parts.mapIndexed { index, part ->
    val fallbackTitle = part.displayText.trim()
    val source = part.source as? MessagePartSource.ScreenButton
    val board = source
        ?.takeIf { graph != null && it.screenId == graph.boardSet.id }
        ?.let { graph?.boardsById?.get(it.pageId) }
    val button = board?.buttons?.firstOrNull { it.id == source.buttonId }
    val resolvedLabel = button?.let { resolveObfLocalizedString(board.strings, locale, it.label) }
        ?.trim()
        ?.takeIf(String::isNotEmpty)
    val image = button?.imageId?.let { id -> board.images.firstOrNull { it.id == id } }
    ScreensMessagePartItem(
        index = index,
        title = resolvedLabel ?: fallbackTitle,
        image = image,
    )
}

/** Builds a [CommunicationAction.ReplaceRange] removing the Message part at [index]. */
internal fun removeMessagePartAction(message: Message, index: Int): CommunicationAction? {
    if (index !in message.parts.indices) return null
    val start = message.parts.take(index).sumOf { it.displayText.length }
    val length = message.parts[index].displayText.length
    if (length <= 0) return null
    return CommunicationAction.ReplaceRange(TextSpan(start, start + length), "")
}

/** Pure slot mutations for the 1–4 message bar action square, matching iOS. */
internal fun replaceMessageBarAction(
    current: List<MessageBarAction>,
    index: Int,
    replacement: MessageBarAction,
): List<MessageBarAction> {
    if (index !in current.indices) return current
    if (replacement != current[index] && replacement in current) return current
    return current.mapIndexed { i, action -> if (i == index) replacement else action }
}

internal fun removeMessageBarAction(
    current: List<MessageBarAction>,
    index: Int,
): List<MessageBarAction> {
    if (index !in current.indices || current.size <= 1) return current
    return current.filterIndexed { i, _ -> i != index }
}

internal fun addMessageBarAction(
    current: List<MessageBarAction>,
    addition: MessageBarAction,
): List<MessageBarAction> {
    if (current.size >= MAX_MESSAGE_BAR_ACTIONS || addition in current) return current
    return current + addition
}

internal fun screensMessageBarControlSize(large: Boolean): Dp = if (large) 56.dp else 48.dp

internal fun screensMessageBarHeight(large: Boolean): Dp =
    screensMessageBarControlSize(large) * 2 + 8.dp

/**
 * Grid 3-style Screens message bar: horizontal strip of symbol chips matching the height of the
 * 2×2 action square beside it. Tapping a chip says that part again; its ✕ removes it.
 * When [editable] is true, a subtle keyboard button lets the user edit the full text inline.
 */
@Composable
internal fun ScreensMessageBar(
    parts: List<ScreensMessagePartItem>,
    messageText: String,
    editable: Boolean,
    selectOnTouchDown: Boolean,
    onSpeakPart: (Int) -> Unit,
    onRemovePart: (Int) -> Unit,
    onTextChange: ((String) -> Unit)? = null,
    loadImage: (suspend (ObfImage) -> ImageBitmap?)? = null,
    modifier: Modifier = Modifier,
) {
    val large = currentWindowWidth().isLarge
    val barHeight = screensMessageBarHeight(large)
    val chipImageSize = if (large) 64.dp else 32.dp
    val chipMinHeight = if (large) 96.dp else 48.dp
    val listState = rememberLazyListState()
    var isEditingText by remember(editable) { mutableStateOf(false) }

    LaunchedEffect(parts.size) {
        if (parts.isNotEmpty() && !isEditingText) {
            listState.animateScrollToItem(parts.lastIndex)
        }
    }

    val messageBarDescription = stringResource(R.string.screens_message_bar)
    Surface(
        modifier = modifier
            .height(barHeight)
            .semantics { contentDescription = messageBarDescription }
            .testTag("screens-message-bar"),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
    ) {
        if (isEditingText && editable && onTextChange != null) {
            var fieldValue by remember { mutableStateOf(TextFieldValue(messageText, TextRange(messageText.length))) }
            LaunchedEffect(messageText) {
                if (fieldValue.text != messageText) {
                    fieldValue = TextFieldValue(messageText, TextRange(messageText.length))
                }
            }
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicTextField(
                    value = fieldValue,
                    onValueChange = { next ->
                        val changed = next.text != fieldValue.text
                        fieldValue = next
                        if (changed) onTextChange(next.text)
                    },
                    textStyle = (if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
                        .copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { isEditingText = false },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = stringResource(R.string.common_done),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LazyRow(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    itemsIndexed(parts, key = { index, item -> "part-$index-${item.title}" }) { _, item ->
                        ScreensMessagePartChip(
                            item = item,
                            large = large,
                            imageSize = chipImageSize,
                            minHeight = chipMinHeight,
                            selectOnTouchDown = selectOnTouchDown,
                            loadImage = loadImage,
                            onSpeak = { onSpeakPart(item.index) },
                            onRemove = { onRemovePart(item.index) },
                        )
                    }
                }
                if (editable && onTextChange != null) {
                    IconButton(
                        onClick = { isEditingText = true },
                        modifier = Modifier.padding(end = 4.dp).size(44.dp),
                    ) {
                        Icon(
                            Icons.Default.Keyboard,
                            contentDescription = stringResource(R.string.screens_message_bar_edit_text),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreensMessagePartChip(
    item: ScreensMessagePartItem,
    large: Boolean,
    imageSize: Dp,
    minHeight: Dp,
    selectOnTouchDown: Boolean,
    loadImage: (suspend (ObfImage) -> ImageBitmap?)?,
    onSpeak: () -> Unit,
    onRemove: () -> Unit,
) {
    val sayHint = stringResource(R.string.screens_message_bar_say_part_hint)
    val removeLabel = stringResource(R.string.screens_message_bar_remove_part)
    val imageSources = remember(item.image) { obfImageSources(item.image) }
    val inlineBitmap = remember(imageSources) {
        imageSources.firstNotNullOfOrNull { source ->
            when (source) {
                is ObfMediaSource.Data -> runCatching {
                    val base64 = source.value.substringAfter("base64,", source.value)
                    Base64Decoder.decode(base64).toComposeImageBitmap()
                }.getOrNull()
                else -> null
            }
        }
    }
    val loadedBitmap by produceState<ImageBitmap?>(initialValue = null, item.image, inlineBitmap, loadImage) {
        value = if (inlineBitmap != null || loadImage == null) {
            null
        } else {
            item.image?.let { loadImage(it) }
        }
    }
    val imageBitmap = inlineBitmap ?: loadedBitmap
    val imageModel = remember(imageSources, imageBitmap) {
        if (imageBitmap != null) null
        else imageSources.firstNotNullOfOrNull { (it as? ObfMediaSource.Url)?.value }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .heightIn(min = minHeight)
            .testTag("screens-message-chip-${item.index}"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(start = 10.dp, end = 2.dp),
        ) {
            val speakModifier = if (selectOnTouchDown) {
                Modifier
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        contentDescription = item.title
                        onClick(label = sayHint) { onSpeak(); true }
                    }
                    .selectOnTouchDown(enabled = true) { onSpeak() }
            } else {
                Modifier
                    .semantics(mergeDescendants = true) {
                        contentDescription = item.title
                    }
                    .clickable(onClickLabel = sayHint, role = Role.Button, onClick = onSpeak)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = speakModifier
                    .heightIn(min = minHeight)
                    .padding(vertical = 4.dp),
            ) {
                if (imageBitmap != null || !imageModel.isNullOrBlank()) {
                    BoardSymbolImage(
                        bitmap = imageBitmap,
                        model = imageModel,
                        contentDescription = null,
                        modifier = Modifier
                            .size(imageSize)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                }
                Text(
                    text = item.title.ifEmpty { " " },
                    style = if (large) {
                        MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .let { base ->
                        if (selectOnTouchDown) {
                            base
                                .semantics {
                                    role = Role.Button
                                    contentDescription = removeLabel
                                    onClick { onRemove(); true }
                                }
                                .selectOnTouchDown(enabled = true) { onRemove() }
                        } else {
                            base.clickable(role = Role.Button, onClick = onRemove)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Cancel,
                    contentDescription = removeLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Complete Screens message bar + 1–4 button action square row for both Run and Edit modes. */
@Composable
internal fun ScreensMessageBarRow(
    isEditMode: Boolean,
    parts: List<ScreensMessagePartItem>,
    messageText: String,
    editable: Boolean,
    actions: List<MessageBarAction>,
    hasMessage: Boolean,
    isSpeaking: Boolean,
    isHolding: Boolean,
    selectOnTouchDown: Boolean,
    onSpeakPart: (Int) -> Unit,
    onRemovePart: (Int) -> Unit,
    onTextChange: (String) -> Unit,
    loadImage: suspend (ObfImage) -> ImageBitmap?,
    onSpeak: () -> Unit,
    onStopSpeech: () -> Unit,
    onHold: () -> Unit,
    onDeleteLast: () -> Unit,
    onClear: () -> Unit,
    onActionsChanged: (List<MessageBarAction>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!isEditMode) {
            ScreensMessageBar(
                parts = parts,
                messageText = messageText,
                editable = editable,
                selectOnTouchDown = selectOnTouchDown,
                onSpeakPart = onSpeakPart,
                onRemovePart = onRemovePart,
                onTextChange = onTextChange,
                loadImage = loadImage,
                modifier = Modifier.weight(1f),
            )
            ScreensMessageBarActionSquare(
                actions = actions,
                editing = false,
                hasMessage = hasMessage,
                isSpeaking = isSpeaking,
                isHolding = isHolding,
                selectOnTouchDown = selectOnTouchDown,
                onSpeak = onSpeak,
                onStopSpeech = onStopSpeech,
                onHold = onHold,
                onDeleteLast = onDeleteLast,
                onClear = onClear,
            )
        } else {
            ScreensMessageBarPlaceholder(modifier = Modifier.weight(1f))
            ScreensMessageBarActionSquare(
                actions = actions,
                editing = true,
                hasMessage = hasMessage,
                isSpeaking = isSpeaking,
                isHolding = isHolding,
                selectOnTouchDown = false,
                onSpeak = {},
                onStopSpeech = {},
                onHold = {},
                onDeleteLast = {},
                onClear = {},
                onActionsChanged = onActionsChanged,
            )
        }
    }
}

/** Matching bar placeholder shown in Edit mode beside the customizable button square. */
@Composable
internal fun ScreensMessageBarPlaceholder(modifier: Modifier = Modifier) {
    val large = currentWindowWidth().isLarge
    Surface(
        modifier = modifier.height(screensMessageBarHeight(large)),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
    ) {}
}

/**
 * The Screen's 1–4 buttons beside the message bar. They always fill the same 2×2 square,
 * so fewer buttons are bigger: 1 fills the square, 2 stack, 3 give the first a full-height
 * column, and 4 form a 2×2 grid. In [editing] mode each slot opens a picker to swap or
 * remove it, and a dashed + slot adds another button up to four.
 */
@Composable
internal fun ScreensMessageBarActionSquare(
    actions: List<MessageBarAction>,
    editing: Boolean,
    hasMessage: Boolean,
    isSpeaking: Boolean,
    isHolding: Boolean,
    selectOnTouchDown: Boolean,
    onSpeak: () -> Unit,
    onStopSpeech: () -> Unit,
    onHold: () -> Unit,
    onDeleteLast: () -> Unit,
    onClear: () -> Unit,
    onActionsChanged: (List<MessageBarAction>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val large = currentWindowWidth().isLarge
    val half = screensMessageBarControlSize(large)
    val gap = 8.dp
    val full = half * 2 + gap
    val slots: List<MessageBarAction?> =
        actions + if (editing && actions.size < MAX_MESSAGE_BAR_ACTIONS) listOf(null) else emptyList()

    if (slots.isEmpty()) return

    @Composable
    fun RenderSlot(slotIndex: Int, width: Dp, height: Dp) {
        val action = slots[slotIndex]
        if (action == null) {
            AddMessageBarActionSlot(
                actions = actions,
                width = width,
                height = height,
                onAdd = { choice -> onActionsChanged(addMessageBarAction(actions, choice)) },
            )
        } else if (editing) {
            EditMessageBarActionSlot(
                index = slotIndex,
                action = action,
                actions = actions,
                width = width,
                height = height,
                onReplace = { choice -> onActionsChanged(replaceMessageBarAction(actions, slotIndex, choice)) },
                onRemove = { onActionsChanged(removeMessageBarAction(actions, slotIndex)) },
            )
        } else {
            RunMessageBarActionSlot(
                action = action,
                width = width,
                height = height,
                hasMessage = hasMessage,
                isSpeaking = isSpeaking,
                isHolding = isHolding,
                selectOnTouchDown = selectOnTouchDown,
                onSpeak = onSpeak,
                onStopSpeech = onStopSpeech,
                onHold = onHold,
                onDeleteLast = onDeleteLast,
                onClear = onClear,
            )
        }
    }

    Box(modifier = modifier.testTag("screens-message-action-square")) {
        when (slots.size) {
            1 -> RenderSlot(0, full, full)
            2 -> Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                RenderSlot(0, full, half)
                RenderSlot(1, full, half)
            }
            3 -> Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                RenderSlot(0, half, full)
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    RenderSlot(1, half, half)
                    RenderSlot(2, half, half)
                }
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    RenderSlot(0, half, half)
                    RenderSlot(1, half, half)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    RenderSlot(2, half, half)
                    RenderSlot(3, half, half)
                }
            }
        }
    }
}

@Composable
private fun RunMessageBarActionSlot(
    action: MessageBarAction,
    width: Dp,
    height: Dp,
    hasMessage: Boolean,
    isSpeaking: Boolean,
    isHolding: Boolean,
    selectOnTouchDown: Boolean,
    onSpeak: () -> Unit,
    onStopSpeech: () -> Unit,
    onHold: () -> Unit,
    onDeleteLast: () -> Unit,
    onClear: () -> Unit,
) {
    val prominent = action == MessageBarAction.Speak
    val enabled = when (action) {
        MessageBarAction.Speak -> isSpeaking || hasMessage
        MessageBarAction.Hold -> isHolding || hasMessage
        MessageBarAction.DeleteLast -> hasMessage
        MessageBarAction.Clear -> hasMessage
    }
    val icon = when (action) {
        MessageBarAction.Speak -> if (isSpeaking) Icons.Default.Stop else Icons.Default.PlayArrow
        MessageBarAction.Hold -> if (isHolding) Icons.Default.Bookmark else Icons.Default.BookmarkBorder
        MessageBarAction.DeleteLast -> Icons.AutoMirrored.Filled.Backspace
        MessageBarAction.Clear -> Icons.Default.Delete
    }
    val label = when (action) {
        MessageBarAction.Speak -> stringResource(if (isSpeaking) R.string.playback_stop else R.string.board_workspace_speak_sentence)
        MessageBarAction.Hold -> stringResource(if (isHolding) R.string.screens_message_bar_swap_held else R.string.screens_message_bar_hold)
        MessageBarAction.DeleteLast -> stringResource(R.string.board_workspace_delete_last)
        MessageBarAction.Clear -> stringResource(R.string.board_workspace_clear_sentence)
    }
    val onClick: () -> Unit = when (action) {
        MessageBarAction.Speak -> { { if (isSpeaking) onStopSpeech() else onSpeak() } }
        MessageBarAction.Hold -> onHold
        MessageBarAction.DeleteLast -> onDeleteLast
        MessageBarAction.Clear -> onClear
    }
    val containerColor = if (prominent) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (prominent) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.primary
    }
    val iconSize = minOf(width, height) * 0.42f

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        modifier = Modifier
            .width(width)
            .height(height)
            .alpha(if (enabled) 1f else 0.38f)
            .clip(RoundedCornerShape(12.dp))
            .let { base ->
                if (!enabled) {
                    base.semantics {
                        role = Role.Button
                        contentDescription = label
                    }
                } else if (selectOnTouchDown) {
                    base
                        .semantics {
                            role = Role.Button
                            contentDescription = label
                            onClick { onClick(); true }
                        }
                        .selectOnTouchDown(enabled = true) { onClick() }
                } else {
                    base.clickable(role = Role.Button, onClick = onClick)
                }
            }
            .testTag("message-bar-action-${action.name.lowercase()}"),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
private fun EditMessageBarActionSlot(
    index: Int,
    action: MessageBarAction,
    actions: List<MessageBarAction>,
    width: Dp,
    height: Dp,
    onReplace: (MessageBarAction) -> Unit,
    onRemove: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val prominent = action == MessageBarAction.Speak
    val containerColor = if (prominent) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (prominent) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.primary
    }
    val iconSize = minOf(width, height) * 0.42f
    val label = messageBarActionLabel(action)
    val editHint = stringResource(R.string.screens_message_bar_edit_hint)

    Box {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = containerColor,
            modifier = Modifier
                .width(width)
                .height(height)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = editHint, role = Role.Button) { expanded = true }
                .testTag("edit-message-bar-slot-$index"),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = messageBarActionIcon(action),
                    contentDescription = label,
                    tint = contentColor,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MessageBarAction.entries.forEach { choice ->
                val isCurrent = choice == action
                val alreadyUsedElsewhere = !isCurrent && choice in actions
                DropdownMenuItem(
                    text = { Text(messageBarActionLabel(choice)) },
                    leadingIcon = {
                        Icon(
                            imageVector = if (isCurrent) Icons.Default.Check else messageBarActionIcon(choice),
                            contentDescription = null,
                        )
                    },
                    enabled = !alreadyUsedElsewhere,
                    onClick = {
                        expanded = false
                        onReplace(choice)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.screens_message_bar_remove_button),
                        color = if (actions.size > 1) MaterialTheme.colorScheme.error else Color.Unspecified,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.RemoveCircleOutline,
                        contentDescription = null,
                        tint = if (actions.size > 1) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                enabled = actions.size > 1,
                onClick = {
                    expanded = false
                    onRemove()
                },
            )
        }
    }
}

@Composable
private fun AddMessageBarActionSlot(
    actions: List<MessageBarAction>,
    width: Dp,
    height: Dp,
    onAdd: (MessageBarAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val remaining = MessageBarAction.entries.filterNot { it in actions }
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val iconSize = minOf(width, height) * 0.4f
    val addLabel = stringResource(R.string.screens_message_bar_add_button)

    Box {
        Box(
            modifier = Modifier
                .width(width)
                .height(height)
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) { expanded = true }
                .testTag("add-message-bar-action"),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 1.5.dp.toPx()
                val radius = 12.dp.toPx()
                drawRoundRect(
                    color = borderColor,
                    cornerRadius = CornerRadius(radius, radius),
                    style = Stroke(
                        width = strokeWidth,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f),
                    ),
                )
            }
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = addLabel,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(iconSize),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            remaining.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(messageBarActionLabel(choice)) },
                    leadingIcon = { Icon(messageBarActionIcon(choice), contentDescription = null) },
                    onClick = {
                        expanded = false
                        onAdd(choice)
                    },
                )
            }
        }
    }
}

@Composable
private fun messageBarActionLabel(action: MessageBarAction): String = when (action) {
    MessageBarAction.Speak -> stringResource(R.string.playback_play)
    MessageBarAction.Hold -> stringResource(R.string.screens_message_bar_hold)
    MessageBarAction.DeleteLast -> stringResource(R.string.screens_message_bar_delete_last)
    MessageBarAction.Clear -> stringResource(R.string.screens_message_bar_clear_all)
}

private fun messageBarActionIcon(action: MessageBarAction): ImageVector = when (action) {
    MessageBarAction.Speak -> Icons.Default.PlayArrow
    MessageBarAction.Hold -> Icons.Default.BookmarkBorder
    MessageBarAction.DeleteLast -> Icons.AutoMirrored.Filled.Backspace
    MessageBarAction.Clear -> Icons.Default.Delete
}
