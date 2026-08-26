package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.domain.CategoryItem
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import kotlin.math.ceil

/** Prefix marking buttons projected from phrases; strips cleanly back to the phrase id. */
const val PHRASE_BUTTON_PREFIX = "phrase_"

/**
 * Projects phrases into an in-memory OBF board so the phrase grid renders as a
 * Screen through ObfBoardView. The phrase repository stays the source of truth;
 * nothing here is persisted.
 */
fun buildPhraseBoard(phrases: List<Phrase>, columns: Int): ObfBoard {
    val columnCount = columns.coerceAtLeast(1)
    val buttons = phrases.map { phrase ->
        ObfButton(
            id = PHRASE_BUTTON_PREFIX + phrase.id,
            label = phrase.text.ifBlank { phrase.name ?: "" },
            vocalization = phrase.name?.ifBlank { null } ?: phrase.text
        )
    }
    val rows = ceil(buttons.size / columnCount.toFloat()).toInt().coerceAtLeast(1)
    val order = (0 until rows).map { row ->
        (0 until columnCount).map { col ->
            buttons.getOrNull(row * columnCount + col)?.id
        }
    }
    return ObfBoard(
        format = "open-board-0.1",
        id = "phrase_projection",
        name = "Phrases",
        buttons = buttons,
        grid = ObfGrid(rows = rows, columns = columnCount, order = order)
    )
}

/**
 * A read-only OBF rendering of [phrases]. Activation and long-press are resolved
 * back to the originating [Phrase] via the button-id prefix.
 * When categories are provided, a chip row is shown at the top.
 */
@Composable
fun PhraseBoardProjection(
    phrases: List<Phrase>,
    onPhraseActivated: (Phrase) -> Unit,
    onPhraseLongPress: (Phrase) -> Unit,
    modifier: Modifier = Modifier,
    categories: List<CategoryItem> = emptyList(),
    selectedCategory: CategoryItem? = null,
    onCategorySelected: ((CategoryItem?) -> Unit)? = null,
) {
    val settings by rememberReactiveSettings()
    val board = remember(phrases, settings.gridColumns) {
        buildPhraseBoard(phrases, settings.gridColumns)
    }
    val phrasesById = remember(phrases) { phrases.associateBy { PHRASE_BUTTON_PREFIX + it.id } }

    Column(modifier = modifier) {
        if (onCategorySelected != null) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 0.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { onCategorySelected(null) },
                        label = {
                            Text(
                                stringResource(R.string.category_all),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                                )
                            )
                        }
                    )
                }
                itemsIndexed(categories, key = { _, category -> category.id }) { _, category ->
                    FilterChip(
                        selected = selectedCategory?.id == category.id,
                        onClick = { onCategorySelected(category) },
                        label = {
                            Text(
                                category.name ?: stringResource(R.string.category_all),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.fontSizeScale
                                )
                            )
                        }
                    )
                }
            }
        }

        ObfBoardView(
            board = board,
            extractedImages = emptyMap(),
            showMessageBar = false,
            sentenceText = "",
            showSpeakControl = false,
            showDeleteControl = false,
            showClearControl = false,
            onButtonClick = { button ->
                phrasesById[button.id]?.let(onPhraseActivated)
            },
            onButtonLongClick = { button ->
                phrasesById[button.id]?.let(onPhraseLongPress)
            },
            modifier = Modifier.weight(1f)
        )
    }
}
