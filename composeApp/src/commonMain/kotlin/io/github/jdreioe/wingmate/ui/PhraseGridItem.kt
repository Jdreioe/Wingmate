package io.github.jdreioe.wingmate.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import io.github.jdreioe.wingmate.ui.parseHexToColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.material3.SmallFloatingActionButton
import io.github.jdreioe.wingmate.domain.Phrase

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PhraseGridItem(
    item: Phrase,
    onPlay: () -> Unit,
    onLongPress: () -> Unit,
    onSpeakSecondary: (() -> Unit)? = null,
    onTap: (() -> Unit)? = null,
    isEditMode: Boolean = false,
    onDelete: (() -> Unit)? = null,
    onMove: ((oldIndex: Int, newIndex: Int) -> Unit)? = null,
    categoryName: String? = null,
    phraseHeight: Dp = 120.dp,
    phraseFontSize: TextUnit = TextUnit.Unspecified,
    index: Int = 0,
    total: Int = 0,
    readOnly: Boolean = false,
) {
    val infiniteTransition = rememberInfiniteTransition()
    val angle = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 300, easing = LinearEasing), repeatMode = RepeatMode.Reverse)
    )

    val bgColor = item.backgroundColor?.let { try { parseHexToColor(it) } catch (_: Throwable) { MaterialTheme.colorScheme.surface } } ?: MaterialTheme.colorScheme.surface

    var showMenu by remember { mutableStateOf(false) }

        Card(
        modifier = Modifier
            .padding(4.dp)
            .fillMaxWidth()
            .height(phraseHeight)
            // long-press opens contextual menu; tap inserts if onTap provided
            .combinedClickable(
                onClick = { showMenu = false; try { onTap?.invoke() ?: onPlay() } catch (_: Throwable) {} },
                onLongClick = { if (!readOnly) showMenu = true }
            ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    // contextual menu (appears on long-press or right-click)
                    if (showMenu && !isEditMode && !readOnly) {
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier.align(Alignment.TopEnd)
                        ) {
                            DropdownMenuItem(text = { Text("Edit") }, onClick = {
                                showMenu = false
                                try { onLongPress() } catch (_: Throwable) {}
                            })
                            // secondary language speak option
                            if (onSpeakSecondary != null) {
                                DropdownMenuItem(text = { Text("Speak (secondary)") }, onClick = {
                                    showMenu = false
                                    try { onSpeakSecondary.invoke() } catch (_: Throwable) {}
                                })
                            }
                            if (onDelete != null) {
                                DropdownMenuItem(text = { Text("Delete") }, onClick = {
                                    showMenu = false
                                    try { onDelete.invoke() } catch (_: Throwable) {}
                                })
                            }
                        }
                    }
                    // category label
                    categoryName?.let { cname ->
                        Text(
                            text = cname,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), shape = RoundedCornerShape(4.dp))
                                .padding(4.dp)
                        )
                    }
                    // wiggle rotation applied to content
                    val rotation = if (isEditMode) (angle.value - 0.5f) * 6f else 0f
            Box(modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize()
                .rotate(rotation)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(bgColor, shape = RoundedCornerShape(6.dp)),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = item.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    if (!item.name.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = item.name ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                    if (!item.backgroundColor.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = "#${item.backgroundColor}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            if (isEditMode && !readOnly) {
                // Show material-style move up / move down / delete buttons
                Column(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                    IconButton(onClick = { if (index > 0) onMove?.invoke(index, index - 1) }) {
                        Icon(imageVector = Icons.Filled.ArrowDropUp, contentDescription = "Move up", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { if (index < total - 1) onMove?.invoke(index, index + 1) }) {
                        Icon(imageVector = Icons.Filled.ArrowDropDown, contentDescription = "Move down", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    IconButton(onClick = { onDelete?.invoke() }) {
                        Icon(imageVector = Icons.Filled.Close, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}
