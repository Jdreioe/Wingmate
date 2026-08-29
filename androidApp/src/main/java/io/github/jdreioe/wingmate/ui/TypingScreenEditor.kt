package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.domain.obf.ActionStripElementConfig
import io.github.jdreioe.wingmate.domain.obf.BoardSetGraph
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import io.github.jdreioe.wingmate.domain.obf.ObfSound
import io.github.jdreioe.wingmate.domain.obf.PageElement
import io.github.jdreioe.wingmate.domain.obf.PageElementContent
import io.github.jdreioe.wingmate.domain.obf.PhraseCollectionElementConfig
import io.github.jdreioe.wingmate.domain.obf.pageElements
import io.github.jdreioe.wingmate.domain.obf.withConfiguration
import io.github.jdreioe.wingmate.domain.obf.withPageElements
import io.github.jdreioe.wingmate.domain.obf.withWordType
import io.github.jdreioe.wingmate.domain.obf.wordType
import kotlin.time.Clock

/** Specialized Page-element canvas hosted by the ordinary Screen editor. */
@Composable
internal fun TypingScreenEditor(
    graph: BoardSetGraph,
    onGraphChange: (BoardSetGraph) -> Unit,
    onEditVocabulary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val board = graph.rootBoard ?: return
    val elements = board.pageElements().sortedWith(compareBy({ it.row }, { it.column }))
    var selectedId by remember(board.id) { mutableStateOf<String?>(null) }
    var editingButton by remember(board.id) { mutableStateOf<ObfButton?>(null) }
    val newActionLabel = stringResource(R.string.typing_screen_new_action)

    fun updateElements(updated: List<PageElement>) {
        onGraphChange(
            graph.copy(boards = graph.boards.map { current ->
                if (current.id == board.id) current.withPageElements(updated) else current
            })
        )
    }

    fun updateElement(updated: PageElement) {
        val maximumColumns = board.grid?.columns?.coerceAtLeast(1) ?: 12
        val bounded = updated.copy(
            columnSpan = updated.columnSpan.coerceIn(1, maximumColumns),
            column = updated.column.coerceIn(0, (maximumColumns - updated.columnSpan).coerceAtLeast(0)),
            row = updated.row.coerceAtLeast(0),
            rowSpan = updated.rowSpan.coerceAtLeast(1),
        ).let { element ->
            if (element.content is PageElementContent.PhraseCollection) {
                element.withConfiguration(PhraseCollectionElementConfig(columns = element.columnSpan))
            } else element
        }
        updateElements(board.pageElements().map { if (it.id == bounded.id) bounded else it })
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(elements, key = PageElement::id) { element ->
            val selected = selectedId == element.id
            Card(
                modifier = Modifier.fillMaxWidth().clickable { selectedId = element.id },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(element.displayName(), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(
                            R.string.typing_screen_element_position,
                            element.row + 1,
                            element.column + 1,
                            element.rowSpan,
                            element.columnSpan,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!element.isSupported) {
                        Text(
                            stringResource(R.string.typing_screen_unknown_element, element.type),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (selected) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            IconButton(onClick = {
                                val previous = elements.getOrNull(elements.indexOf(element) - 1) ?: return@IconButton
                                updateElements(board.pageElements().map {
                                    when (it.id) {
                                        element.id -> element.copy(row = previous.row)
                                        previous.id -> previous.copy(row = element.row)
                                        else -> it
                                    }
                                })
                            }) { Icon(Icons.Default.ArrowUpward, stringResource(R.string.typing_screen_move_up)) }
                            IconButton(onClick = {
                                updateElement(element.copy(column = (element.column - 1).coerceAtLeast(0)))
                            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.typing_screen_move_left)) }
                            IconButton(onClick = {
                                updateElement(element.copy(column = element.column + 1))
                            }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.typing_screen_move_right)) }
                            IconButton(onClick = {
                                val next = elements.getOrNull(elements.indexOf(element) + 1) ?: return@IconButton
                                updateElements(board.pageElements().map {
                                    when (it.id) {
                                        element.id -> element.copy(row = next.row)
                                        next.id -> next.copy(row = element.row)
                                        else -> it
                                    }
                                })
                            }) { Icon(Icons.Default.ArrowDownward, stringResource(R.string.typing_screen_move_down)) }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            TextButton(onClick = {
                                updateElement(element.copy(rowSpan = (element.rowSpan - 1).coerceAtLeast(1)))
                            }) { Text(stringResource(R.string.typing_screen_shorter)) }
                            TextButton(onClick = {
                                updateElement(element.copy(rowSpan = element.rowSpan + 1))
                            }) { Text(stringResource(R.string.typing_screen_taller)) }
                            TextButton(onClick = {
                                updateElement(element.copy(columnSpan = (element.columnSpan - 1).coerceAtLeast(1)))
                            }) { Text(stringResource(R.string.typing_screen_narrower)) }
                            TextButton(onClick = {
                                updateElement(element.copy(columnSpan = element.columnSpan + 1))
                            }) { Text(stringResource(R.string.typing_screen_wider)) }
                        }
                    }
                    when (val content = element.content) {
                        is PageElementContent.ActionStrip -> {
                            val config = content.configuration
                            val buttons = config.buttonIds.mapNotNull { id ->
                                board.buttons.firstOrNull { it.id == id }
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(buttons, key = ObfButton::id) { button ->
                                    Box(modifier = Modifier.size(width = 112.dp, height = 88.dp)) {
                                        ObfButtonItem(
                                            button = button,
                                            isEditMode = true,
                                            onClick = { editingButton = button },
                                        )
                                    }
                                }
                                item("add") {
                                    TextButton(
                                        modifier = Modifier.height(88.dp),
                                        onClick = {
                                            val newButton = ObfButton(
                                                id = "typing:action:${Clock.System.now().toEpochMilliseconds()}",
                                                label = newActionLabel,
                                                action = "+",
                                            )
                                            val updatedElement = element.withConfiguration(
                                                ActionStripElementConfig(config.buttonIds + newButton.id)
                                            )
                                            onGraphChange(graph.copy(boards = graph.boards.map { current ->
                                                if (current.id == board.id) {
                                                    current.copy(buttons = current.buttons + newButton)
                                                        .withPageElements(board.pageElements().map {
                                                            if (it.id == element.id) updatedElement else it
                                                        })
                                                } else current
                                            }))
                                            editingButton = newButton
                                        },
                                    ) {
                                        Icon(Icons.Default.Add, null)
                                        Text(stringResource(R.string.common_add))
                                    }
                                }
                            }
                        }

                        is PageElementContent.PhraseCollection -> ContentRepositoryLink(
                            hint = stringResource(R.string.typing_screen_phrase_contents_hint),
                            onEditVocabulary = onEditVocabulary,
                        )

                        is PageElementContent.PageNavigation -> ContentRepositoryLink(
                            hint = stringResource(R.string.typing_screen_category_contents_hint),
                            onEditVocabulary = onEditVocabulary,
                        )

                        is PageElementContent.Unsupported -> Unit
                    }
                }
            }
        }
    }

    editingButton?.let { button ->
        val imageUrl = button.imageId?.let { id -> board.images.firstOrNull { it.id == id }?.url }.orEmpty()
        val recordingPath = button.soundId?.let { id -> board.sounds.firstOrNull { it.id == id }?.path }
        EditBoardCellDialog(
            boardName = board.name.orEmpty(),
            row = 0,
            column = 0,
            initialLabel = button.label.orEmpty(),
            initialVocalization = button.vocalization.orEmpty(),
            initialImageUrl = imageUrl,
            initialRecordingPath = recordingPath,
            initialBackgroundColor = button.backgroundColor,
            initialLanguage = button.locale,
            initialMathMode = button.mathMode == true,
            initialHidden = button.hidden == true,
            initialShape = button.shape,
            initialWordType = button.wordType,
            showMathMode = false,
            initialAction = button.action,
            initialActions = button.actions.orEmpty(),
            hasExistingValue = true,
            onDismiss = { editingButton = null },
            onSave = { label, vocalization, newImageUrl, newRecordingPath, backgroundColor, language, _, hidden, _, action, actions, shape, wordType ->
                val imageId = newImageUrl?.takeIf(String::isNotBlank)?.let { button.imageId ?: "${button.id}:image" }
                val soundId = newRecordingPath?.takeIf(String::isNotBlank)?.let { button.soundId ?: "${button.id}:sound" }
                val updated = button.copy(
                    label = label,
                    vocalization = vocalization,
                    imageId = imageId,
                    soundId = soundId,
                    backgroundColor = backgroundColor,
                    locale = language,
                    hidden = hidden,
                    action = action,
                    actions = actions,
                ).withShape(shape).withWordType(wordType)
                onGraphChange(graph.copy(boards = graph.boards.map { current ->
                    if (current.id != board.id) return@map current
                    current.copy(
                        buttons = current.buttons.map { if (it.id == button.id) updated else it },
                        images = current.images.filterNot { it.id == button.imageId || it.id == imageId } +
                            listOfNotNull(imageId?.let { ObfImage(it, url = newImageUrl) }),
                        sounds = current.sounds.filterNot { it.id == button.soundId || it.id == soundId } +
                            listOfNotNull(soundId?.let { ObfSound(it, path = newRecordingPath) }),
                    )
                }))
                editingButton = null
            },
            onClearCell = {
                val updatedElements = board.pageElements().map { element ->
                    val config = (element.content as? PageElementContent.ActionStrip)?.configuration
                        ?: return@map element
                    element.withConfiguration(config.copy(buttonIds = config.buttonIds - button.id))
                }
                onGraphChange(graph.copy(boards = graph.boards.map { current ->
                    if (current.id != board.id) current else current.copy(
                        buttons = current.buttons.filterNot { it.id == button.id },
                        images = current.images.filterNot { it.id == button.imageId },
                        sounds = current.sounds.filterNot { it.id == button.soundId },
                    ).withPageElements(updatedElements)
                }))
                editingButton = null
            },
        )
    }
}

@Composable
private fun PageElement.displayName(): String = when (content) {
    is PageElementContent.PhraseCollection -> stringResource(R.string.typing_screen_phrase_collection)
    is PageElementContent.PageNavigation -> stringResource(R.string.typing_screen_page_navigation)
    is PageElementContent.ActionStrip -> stringResource(R.string.typing_screen_action_strip)
    is PageElementContent.Unsupported -> type
}

@Composable
private fun ContentRepositoryLink(
    hint: String,
    onEditVocabulary: () -> Unit,
) {
    Column {
        Text(hint, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onEditVocabulary) {
            Text(stringResource(R.string.typing_screen_edit_contents))
        }
    }
}
