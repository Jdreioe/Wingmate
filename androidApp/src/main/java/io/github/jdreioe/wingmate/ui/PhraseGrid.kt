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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import io.github.jdreioe.wingmate.domain.CategoryItem
import kotlinx.coroutines.delay
import kotlin.time.Clock
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.application.SelectionDebouncer
import io.github.jdreioe.wingmate.application.SelectionHighlight
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.res.stringResource

import com.hojmoseit.wingmate.R
/**
 * PhraseGrid – a Compose port of the Flutter PhraseGrid.
 * Backwards-compatible: existing call sites (phrases, onPlay, onLongPress) still work.
 */

/**
 * Extends the platform long-press timeout by the selection debounce duration so the
 * long-press context menu requires a deliberately longer hold than an ordinary tap.
 * Without this, a debounced (ignored) tap followed by a slow hold is misread as a
 * long-press and opens the menu unexpectedly.
 */
private class DebounceAwareViewConfiguration(
    private val delegate: ViewConfiguration,
    private val extraLongPressMillis: Long
) : ViewConfiguration by delegate {
    override val longPressTimeoutMillis: Long =
        delegate.longPressTimeoutMillis + extraLongPressMillis
}

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
    defaultCategoryId: String? = null,
    onSavePhrase: ((Phrase) -> Unit)? = null,
    phraseHeight: Dp = 120.dp,
    phraseFontSize: TextUnit = TextUnit.Unspecified,
    showAddTile: Boolean = true,
    readOnly: Boolean = false,
    onCopyAudio: ((filePath: String) -> Unit)? = null,
) {
    val settings by rememberReactiveSettings()
    // #118/#120: per-target activation debounce and time-bounded selection highlight.
    val selectionDebouncer = remember { SelectionDebouncer() }
    val selectionHighlight = remember { SelectionHighlight() }
    var highlightedPhraseId by remember { mutableStateOf<String?>(null) }
    var highlightGeneration by remember { mutableLongStateOf(0L) }
    // Filter out hidden phrases unless in wiggle mode
    val visiblePhrases = remember(phrases, isWiggleMode) {
        if (isWiggleMode) phrases else phrases.filter { !it.isHidden }
    }
    // Build item list; when not in wiggle mode show an Add button as last tile
    val showAdd = !isWiggleMode && showAddTile
    val itemCount = if (showAdd) visiblePhrases.size + 1 else visiblePhrases.size

    var showAddDialog by remember { mutableStateOf(false) }

    // #120: expire the selection highlight after the configured duration.
    LaunchedEffect(highlightGeneration) {
        val id = highlightedPhraseId
        val duration = settings.selectionHighlightMillis
        if (id != null && duration > 0) {
            delay(duration)
            val now = Clock.System.now().toEpochMilliseconds()
            if (selectionHighlight.highlightedTarget(now, duration) != id) {
                highlightedPhraseId = null
            }
        }
    }

    // #118: return true when the target may activate, and record the selection highlight.
    fun tryActivate(phraseId: String): Boolean {
        val now = Clock.System.now().toEpochMilliseconds()
        if (!selectionDebouncer.tryActivate(phraseId, now, settings.selectionDebounceMillis)) {
            return false
        }
        selectionHighlight.activate(phraseId, now)
        if (settings.selectionHighlightMillis > 0) {
            highlightedPhraseId = phraseId
            highlightGeneration = selectionHighlight.generation
        }
        return true
    }

    // #118: the long-press context menu requires a hold of debounce + the platform
    // long-press timeout, so a debounced tap followed by a slow hold is not misread as
    // a long-press.
    val baseViewConfiguration = LocalViewConfiguration.current
    val debounceAwareViewConfiguration = remember(baseViewConfiguration, settings.selectionDebounceMillis) {
        DebounceAwareViewConfiguration(baseViewConfiguration, settings.selectionDebounceMillis)
    }
    CompositionLocalProvider(
        LocalViewConfiguration provides debounceAwareViewConfiguration
    ) {
        LazyVerticalGrid(columns = GridCells.Fixed(settings.gridColumns), contentPadding = PaddingValues(4.dp)) {
            items(
                count = itemCount,
                key = { index -> if (showAdd && index == visiblePhrases.size) "add_tile" else visiblePhrases[index].id }
            ) { index ->
                if (showAdd && index == visiblePhrases.size) {
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
                                contentDescription = stringResource(R.string.phrase_add_cd),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                } else {
                    val item = visiblePhrases[index]
                    // When edit mode is active, expose move and delete buttons
                    val categoryName = categories.firstOrNull { it.id == item.parentId }?.name
                    PhraseGridItem(
                        item = item,
                        onPlay = {
                            if (tryActivate(item.id)) {
                                onPlay(item)
                                true
                            } else false
                        },
                        onSpeakSecondary = { onPlaySecondary?.invoke(item) },
                        onLongPress = { onLongPress(item); onToggleWiggleMode?.invoke() },
                        isEditMode = isWiggleMode,
                        onTap = {
                            if (tryActivate(item.id)) {
                                onInsert?.invoke(item)
                                true
                            } else false
                        },
                        onMove = { oldIndex, newIndex -> onMove?.invoke(oldIndex, newIndex) },
                        onDelete = { onDeletePhrase?.invoke(item) },
                        categoryName = categoryName,
                        phraseHeight = phraseHeight,
                        phraseFontSize = phraseFontSize,
                        index = index,
                        total = visiblePhrases.size,
                        readOnly = readOnly,
                        isSelectionHighlighted = highlightedPhraseId == item.id,
                        onCopyAudio = onCopyAudio,
                    )
                }
            }
        }
    }
    if (showAddDialog) {
        AddPhraseDialog(
            onDismiss = { showAddDialog = false },
            categories = categories,
            defaultCategoryId = defaultCategoryId,
            onSave = { phrase ->
                onSavePhrase?.invoke(phrase)
                showAddDialog = false
            }
        )
    }
}
