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
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.withTimeoutOrNull
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
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.getKoin
import wingmatekmp.composeapp.generated.resources.Res
import wingmatekmp.composeapp.generated.resources.common_delete
import wingmatekmp.composeapp.generated.resources.phrase_item_copy_soundfile
import wingmatekmp.composeapp.generated.resources.phrase_item_edit
import wingmatekmp.composeapp.generated.resources.phrase_item_move_down
import wingmatekmp.composeapp.generated.resources.phrase_item_move_up
import wingmatekmp.composeapp.generated.resources.phrase_item_share_soundfile
import wingmatekmp.composeapp.generated.resources.phrase_item_speak_secondary

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
    onCopyAudio: ((filePath: String) -> Unit)? = null,
) {
    val koin = getKoin()
    val shareService = remember(koin) {
        koin.getOrNull<io.github.jdreioe.wingmate.platform.ShareService>()
    }
    val editLabel = stringResource(Res.string.phrase_item_edit)
    val speakSecondaryLabel = stringResource(Res.string.phrase_item_speak_secondary)
    val deleteLabel = stringResource(Res.string.common_delete)
    val copySoundfileLabel = stringResource(Res.string.phrase_item_copy_soundfile)
    val shareSoundfileLabel = stringResource(Res.string.phrase_item_share_soundfile)
    val moveUpLabel = stringResource(Res.string.phrase_item_move_up)
    val moveDownLabel = stringResource(Res.string.phrase_item_move_down)

    val settings by rememberReactiveSettings()
    
    // High Contrast Overrides
    val highContrastContainer = if (MaterialTheme.colorScheme.surface == Color.Black || settings.forceDarkTheme == true) Color.Black else Color.White
    val highContrastContent = if (highContrastContainer == Color.Black) Color.White else Color.Black
    
    val bgColor = if (settings.highContrastMode) {
        highContrastContainer
    } else {
        item.backgroundColor?.let { try { parseHexToColor(it) } catch (_: Throwable) { MaterialTheme.colorScheme.surface } } ?: MaterialTheme.colorScheme.surface
    }
    
    val contentColor = if (settings.highContrastMode) highContrastContent else MaterialTheme.colorScheme.onSurface
    
    var showMenu by remember { mutableStateOf(false) }

        Card(
        modifier = Modifier
            .padding(4.dp)
            .fillMaxWidth()
            .height(phraseHeight)
            .alpha(if (item.isHidden) 0.5f else 1.0f)
            .let { baseModifier ->
                val primaryAction = {
                    showMenu = false
                    try { onTap?.invoke() ?: onPlay() } catch (_: Throwable) {}
                }
                
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
                            },
                            onLongPress = { showMenu = true }
                        )
                    }
                } else {
                    baseModifier.combinedClickable(
                        onClick = { primaryAction() },
                        onLongClick = { showMenu = true }
                    )
                }
            },
        shape = RoundedCornerShape(8.dp),
        border = if (settings.highContrastMode) {
            androidx.compose.foundation.BorderStroke(3.dp, highContrastContent)
        } else null
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    // contextual menu (appears on long-press or right-click)
                    if (showMenu && !isEditMode) {
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier.align(Alignment.TopEnd)
                        ) {
                            if (!readOnly) {
                                DropdownMenuItem(text = { Text(editLabel) }, onClick = {
                                    showMenu = false
                                    try { onLongPress() } catch (_: Throwable) {}
                                })
                            }
                            // secondary language speak option
                            if (onSpeakSecondary != null && !readOnly) {
                                DropdownMenuItem(text = { Text(speakSecondaryLabel) }, onClick = {
                                    showMenu = false
                                    try { onSpeakSecondary.invoke() } catch (_: Throwable) {}
                                })
                            }
                            if (onDelete != null && !readOnly) {
                                DropdownMenuItem(text = { Text(deleteLabel) }, onClick = {
                                    showMenu = false
                                    try { onDelete.invoke() } catch (_: Throwable) {}
                                })
                            }
                            // Copy/share audio file if available
                            val audioPath = item.recordingPath
                            if (!audioPath.isNullOrBlank() && onCopyAudio != null) {
                                DropdownMenuItem(text = { Text(copySoundfileLabel) }, onClick = {
                                    showMenu = false
                                    try { onCopyAudio.invoke(audioPath) } catch (_: Throwable) {}
                                })
                            }
                            if (!audioPath.isNullOrBlank()) {
                                DropdownMenuItem(text = { Text(shareSoundfileLabel) }, onClick = {
                                    showMenu = false
                                    runCatching {
                                        shareService?.shareAudio(audioPath)
                                    }
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
                    // Only allocate and drive the infinite animation while edit mode is active.
                    val rotation = if (isEditMode) {
                        val infiniteTransition = rememberInfiniteTransition()
                        val angle by infiniteTransition.animateFloat(
                            initialValue = -3f,
                            targetValue = 3f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 300, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            )
                        )
                        angle
                    } else {
                        0f
                    }
            Box(modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize()
                .rotate(rotation)) {
                
                // Async load image from URL if available
                var imageBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
                val imageUrl = item.imageUrl
                androidx.compose.runtime.LaunchedEffect(imageUrl) {
                    if (!imageUrl.isNullOrBlank()) {
                        when {
                            imageUrl.startsWith("http") -> {
                                imageBitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    runCatching {
                                        val bytes = java.net.URL(imageUrl).readBytes()
                                        bytes.toComposeImageBitmap()
                                    }.getOrNull()
                                }
                            }
                            imageUrl.startsWith("file://") || imageUrl.startsWith("/") -> {
                                imageBitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    runCatching {
                                        val path = if (imageUrl.startsWith("file://")) java.net.URI(imageUrl).path else imageUrl
                                        val bytes = java.io.File(path).readBytes()
                                        bytes.toComposeImageBitmap()
                                    }.getOrNull()
                                }
                            }
                        }
                    }
                }
                
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(bgColor, shape = RoundedCornerShape(6.dp)),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val showImg = imageBitmap != null && settings.showSymbols
                    val showLbl = settings.showLabels

                    if (settings.labelAtTop && showImg && showLbl) {
                        // Label at Top
                        val baseLarge = MaterialTheme.typography.bodyLarge
                        val effectiveLarge = if (phraseFontSize != TextUnit.Unspecified) baseLarge.copy(fontSize = phraseFontSize) else baseLarge
                        Text(text = item.text, style = effectiveLarge, color = contentColor)
                        
                        androidx.compose.foundation.Image(
                            bitmap = imageBitmap!!,
                            contentDescription = item.text,
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(4.dp)
                        )
                    } else {
                        // Normal order (Image at Top)
                        if (showImg) {
                            androidx.compose.foundation.Image(
                                bitmap = imageBitmap!!,
                                contentDescription = item.text,
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                modifier = Modifier.weight(1f).fillMaxWidth().padding(4.dp)
                            )
                        }
                        if (showLbl) {
                            val baseLarge = MaterialTheme.typography.bodyLarge
                            val effectiveLarge = if (phraseFontSize != TextUnit.Unspecified) baseLarge.copy(fontSize = phraseFontSize) else baseLarge
                            Text(text = item.text, style = effectiveLarge, color = contentColor)
                        }
                    }
                }
            }

            if (isEditMode && !readOnly) {
                // Show material-style move up / move down / delete buttons
                Column(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                    IconButton(onClick = { if (index > 0) onMove?.invoke(index, index - 1) }) {
                        Icon(imageVector = Icons.Filled.ArrowDropUp, contentDescription = moveUpLabel, tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { if (index < total - 1) onMove?.invoke(index, index + 1) }) {
                        Icon(imageVector = Icons.Filled.ArrowDropDown, contentDescription = moveDownLabel, tint = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    IconButton(onClick = { onDelete?.invoke() }) {
                        Icon(imageVector = Icons.Filled.Close, contentDescription = deleteLabel, tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}
