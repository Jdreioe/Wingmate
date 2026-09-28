package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.application.OBF_PHRASE_ID_EXTENSION
import io.github.jdreioe.wingmate.application.OBF_HISTORY_ID_EXTENSION
import io.github.jdreioe.wingmate.application.TYPING_ALL_PAGE_ID
import io.github.jdreioe.wingmate.application.TYPING_HISTORY_PAGE_ID
import io.github.jdreioe.wingmate.application.TypingScreenPage
import io.github.jdreioe.wingmate.application.TypingScreenProjector
import io.github.jdreioe.wingmate.application.typingCategoryPageId
import io.github.jdreioe.wingmate.domain.CategoryItem
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfButtonActionEffect
import io.github.jdreioe.wingmate.domain.obf.PageElement
import io.github.jdreioe.wingmate.domain.obf.PageElementContent
import io.github.jdreioe.wingmate.domain.obf.pageElements
import io.github.jdreioe.wingmate.domain.obf.parseObfButtonActions
import kotlinx.serialization.json.jsonPrimitive

private const val ADD_PHRASE_BUTTON_ID = "typing:add-phrase"

sealed interface TypingPageSelection {
    data object AllPhrases : TypingPageSelection
    data class Category(val category: CategoryItem) : TypingPageSelection
    data object History : TypingPageSelection
}

private fun ObfButton.projectedPhraseId(): String? =
    extensions[OBF_PHRASE_ID_EXTENSION]?.jsonPrimitive?.content

private fun ObfButton.projectedHistoryId(): String? =
    extensions[OBF_HISTORY_ID_EXTENSION]?.jsonPrimitive?.content

private fun SaidText.typingHistoryId(index: Int): String =
    id?.toString() ?: (date ?: createdAt ?: index.toLong()).toString()

/** Full Typing Screen Page rendered inside the OSK-alternate tray. */
@Composable
fun TypingScreenTray(
    template: ObfBoard,
    phrases: List<Phrase>,
    categories: List<CategoryItem>,
    selection: TypingPageSelection,
    showHistory: Boolean,
    history: List<SaidText>,
    onSelectionChanged: (TypingPageSelection) -> Unit,
    onOpenCategoryMenu: (CategoryItem) -> Unit,
    onAddCategory: () -> Unit,
    onAddPhrase: () -> Unit,
    onPhraseActivated: (Phrase) -> Unit,
    onEditPhrase: (Phrase) -> Unit,
    onDeletePhrase: (Phrase) -> Unit,
    onMovePhrase: (moved: Phrase, target: Phrase) -> Unit,
    onHistoryActivated: (SaidText) -> Unit,
    onAction: (ObfButtonActionEffect) -> Unit,
    isActionEnabled: (ObfButtonActionEffect) -> Boolean,
    vocabularyMutationsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val settings by rememberReactiveSettings()
    val page = remember(selection) {
        when (selection) {
            TypingPageSelection.AllPhrases -> TypingScreenPage(TYPING_ALL_PAGE_ID, "All Phrases")
            is TypingPageSelection.Category -> TypingScreenPage(
                id = typingCategoryPageId(selection.category.id),
                name = selection.category.name ?: "Category",
                categoryId = selection.category.id,
            )
            TypingPageSelection.History -> TypingScreenPage(TYPING_HISTORY_PAGE_ID, "History", isHistory = true)
        }
    }
    val elements = remember(template) {
        template.pageElements().sortedWith(compareBy({ it.row }, { it.column }))
    }
    val phraseColumns = remember(elements, settings.gridColumns) {
        elements.map(PageElement::content)
            .filterIsInstance<PageElementContent.PhraseCollection>()
            .firstOrNull()
            ?.configuration
            ?.columns
            ?.coerceIn(1, 12)
            ?: settings.gridColumns
    }
    val projectedBoard = remember(template, page, phrases, history, phraseColumns) {
        if (selection == TypingPageSelection.History) {
            TypingScreenProjector.projectHistoryPage(template, page, history, phraseColumns)
        } else {
            TypingScreenProjector.projectPhrasePage(template, page, phrases, phraseColumns)
        }
    }
    val addPhraseLabel = stringResource(R.string.phrase_add_title)
    val board = remember(projectedBoard, selection, addPhraseLabel, vocabularyMutationsEnabled) {
        if (selection == TypingPageSelection.History) projectedBoard
        else if (vocabularyMutationsEnabled) projectedBoard.withAppendedGridButton(
            ObfButton(id = ADD_PHRASE_BUTTON_ID, label = addPhraseLabel)
        ) else projectedBoard
    }
    val phrasesById = remember(phrases) { phrases.associateBy(Phrase::id) }
    val historyById = remember(history) {
        history.mapIndexed { index, item -> item.typingHistoryId(index) to item }.toMap()
    }

    TypingPageElementLayout(
        elements = elements.filter { it.isSupported },
        columns = template.grid?.columns ?: phraseColumns,
        modifier = modifier,
    ) { element ->
        when (val content = element.content) {
            is PageElementContent.PageNavigation -> TypingPageNavigation(
                    categories,
                    selection,
                    showHistory,
                    onSelectionChanged,
                    onOpenCategoryMenu,
                    onAddCategory,
                    addCategoryEnabled = vocabularyMutationsEnabled,
                    modifier = Modifier.fillMaxSize(),
                )

            is PageElementContent.PhraseCollection -> ObfBoardView(
                    board = board,
                    extractedImages = emptyMap(),
                    showMessageBar = false,
                    messageText = "",
                    showSpeakControl = false,
                    showDeleteControl = false,
                    showClearControl = false,
                    // Keep Phrases full-size and scroll, rather than shrinking
                    // them to fit a tray the height of the keyboard.
                    scrollingMinimumCellHeight = 72.dp * settings.inputFieldScale.coerceIn(0.75f, 2f),
                    onButtonClick = { button ->
                        if (button.id == ADD_PHRASE_BUTTON_ID) onAddPhrase()
                        else button.projectedPhraseId()?.let(phrasesById::get)?.let(onPhraseActivated)
                            ?: button.projectedHistoryId()?.let(historyById::get)?.let(onHistoryActivated)
                    },
                    // Long-press for Edit / Delete, keep holding and drag to reorder,
                    // like app icons on the home screen. History is read-only.
                    reorder = if (selection != TypingPageSelection.History && vocabularyMutationsEnabled) {
                        BoardReorder(
                            canMove = { button -> button.projectedPhraseId()?.let(phrasesById::get) != null },
                            onMove = { moved, target ->
                                val movedPhrase = moved.projectedPhraseId()?.let(phrasesById::get)
                                val targetPhrase = target.projectedPhraseId()?.let(phrasesById::get)
                                if (movedPhrase != null && targetPhrase != null) onMovePhrase(movedPhrase, targetPhrase)
                            },
                            // Hold-to-select uses long presses to activate; keep those.
                            gesturesEnabled = settings.holdToSelectMillis <= 0,
                            menu = { button, dismiss ->
                                val phrase = button.projectedPhraseId()?.let(phrasesById::get)
                                DropdownMenu(expanded = phrase != null, onDismissRequest = dismiss) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.phrase_item_edit)) },
                                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                        onClick = {
                                            dismiss()
                                            phrase?.let(onEditPhrase)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.common_delete)) },
                                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                        onClick = {
                                            dismiss()
                                            phrase?.let(onDeletePhrase)
                                        },
                                    )
                                }
                            },
                        )
                    } else null,
                    modifier = Modifier.fillMaxSize(),
                )

            is PageElementContent.ActionStrip -> {
                    val ids = content.configuration.buttonIds
                    val buttons = ids.mapNotNull { id -> template.buttons.firstOrNull { it.id == id } }
                    Column(Modifier.fillMaxSize()) {
                        HorizontalDivider()
                        TypingActionStrip(
                            buttons = buttons,
                            onAction = onAction,
                            isActionEnabled = isActionEnabled,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

            is PageElementContent.Unsupported -> Unit
            }
        }
}

@Composable
private fun TypingPageNavigation(
    categories: List<CategoryItem>,
    selection: TypingPageSelection,
    showHistory: Boolean,
    onSelectionChanged: (TypingPageSelection) -> Unit,
    onOpenCategoryMenu: (CategoryItem) -> Unit,
    onAddCategory: () -> Unit,
    addCategoryEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val historyLabel = stringResource(R.string.category_history)
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        item("all") {
            FilterChip(
                selected = selection == TypingPageSelection.AllPhrases,
                onClick = { onSelectionChanged(TypingPageSelection.AllPhrases) },
                label = { Text(stringResource(R.string.category_all)) },
            )
        }
        itemsIndexed(categories, key = { _, category -> category.id }) { _, category ->
            val selected = (selection as? TypingPageSelection.Category)?.category?.id == category.id
            FilterChip(
                selected = selected,
                // Tapping the open Category again opens its menu (move, delete).
                onClick = {
                    if (selected) onOpenCategoryMenu(category)
                    else onSelectionChanged(TypingPageSelection.Category(category))
                },
                label = { Text(category.name ?: stringResource(R.string.category_all)) },
            )
        }
        if (showHistory) {
            item("history") {
                FilterChip(
                    selected = selection == TypingPageSelection.History,
                    onClick = { onSelectionChanged(TypingPageSelection.History) },
                    label = { Text(historyLabel) },
                )
            }
        }
        item("add") {
            FilterChip(
                selected = false,
                enabled = addCategoryEnabled,
                onClick = onAddCategory,
                label = { Text("+") },
            )
        }
    }
}

/**
 * Every Action-strip Button of the Typing Screen in one row, in template order.
 * Used where there is room for a single row (landscape while typing); [leading]
 * goes first, for example the Held message's Swap.
 */
@Composable
fun TypingActionRow(
    template: ObfBoard,
    onAction: (ObfButtonActionEffect) -> Unit,
    isActionEnabled: (ObfButtonActionEffect) -> Boolean,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val buttons = remember(template) {
        template.pageElements()
            .sortedWith(compareBy({ it.row }, { it.column }))
            .map(PageElement::content)
            .filterIsInstance<PageElementContent.ActionStrip>()
            .flatMap { it.configuration.buttonIds }
            .distinct()
            .mapNotNull { id -> template.buttons.firstOrNull { it.id == id } }
    }
    TypingActionStrip(
        buttons = buttons,
        onAction = onAction,
        isActionEnabled = isActionEnabled,
        modifier = modifier.height(56.dp),
        leading = leading,
    )
}

@Composable
private fun TypingActionStrip(
    buttons: List<ObfButton>,
    onAction: (ObfButtonActionEffect) -> Unit,
    isActionEnabled: (ObfButtonActionEffect) -> Boolean,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val unsupportedDescription = stringResource(R.string.typing_screen_unsupported_action)
    val unavailableDescription = stringResource(R.string.typing_screen_unavailable_action)
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        if (leading != null) item("leading") { leading() }
        items(buttons, key = ObfButton::id) { button ->
            val effects = remember(button) { parseObfButtonActions(button) }
            val enabled = effects.isNotEmpty() &&
                effects.none { it is ObfButtonActionEffect.Unsupported } &&
                effects.all(isActionEnabled)
            val disabledDescription = when {
                effects.any { it is ObfButtonActionEffect.Unsupported } -> unsupportedDescription
                !enabled -> unavailableDescription
                else -> null
            }
            TypingActionButton(
                button = button,
                effects = effects,
                enabled = enabled,
                disabledDescription = disabledDescription,
                onClick = { effects.forEach(onAction) },
            )
        }
    }
}

/**
 * One Action-strip Button, styled by what it does (#299 prototype): SSML
 * insertions are compact outlined chips in a monospace face; Message controls
 * are tonal buttons with an icon, and the language toggle shows the secondary
 * language's code. Registers with switch scanning and dwell like board Buttons.
 */
@Composable
private fun TypingActionButton(
    button: ObfButton,
    effects: List<ObfButtonActionEffect>,
    enabled: Boolean,
    disabledDescription: String?,
    onClick: () -> Unit,
) {
    val settings by rememberReactiveSettings()
    val single = effects.singleOrNull()
    val isSsml = effects.isNotEmpty() && effects.all {
        it is ObfButtonActionEffect.AppendText || it is ObfButtonActionEffect.WrapSelection
    }
    val label = when {
        single == ObfButtonActionEffect.ToggleSecondaryLanguage && settings.secondaryLanguage.isNotBlank() ->
            settings.secondaryLanguage.substringBefore('-').uppercase()
        else -> button.label.orEmpty()
    }
    val icon = when (single) {
        ObfButtonActionEffect.SwapHeldMessage -> Icons.Filled.Bookmark
        ObfButtonActionEffect.ToggleSecondaryLanguage -> Icons.Filled.Translate
        ObfButtonActionEffect.Pause -> Icons.Filled.Pause
        ObfButtonActionEffect.Resume, ObfButtonActionEffect.Speak -> Icons.Filled.PlayArrow
        ObfButtonActionEffect.Stop -> Icons.Filled.Stop
        ObfButtonActionEffect.Backspace -> Icons.AutoMirrored.Filled.Backspace
        ObfButtonActionEffect.Clear -> Icons.Filled.Clear
        ObfButtonActionEffect.NativeKeyboard -> Icons.Filled.Keyboard
        else -> null
    }
    val shape = RoundedCornerShape(if (isSsml) 12.dp else 16.dp)
    val modifier = Modifier
        .fillMaxHeight()
        .typingAccessTarget("board:${button.id}", enabled, shape, onClick)
        .semantics {
            if (disabledDescription != null) stateDescription = disabledDescription
        }
    if (isSsml) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            contentPadding = PaddingValues(horizontal = 14.dp),
            modifier = modifier,
        ) {
            Text(label, fontFamily = FontFamily.Monospace, maxLines = 1)
        }
    } else {
        val language = single == ObfButtonActionEffect.ToggleSecondaryLanguage
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            contentPadding = PaddingValues(horizontal = 16.dp),
            colors = if (language) {
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            } else {
                ButtonDefaults.filledTonalButtonColors()
            },
            modifier = modifier,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(label, maxLines = 1)
        }
    }
}

/**
 * Makes a Material button reachable by switch scanning and dwell, the same way
 * board Buttons are: registered as an access target, focusable for scanning,
 * entered/left on hover for dwell, and outlined while the scanner is on it.
 */
@Composable
private fun Modifier.typingAccessTarget(
    targetId: String,
    enabled: Boolean,
    shape: androidx.compose.ui.graphics.Shape,
    action: () -> Unit,
): Modifier {
    val host = LocalAccessInputHost.current
    if (enabled) RegisterAccessTarget(targetId, action)
    val highlighted = enabled && host?.state?.currentTargetId == targetId
    val highlight = MaterialTheme.colorScheme.tertiary
    return this
        .then(
            if (host != null && enabled) {
                Modifier.pointerInput(targetId, host) {
                    awaitPointerEventScope {
                        while (true) {
                            when (awaitPointerEvent().type) {
                                PointerEventType.Enter -> host.enter(targetId)
                                PointerEventType.Exit, PointerEventType.Press -> host.exit(targetId)
                            }
                        }
                    }
                }
            } else Modifier
        )
        .accessTargetFocus(targetId, if (enabled) host else null)
        .then(if (highlighted) Modifier.border(3.dp, highlight, shape) else Modifier)
}

/**
 * Places Page elements on the template's grid. Page navigation and Action strips
 * get at least their minimum height so chips and Buttons stay full-size touch
 * targets; rows holding only the Phrase collection share the remaining height.
 * If the tray is too short for that (landscape), it scrolls instead of shrinking.
 */
@Composable
private fun TypingPageElementLayout(
    elements: List<PageElement>,
    columns: Int,
    modifier: Modifier = Modifier,
    content: @Composable (PageElement) -> Unit,
) {
    val safeColumns = columns.coerceAtLeast(1)
    val rows = elements.maxOfOrNull { it.row + it.rowSpan }?.coerceAtLeast(1) ?: 1
    val minimumRowHeights = remember(elements, rows) {
        val heights = Array(rows) { 0.dp }
        for (element in elements) {
            val span = element.rowSpan.coerceAtLeast(1)
            val perRow = element.content.minimumHeight() / span
            for (row in element.row until (element.row + span).coerceAtMost(rows)) {
                heights[row] = maxOf(heights[row], perRow)
            }
        }
        heights.toList()
    }
    val hasFlexibleRows = minimumRowHeights.any { it == 0.dp }
    val requiredHeight = minimumRowHeights.fold(0.dp) { sum, height -> sum + height } +
        if (hasFlexibleRows) MINIMUM_PHRASE_AREA_HEIGHT else 0.dp
    BoxWithConstraints(modifier) {
        val scrolls = maxHeight < requiredHeight
        Layout(
            modifier = if (scrolls) {
                Modifier.verticalScroll(rememberScrollState()).height(requiredHeight)
            } else {
                Modifier.fillMaxSize()
            },
            content = {
                for (element in elements) {
                    // Keyed so scroll state stays with its element when the layout changes.
                    key(element.id) { content(element) }
                }
            },
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val rowHeights = typingRowHeights(minimumRowHeights.map { it.roundToPx() }, height)
            val rowTops = rowHeights.runningFold(0) { top, rowHeight -> top + rowHeight }
            val placeables = measurables.zip(elements).map { (measurable, element) ->
                val left = width * element.column / safeColumns
                val right = width * (element.column + element.columnSpan).coerceAtMost(safeColumns) / safeColumns
                val top = rowTops[element.row.coerceIn(0, rows)]
                val bottom = rowTops[(element.row + element.rowSpan).coerceIn(0, rows)]
                element to measurable.measure(
                    Constraints.fixed(
                        width = (right - left).coerceAtLeast(0),
                        height = (bottom - top).coerceAtLeast(0),
                    )
                )
            }
            layout(width, height) {
                placeables.forEach { (element, placeable) ->
                    placeable.placeRelative(
                        x = width * element.column / safeColumns,
                        y = rowTops[element.row.coerceIn(0, rows)],
                    )
                }
            }
        }
    }
}

/**
 * Splits [height] across grid rows. Rows with a minimum keep exactly that; rows
 * without one (the Phrase collection) share what is left. With no flexible rows,
 * the spare height is spread over all rows.
 */
internal fun typingRowHeights(minimums: List<Int>, height: Int): List<Int> {
    val remaining = (height - minimums.sum()).coerceAtLeast(0)
    val flexibleRows = minimums.count { it == 0 }
    var flexibleIndex = 0
    return minimums.map { minimum ->
        when {
            flexibleRows == 0 -> minimum + remaining / minimums.size
            minimum > 0 -> minimum
            else -> {
                val share = remaining * (flexibleIndex + 1) / flexibleRows -
                    remaining * flexibleIndex / flexibleRows
                flexibleIndex++
                share
            }
        }
    }
}

/** Height kept for the Phrase collection before the tray starts scrolling. */
private val MINIMUM_PHRASE_AREA_HEIGHT = 72.dp

private fun PageElementContent.minimumHeight(): Dp = when (this) {
    is PageElementContent.PageNavigation -> 48.dp
    is PageElementContent.ActionStrip -> 64.dp
    is PageElementContent.PhraseCollection,
    is PageElementContent.Unsupported -> 0.dp
}

/** Compact presentation retained while the Typing Screen tray is closed. */
@Composable
fun CompactPhraseRow(
    template: ObfBoard,
    phrases: List<Phrase>,
    onPhraseActivated: (Phrase) -> Unit,
    onPhraseLongPress: (Phrase) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by rememberReactiveSettings()
    val board = remember(template, phrases, settings.gridColumns) {
        TypingScreenProjector.projectPhrasePage(
            template,
            TypingScreenPage(TYPING_ALL_PAGE_ID, "All Phrases"),
            phrases,
            settings.gridColumns,
        )
    }
    val phrasesById = remember(phrases) { phrases.associateBy(Phrase::id) }
    val projectedButtons = remember(board) { board.buttons.filter { it.projectedPhraseId() != null } }
    LazyRow(
        modifier = modifier.height(88.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
    ) {
        items(projectedButtons, key = ObfButton::id) { button ->
            Box(modifier = Modifier.size(width = 128.dp, height = 80.dp)) {
                ObfButtonItem(
                    button = button,
                    image = button.imageId?.let { id -> board.images.firstOrNull { it.id == id } },
                    onClick = {
                        button.projectedPhraseId()?.let(phrasesById::get)?.let(onPhraseActivated)
                    },
                    onLongClick = {
                        button.projectedPhraseId()?.let(phrasesById::get)?.let(onPhraseLongPress)
                    },
                )
            }
        }
    }
}

private fun ObfBoard.withAppendedGridButton(button: ObfButton): ObfBoard {
    val currentGrid = grid ?: return copy(buttons = buttons + button)
    val flat = currentGrid.order.flatten() + button.id
    val rows = ((flat.size + currentGrid.columns - 1) / currentGrid.columns).coerceAtLeast(1)
    return copy(
        buttons = buttons + button,
        grid = currentGrid.copy(
            rows = rows,
            order = List(rows) { row ->
                List(currentGrid.columns) { column -> flat.getOrNull(row * currentGrid.columns + column) }
            },
        ),
    )
}
