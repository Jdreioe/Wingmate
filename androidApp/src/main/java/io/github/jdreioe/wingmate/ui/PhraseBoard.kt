package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
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
    onAddCategory: () -> Unit,
    onAddPhrase: () -> Unit,
    onPhraseActivated: (Phrase) -> Unit,
    onPhraseLongPress: (Phrase) -> Unit,
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
                    onButtonClick = { button ->
                        if (button.id == ADD_PHRASE_BUTTON_ID) onAddPhrase()
                        else button.projectedPhraseId()?.let(phrasesById::get)?.let(onPhraseActivated)
                            ?: button.projectedHistoryId()?.let(historyById::get)?.let(onHistoryActivated)
                    },
                    onButtonLongClick = { button ->
                        if (vocabularyMutationsEnabled) {
                            button.projectedPhraseId()?.let(phrasesById::get)?.let(onPhraseLongPress)
                        }
                    },
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
            FilterChip(
                selected = (selection as? TypingPageSelection.Category)?.category?.id == category.id,
                onClick = { onSelectionChanged(TypingPageSelection.Category(category)) },
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

@Composable
private fun TypingActionStrip(
    buttons: List<ObfButton>,
    onAction: (ObfButtonActionEffect) -> Unit,
    isActionEnabled: (ObfButtonActionEffect) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val unsupportedDescription = stringResource(R.string.typing_screen_unsupported_action)
    val unavailableDescription = stringResource(R.string.typing_screen_unavailable_action)
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(8.dp),
    ) {
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
            Box(
                modifier = Modifier
                    .size(width = 104.dp, height = 72.dp)
                    .semantics(mergeDescendants = true) {
                        if (disabledDescription != null) {
                            disabled()
                            stateDescription = disabledDescription
                        }
                    },
            ) {
                ObfButtonItem(
                    button = button,
                    enabled = enabled,
                    onClick = { effects.forEach(onAction) },
                )
            }
        }
    }
}

@Composable
private fun TypingPageElementLayout(
    elements: List<PageElement>,
    columns: Int,
    modifier: Modifier = Modifier,
    content: @Composable (PageElement) -> Unit,
) {
    val safeColumns = columns.coerceAtLeast(1)
    val rows = elements.maxOfOrNull { it.row + it.rowSpan }?.coerceAtLeast(1) ?: 1
    Layout(
        modifier = modifier,
        content = {
            for (element in elements) {
                content(element)
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val placeables = measurables.zip(elements).map { (measurable, element) ->
            val left = width * element.column / safeColumns
            val right = width * (element.column + element.columnSpan).coerceAtMost(safeColumns) / safeColumns
            val top = height * element.row / rows
            val bottom = height * (element.row + element.rowSpan).coerceAtMost(rows) / rows
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
                    y = height * element.row / rows,
                )
            }
        }
    }
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
        modifier = modifier.height(112.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
    ) {
        items(projectedButtons, key = ObfButton::id) { button ->
            Box(modifier = Modifier.size(width = 128.dp, height = 104.dp)) {
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
