package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import org.jetbrains.skia.Image as SkiaImage

@Composable
fun ObfBoardView(
    board: ObfBoard,
    onButtonClick: (ObfButton) -> Unit,
    modifier: Modifier = Modifier
) {
    // If grid is defined, use it. Otherwise, just listing buttons (fallback)
    val grid = board.grid
    val buttonsById = remember(board) { board.buttons.associateBy { it.id } }
    val imagesById = remember(board) { board.images.associateBy { it.id } }

    if (grid != null) {
        val columns = grid.columns.coerceAtLeast(1)
        val rows = grid.rows.coerceAtLeast(1)
        
        val flattenedItems = remember(grid) {
            val list = mutableListOf<String?>()
            grid.order.forEach { row ->
                row.forEach { id ->
                    list.add(id)
                }
                val pad = columns - row.size
                repeat(pad) { list.add(null) }
            }
            list
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(4.dp),
            modifier = modifier,
            userScrollEnabled = true 
        ) {
            items(flattenedItems.size) { index ->
                val buttonId = flattenedItems[index]
                val button = buttonsById[buttonId]

                Box(
                    modifier = Modifier
                        .aspectRatio(1f) // Square tiles
                        .padding(4.dp)
                ) {
                    if (button != null) {
                        val image = button.imageId?.let { imagesById[it] }
                        ObfButtonItem(
                            button = button,
                            image = image,
                            onClick = { onButtonClick(button) }
                        )
                    } else {
                        Spacer(modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 100.dp),
            contentPadding = PaddingValues(4.dp),
            modifier = modifier
        ) {
            items(board.buttons.size) { index ->
                val button = board.buttons[index]
                val image = button.imageId?.let { imagesById[it] }
                Box(modifier = Modifier.height(100.dp).padding(4.dp)) {
                    ObfButtonItem(
                        button = button,
                        image = image,
                        onClick = { onButtonClick(button) }
                    )
                }
            }
        }
    }
}

@Composable
fun ObfButtonItem(
    button: ObfButton,
    image: ObfImage? = null,
    onClick: () -> Unit
) {
    val bgColor = button.backgroundColor?.let { runCatching { parseHexToColor(it) }.getOrNull() } 
        ?: MaterialTheme.colorScheme.surfaceVariant
    
    val borderColor = button.borderColor?.let { runCatching { parseHexToColor(it) }.getOrNull() }
    
    // Try to load image from base64 data
    val imageBitmap = remember(image) {
        image?.data?.let { data ->
            runCatching {
                // Format: data:image/png;base64,xxxx
                val base64 = if (data.contains(",")) data.substringAfter(",") else data
                val bytes = java.util.Base64.getDecoder().decode(base64)
                SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
            }.getOrNull()
        }
    }
    
    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = if (borderColor != null) androidx.compose.foundation.BorderStroke(2.dp, borderColor) else null,
        onClick = onClick
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                if (imageBitmap != null) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = button.label,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(4.dp)
                    )
                }
                val labelText = button.label ?: button.vocalization
                if (!labelText.isNullOrBlank()) {
                    Text(
                        text = labelText,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2
                    )
                }
            }
        }
    }
}
