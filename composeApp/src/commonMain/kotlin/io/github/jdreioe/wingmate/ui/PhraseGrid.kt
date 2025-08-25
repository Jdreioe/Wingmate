package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.jdreioe.wingmate.domain.CategoryItem
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.Phrase

/**
 * PhraseGrid – a Compose port of the Flutter PhraseGrid.
 * Backwards-compatible: existing call sites (phrases, onPlay, onLongPress) still work.
 */
@Composable
fun PhraseGrid(
    phrases: List<Phrase>,
    onPlay: (Phrase) -> Unit,
    onLongPress: (Phrase) -> Unit,
    isWiggleMode: Boolean = false,
    onToggleWiggleMode: (() -> Unit)? = null,
    onAddPhrase: (() -> Unit)? = null,
    onPlaySecondary: ((Phrase) -> Unit)? = null,
    onInsert: ((Phrase) -> Unit)? = null,
    onDeletePhrase: ((Phrase) -> Unit)? = null,
    onMove: ((oldIndex: Int, newIndex: Int) -> Unit)? = null,
    categories: List<CategoryItem> = emptyList(),
    onSavePhrase: ((Phrase) -> Unit)? = null,
    phraseHeight: Dp = 120.dp,
    phraseFontSize: TextUnit = TextUnit.Unspecified,
) {
    // Build item list; when not in wiggle mode show an Add button as last tile
    val showAdd = !isWiggleMode
    val itemCount = if (showAdd) phrases.size + 1 else phrases.size

    var showAddDialog by remember { mutableStateOf(false) }

    LazyVerticalGrid(columns = GridCells.Fixed(3), contentPadding = PaddingValues(4.dp)) {
        items(itemCount) { index ->
            if (showAdd && index == phrases.size) {
                // Add button as card
                Card(
                    modifier = Modifier
                        .padding(4.dp)
                        .fillMaxWidth()
                        .height(100.dp)
                        .clickable { onAddPhrase?.invoke(); showAddDialog = true },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Add phrase",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            } else {
                val item = phrases[index]
                // When edit mode is active, expose move and delete buttons
                val categoryName = categories.firstOrNull { it.id == item.parentId }?.name
                PhraseGridItem(
                    item = item,
                    onPlay = { onPlay(item) },
                    onSpeakSecondary = { onPlaySecondary?.invoke(item) },
                    onLongPress = { onLongPress(item); onToggleWiggleMode?.invoke() },
                    isEditMode = isWiggleMode,
                    onTap = { onInsert?.invoke(item) },
                    onMove = { oldIndex, newIndex -> onMove?.invoke(oldIndex, newIndex) },
                    onDelete = { onDeletePhrase?.invoke(item) },
                    categoryName = categoryName,
                    phraseHeight = phraseHeight,
                    phraseFontSize = phraseFontSize,
                    index = index,
                    total = phrases.size,
                )
            }
        }
    }
    if (showAddDialog) {
        AddPhraseDialog(
            onDismiss = { showAddDialog = false },
            categories = categories,
            onSave = { phrase ->
                onSavePhrase?.invoke(phrase)
                showAddDialog = false
            }
        )
    }
}
