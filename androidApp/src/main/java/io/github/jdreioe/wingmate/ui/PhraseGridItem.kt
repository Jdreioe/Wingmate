package io.github.jdreioe.wingmate.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import io.github.jdreioe.wingmate.ui.parseHexToColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
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
) {
    val infiniteTransition = rememberInfiniteTransition()
    val angle = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 300, easing = LinearEasing), repeatMode = RepeatMode.Reverse)
    )

    val bgColor = item.backgroundColor?.let { try { parseHexToColor(it) } catch (_: Throwable) { MaterialTheme.colorScheme.surface } } ?: MaterialTheme.colorScheme.surface

    Card(
        modifier = Modifier
            .padding(4.dp)
            .fillMaxWidth()
            .height(phraseHeight)
            .combinedClickable(onClick = { onTap?.invoke() ?: onPlay() }, onLongClick = onLongPress),
        shape = RoundedCornerShape(8.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    // category label
                    categoryName?.let { cname ->
                        Text(
                            text = cname,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .background(MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(4.dp))
                                .padding(4.dp),
                            color = MaterialTheme.colorScheme.onSurface
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
                    Text(text = item.text, style = MaterialTheme.typography.bodyLarge)
                    if (!item.name.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = item.name ?: "", style = MaterialTheme.typography.bodySmall)
                    }
                    if (!item.backgroundColor.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = "#${item.backgroundColor}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

                    if (isEditMode) {
                // Show material-style move up / move down / delete buttons
                Column(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                    IconButton(onClick = { if (index > 0) onMove?.invoke(index, index - 1) }) {
                        Icon(imageVector = Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                    }
                    IconButton(onClick = { if (index < total - 1) onMove?.invoke(index, index + 1) }) {
                        Icon(imageVector = Icons.Filled.ArrowDropDown, contentDescription = "Move down")
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    IconButton(onClick = { onDelete?.invoke() }) {
                        Icon(imageVector = Icons.Filled.Close, contentDescription = "Delete")
                    }
                }
            }

                    // Play button overlay (bottom-right)
            IconButton(
                onClick = onPlay,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(40.dp)
            ) {
                Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = "Play")
            }
        }
    }
}
