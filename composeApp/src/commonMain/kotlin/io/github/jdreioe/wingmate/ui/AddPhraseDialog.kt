package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.PI
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.CategoryItem
import io.github.jdreioe.wingmate.ui.parseHexToColor
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPhraseDialog(
    onDismiss: () -> Unit,
    categories: List<CategoryItem>,
    defaultCategoryId: String? = null,
    initialPhrase: Phrase? = null,
    onSave: (Phrase) -> Unit,
    onDelete: ((String) -> Unit)? = null
) {
    var text by remember { mutableStateOf(initialPhrase?.text ?: "") }
    var altText by remember { mutableStateOf(initialPhrase?.name ?: "") }
    var imageUrl by remember { mutableStateOf(initialPhrase?.imageUrl ?: "") }
    var selectedColor by remember { mutableStateOf(initialPhrase?.backgroundColor?.let { parseHexToColor(it) } ?: Color.Blue) }
    var useColor by remember { mutableStateOf(initialPhrase?.backgroundColor != null) }
    var hue by remember { mutableStateOf(0f) }
    var value by remember { mutableStateOf(1f) }
    var selectedCategory by remember {
        mutableStateOf(
            categories.firstOrNull { it.id == (initialPhrase?.parentId ?: defaultCategoryId) }
                ?: categories.firstOrNull()
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialPhrase == null) "Add New Phrase" else "Edit Phrase") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                val showKeyboard = rememberShowKeyboardOnFocus()
                OutlinedTextField(
                    value = altText,
                    onValueChange = { altText = it },
                    label = { Text("Vocalization (what to speak)") },
                    modifier = Modifier.fillMaxWidth().then(showKeyboard)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("New phrase text") },
                    modifier = Modifier.fillMaxWidth().then(showKeyboard)
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                // Image selection
                var showSymbolSearch by remember { mutableStateOf(false) }
                val filePicker = remember { 
                    try { 
                        org.koin.core.context.GlobalContext.get().get<io.github.jdreioe.wingmate.platform.FilePicker>() 
                    } catch (e: Throwable) { null } 
                }
                val scope = rememberCoroutineScope()
                
                Column {
                    Text("Image", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    if (imageUrl.isNotBlank()) {
                         Row(verticalAlignment = Alignment.CenterVertically) {
                            // Preview could go here, but for now just the URL/Path text
                            Text(
                                text = imageUrl.takeLast(30).let { if (imageUrl.length > 30) "...$it" else it },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                                maxLines = 1
                            )
                            IconButton(onClick = { imageUrl = "" }) {
                                Icon(Icons.Filled.Close, "Clear image")
                            }
                        }
                    }
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                         // File Picker Button
                        if (filePicker != null) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val picked = filePicker.pickFile("Select Image", listOf("png", "jpg", "jpeg", "svg"))
                                        if (picked != null) {
                                            // Ensure file protocol for local files
                                            imageUrl = if (picked.startsWith("http")) picked else "file://$picked"
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Pick File")
                            }
                        }
                        
                        // OpenSymbols Button
                        OutlinedButton(
                            onClick = { showSymbolSearch = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("OpenSymbols")
                        }
                    }
                }
                
                if (showSymbolSearch) {
                    val imageCacher = remember { 
                        try { org.koin.core.context.GlobalContext.get().get<io.github.jdreioe.wingmate.infrastructure.ImageCacher>() } catch (e: Throwable) { null } 
                    }
                    
                    OpenSymbolsSearchDialog(
                        onDismiss = { showSymbolSearch = false },
                        onSelect = { url ->
                            showSymbolSearch = false
                            // Cache the image if cacher is available
                            if (imageCacher != null) {
                                scope.launch {
                                    val cachedPath = imageCacher.getCachedImagePath(url)
                                    imageUrl = cachedPath
                                }
                            } else {
                                imageUrl = url
                            }
                        }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                // Color preview + nested color picker dialog trigger
                var showColorDialog by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Color", modifier = Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(8.dp))
                    // preview
                    Box(modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (useColor) selectedColor else MaterialTheme.colorScheme.surface)
                        .border(width = 1.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), shape = CircleShape))
                    Spacer(modifier = Modifier.width(8.dp))
                    if (useColor) {
                        TextButton(onClick = { showColorDialog = true }) { Text("Pick color") }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = { useColor = false }) { Text("None") }
                    } else {
                        TextButton(onClick = { useColor = true; showColorDialog = true }) { Text("Use color") }
                    }
                }
                if (showColorDialog) {
                    ColorPickerDialog(
                        initialColor = selectedColor,
                        initialUse = useColor,
                        onDismiss = { showColorDialog = false },
                        onPick = { pickedColor ->
                            showColorDialog = false
                            if (pickedColor == null) {
                                useColor = false
                            } else {
                                useColor = true
                                selectedColor = pickedColor
                            }
                        }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                // Category dropdown
                if (categories.isNotEmpty()) {
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded }
                    ) {
                        OutlinedTextField(
                            value = selectedCategory?.name ?: "",
                            onValueChange = {},
                            label = { Text("Belongs to category") },
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            categories.forEach { cat ->
                                DropdownMenuItem(
                                    text = { Text(cat.name ?: "No Name") },
                                    onClick = {
                                        selectedCategory = cat
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Normalize to six-digit RRGGBB hex string (lowercase) or null when disabled
                val hex = if (useColor) String.format("%06X", (selectedColor.toArgb() and 0xFFFFFF)).lowercase() else null
                
                val finalVocalization = altText.trim().ifEmpty { null }
                // If Label (text) is empty, default to Vocalization (altText/finalVocalization) if available
                val finalLabel = text.trim().ifEmpty { finalVocalization ?: "" }
                
                val phrase = Phrase(
                    id = initialPhrase?.id ?: java.util.UUID.randomUUID().toString(),
                    text = finalLabel,
                    name = finalVocalization,
                    backgroundColor = hex,
                    imageUrl = imageUrl.trim().ifEmpty { null },
                    parentId = selectedCategory?.id,
                    createdAt = initialPhrase?.createdAt ?: System.currentTimeMillis()
                )
                onSave(phrase)
                onDismiss()
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                if (initialPhrase != null && onDelete != null) {
                    TextButton(onClick = {
                        onDelete(initialPhrase.id)
                        onDismiss()
                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
fun ColorWheel(hue: Float, value: Float, onHueChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.pointerInput(Unit) {
        detectDragGestures { change, _ ->
            val size = this.size
            val center = Offset(size.width / 2f, size.height / 2f)
            val pos = change.position
            val dx = pos.x - center.x
            val dy = pos.y - center.y
            val angle = atan2(dy, dx)
            val degrees = ((angle * 180f / PI.toFloat()) + 360f) % 360f
            onHueChange(degrees)
        }
    }) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        // draw simple hue ring
        val steps = 36
        for (i in 0 until steps) {
            val startAngle = i * 360f / steps
            val endAngle = (i + 1) * 360f / steps
            drawArc(color = hsvToColor(startAngle, 1f, value), startAngle = startAngle, sweepAngle = 360f / steps, useCenter = true, topLeft = Offset(center.x - radius, center.y - radius), size = Size(radius * 2f, radius * 2f))
        }
        // outline
        drawCircle(color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.08f), radius = radius, center = center, style = Stroke(width = 2f))
    }
}

fun hsvToColor(h: Float, s: Float, v: Float): Color {
    val hue = h
    val c = v * s
    val x = c * (1 - kotlin.math.abs((hue / 60f) % 2 - 1))
    val m = v - c
    val (r1, g1, b1) = when {
        hue < 60f -> Triple(c, x, 0f)
        hue < 120f -> Triple(x, c, 0f)
        hue < 180f -> Triple(0f, c, x)
        hue < 240f -> Triple(0f, x, c)
        hue < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(((r1 + m) * 255).toInt(), ((g1 + m) * 255).toInt(), ((b1 + m) * 255).toInt())
}

@Composable
fun ColorPickerDialog(initialColor: Color = Color.Blue, initialUse: Boolean = true, onDismiss: () -> Unit, onPick: (Color?) -> Unit) {
    var hue by remember { mutableStateOf(0f) }
    var value by remember { mutableStateOf(1f) }
    var tempColor by remember { mutableStateOf(initialColor) }
    // populate initial HSV from color
    LaunchedEffect(initialColor) {
        val (h, v) = colorToHsv(initialColor)
        hue = h
        value = v
        tempColor = hsvToColor(hue, 1f, value)
    }

    AlertDialog(onDismissRequest = onDismiss, title = { Text("Pick color") }, text = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ColorWheel(hue = hue, value = value, onHueChange = { h -> hue = h; tempColor = hsvToColor(h, 1f, value) }, modifier = Modifier.size(200.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bright", style = MaterialTheme.typography.labelSmall)
                Slider(value = value, onValueChange = { v -> value = v; tempColor = hsvToColor(hue, 1f, v) }, valueRange = 0.1f..1f, modifier = Modifier.width(180.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(tempColor))
        }
    }, confirmButton = {
        TextButton(onClick = { onPick(tempColor) }) { Text("OK") }
    }, dismissButton = {
        Row {
            TextButton(onClick = { onPick(null) }) { Text("Clear") }
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    })
}

// returns Pair(hue, value)
fun colorToHsv(color: Color): Pair<Float, Float> {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val hue = when {
        delta == 0f -> 0f
        max == r -> ((g - b) / delta % 6f) * 60f
        max == g -> ((b - r) / delta + 2f) * 60f
        else -> ((r - g) / delta + 4f) * 60f
    }.let { if (it < 0f) it + 360f else it }
    val value = max
    return Pair(hue, value)
}
