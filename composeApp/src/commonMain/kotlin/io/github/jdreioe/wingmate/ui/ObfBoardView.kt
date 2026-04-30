package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import java.net.URL
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.withTimeoutOrNull

// Simple in-memory cache for downloaded images
private val imageCache = mutableMapOf<String, ByteArray>()

@Composable
fun ObfBoardView(
    board: ObfBoard,
    onButtonClick: (ObfButton) -> Unit,
    modifier: Modifier = Modifier,
    extractedImages: Map<String, ByteArray> = emptyMap(),
    isEditMode: Boolean = false // Added for hidden button visibility
) {
    val settings by rememberReactiveSettings()
    // If grid is defined, use it. Otherwise, just listing buttons (fallback)
    val grid = board.grid
    val buttonsById = remember(board) { board.buttons.associateBy { it.id } }
    val imagesById = remember(board) { board.images.associateBy { it.id } }

    if (grid != null) {
        val columns = grid.columns.coerceAtLeast(1)
        val rows = grid.rows.coerceAtLeast(1)
        
        // Use Column/Row for fixed grid that fills the space
        Column(
            modifier = modifier.padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            grid.order.forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (colIndex in 0 until columns) {
                        val buttonId = row.getOrNull(colIndex)
                        val button = buttonId?.let { buttonsById[it] }
                        
                        // Filter hidden buttons unless in edit mode
                        val isVisible = button != null && (!button.hidden || isEditMode)
                        
                        Box(
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        ) {
                            if (button != null && isVisible) {
                                val image = button.imageId?.let { imagesById[it] }
                                ObfButtonItem(
                                    button = button,
                                    image = image,
                                    extractedImageBytes = button.imageId?.let { 
                                        image?.path?.let { path -> extractedImages[path] }
                                    },
                                    onClick = { onButtonClick(button) },
                                    isEditMode = isEditMode
                                )
                            } else {
                                Spacer(modifier = Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
    } else {
        // Fallback: scrollable grid for boards without explicit grid
        Column(
            modifier = modifier.padding(4.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            board.buttons.chunked(4).forEach { rowButtons ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    rowButtons.forEach { button ->
                        val image = button.imageId?.let { imagesById[it] }
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            ObfButtonItem(
                                button = button,
                                image = image,
                                extractedImageBytes = button.imageId?.let { 
                                    image?.path?.let { path -> extractedImages[path] }
                                },
                                onClick = { onButtonClick(button) }
                            )
                        }
                    }
                    // Fill remaining space if row is not complete
                    repeat(4 - rowButtons.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun ObfButtonItem(
    button: ObfButton,
    image: ObfImage? = null,
    extractedImageBytes: ByteArray? = null,
    onClick: () -> Unit,
    isEditMode: Boolean = false
) {
    val settings by rememberReactiveSettings()
    
    // High Contrast Overrides
    val highContrastContainer = if (MaterialTheme.colorScheme.surface == Color.Black || settings.forceDarkTheme == true) Color.Black else Color.White
    val highContrastContent = if (highContrastContainer == Color.Black) Color.White else Color.Black
    
    val bgColor = if (settings.highContrastMode) {
        highContrastContainer
    } else {
        button.backgroundColor?.let { runCatching { parseHexToColor(it) }.getOrNull() } 
            ?: MaterialTheme.colorScheme.surfaceVariant
    }
    
    val borderColor = if (settings.highContrastMode) {
        highContrastContent
    } else {
        button.borderColor?.let { runCatching { parseHexToColor(it) }.getOrNull() }
    }
    
    val contentColor = if (settings.highContrastMode) highContrastContent else MaterialTheme.colorScheme.onSurface
    
    // State for async-loaded image
    var urlLoadedBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    
    // Try to load image from various sources synchronously first
    val syncBitmap = remember(image, extractedImageBytes) {
                // Option 1: Extracted bytes from OBZ
        extractedImageBytes?.let { bytes ->
            runCatching {
                bytes.toComposeImageBitmap()
            }.getOrNull()
        } 
        // Option 2: Base64 data embedded in image
        ?: image?.data?.let { data ->
            runCatching {
                val base64 = if (data.contains(",")) data.substringAfter(",") else data
                val bytes = java.util.Base64.getDecoder().decode(base64)
                bytes.toComposeImageBitmap()
            }.getOrNull()
        }
    }
    
    // Option 3: Load from URL asynchronously (for opensymbols.org etc)
    val imageUrl = image?.url
    LaunchedEffect(imageUrl) {
        if (syncBitmap == null && imageUrl != null && imageUrl.startsWith("http")) {
            urlLoadedBitmap = withContext(Dispatchers.IO) {
                runCatching {
                    // Check cache first
                    val cachedBytes = imageCache[imageUrl]
                    val bytes = if (cachedBytes != null) {
                        cachedBytes
                    } else {
                        val downloaded = URL(imageUrl).readBytes()
                        imageCache[imageUrl] = downloaded
                        downloaded
                    }
                    bytes.toComposeImageBitmap()
                }.getOrNull()
            }
        }
    }
    
    val imageBitmap = syncBitmap ?: urlLoadedBitmap
    
    Card(
        modifier = Modifier
            .fillMaxSize()
            .padding(if (settings.highContrastMode) 2.dp else 0.dp)
            .alpha(if (button.hidden && isEditMode) 0.5f else 1f)
            .let { baseModifier ->
                val primaryAction = { onClick() }
                
                if (settings.holdToSelectMillis > 0 && !isEditMode) {
                    baseModifier.pointerInput(settings.holdToSelectMillis) {
                        detectTapGestures(
                            onPress = {
                                val completed = withTimeoutOrNull(settings.holdToSelectMillis) {
                                    tryAwaitRelease()
                                    false
                                } ?: true
                                if (completed) {
                                    primaryAction()
                                    tryAwaitRelease()
                                }
                            }
                        )
                    }
                } else {
                    baseModifier.combinedClickable(
                        onClick = { primaryAction() }
                    )
                }
            },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = if (borderColor != null || settings.highContrastMode) {
             androidx.compose.foundation.BorderStroke(if (settings.highContrastMode) 3.dp else 2.dp, borderColor ?: highContrastContent)
        } else null,
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                val showImg = imageBitmap != null && settings.showSymbols
                val showLbl = settings.showLabels && !(button.label.isNullOrBlank() && button.vocalization.isNullOrBlank())

                if (settings.labelAtTop && showImg && showLbl) {
                    // Label at Top
                    val labelText = button.label ?: button.vocalization ?: ""
                    Text(
                        text = labelText,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Image(
                        bitmap = imageBitmap!!,
                        contentDescription = button.label,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(2.dp)
                    )
                } else {
                    // Normal order (Image at Top)
                    if (showImg) {
                        Image(
                            bitmap = imageBitmap!!,
                            contentDescription = button.label,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(2.dp)
                        )
                    }
                    if (showLbl) {
                        val labelText = button.label ?: button.vocalization ?: ""
                        Text(
                            text = labelText,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = contentColor,
                            maxLines = if (imageBitmap != null) 1 else 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
