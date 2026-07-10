package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.getKoin
import io.github.jdreioe.wingmate.domain.PhraseRecordingService
import io.github.jdreioe.wingmate.domain.SpeechService
import wingmatekmp.composeapp.generated.resources.Res
import wingmatekmp.composeapp.generated.resources.color_picker_brightness
import wingmatekmp.composeapp.generated.resources.color_picker_title
import wingmatekmp.composeapp.generated.resources.common_cancel
import wingmatekmp.composeapp.generated.resources.common_clear
import wingmatekmp.composeapp.generated.resources.common_delete
import wingmatekmp.composeapp.generated.resources.common_no_name
import wingmatekmp.composeapp.generated.resources.common_ok
import wingmatekmp.composeapp.generated.resources.common_save
import wingmatekmp.composeapp.generated.resources.phrase_add_title
import wingmatekmp.composeapp.generated.resources.phrase_belongs_to_category
import wingmatekmp.composeapp.generated.resources.phrase_clear_button
import wingmatekmp.composeapp.generated.resources.phrase_clear_image_cd
import wingmatekmp.composeapp.generated.resources.phrase_color_label
import wingmatekmp.composeapp.generated.resources.phrase_edit_title
import wingmatekmp.composeapp.generated.resources.phrase_image_label
import wingmatekmp.composeapp.generated.resources.phrase_new_text_label
import wingmatekmp.composeapp.generated.resources.phrase_none
import wingmatekmp.composeapp.generated.resources.phrase_open_symbols
import wingmatekmp.composeapp.generated.resources.phrase_pick_color
import wingmatekmp.composeapp.generated.resources.phrase_pick_file
import wingmatekmp.composeapp.generated.resources.phrase_play_button
import wingmatekmp.composeapp.generated.resources.phrase_record_button
import wingmatekmp.composeapp.generated.resources.phrase_record_hint
import wingmatekmp.composeapp.generated.resources.phrase_edit_hidden_title
import wingmatekmp.composeapp.generated.resources.phrase_edit_hidden_desc
import wingmatekmp.composeapp.generated.resources.phrase_recording_finalize_failed
import wingmatekmp.composeapp.generated.resources.phrase_recording_in_progress
import wingmatekmp.composeapp.generated.resources.phrase_recording_label
import wingmatekmp.composeapp.generated.resources.phrase_recording_play_failed
import wingmatekmp.composeapp.generated.resources.phrase_recording_start_failed
import wingmatekmp.composeapp.generated.resources.phrase_recording_unavailable
import wingmatekmp.composeapp.generated.resources.phrase_replace_button
import wingmatekmp.composeapp.generated.resources.phrase_select_image_title
import wingmatekmp.composeapp.generated.resources.phrase_stop_button
import wingmatekmp.composeapp.generated.resources.phrase_use_color
import wingmatekmp.composeapp.generated.resources.phrase_vocalization_label

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
    var recordingPath by remember { mutableStateOf(initialPhrase?.recordingPath.orEmpty()) }
    var recordingInProgress by remember { mutableStateOf(false) }
    var recordingError by remember { mutableStateOf<String?>(null) }
    var selectedColor by remember { mutableStateOf(initialPhrase?.backgroundColor?.let { parseHexToColor(it) } ?: Color.Blue) }
    var useColor by remember { mutableStateOf(initialPhrase?.backgroundColor != null) }
    var isHidden by remember { mutableStateOf(initialPhrase?.isHidden ?: false) }
    var hue by remember { mutableStateOf(0f) }
    var value by remember { mutableStateOf(1f) }
    var selectedCategory by remember {
        mutableStateOf(
            categories.firstOrNull { it.id == (initialPhrase?.parentId ?: defaultCategoryId) }
                ?: categories.firstOrNull()
        )
    }
    val koin = getKoin()
    val filePicker = remember(koin) {
        koin.getOrNull<io.github.jdreioe.wingmate.platform.FilePicker>()
    }
    val imageCacher = remember(koin) {
        koin.getOrNull<io.github.jdreioe.wingmate.infrastructure.ImageCacher>()
    }
    val recordingService = remember(koin) {
        koin.getOrNull<PhraseRecordingService>()
    }
    val speechService = remember(koin) {
        koin.getOrNull<SpeechService>()
    }
    val scope = rememberCoroutineScope()
    val recordingHintFallback = stringResource(Res.string.phrase_record_hint)
    val recordingUnavailable = stringResource(Res.string.phrase_recording_unavailable)
    val recordingStartFailed = stringResource(Res.string.phrase_recording_start_failed)
    val recordingFinalizeFailed = stringResource(Res.string.phrase_recording_finalize_failed)
    val recordingPlayFailed = stringResource(Res.string.phrase_recording_play_failed)
    val selectImageTitle = stringResource(Res.string.phrase_select_image_title)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initialPhrase == null) {
                    stringResource(Res.string.phrase_add_title)
                } else {
                    stringResource(Res.string.phrase_edit_title)
                }
            )
        },
        text = {
            val scrollState = rememberScrollState()
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(scrollState)) {
                val showKeyboard = rememberShowKeyboardOnFocus()
                OutlinedTextField(
                    value = altText,
                    onValueChange = { altText = it },
                    label = { Text(stringResource(Res.string.phrase_vocalization_label)) },
                    modifier = Modifier.fillMaxWidth().then(showKeyboard)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(Res.string.phrase_new_text_label)) },
                    modifier = Modifier.fillMaxWidth().then(showKeyboard)
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                // Image selection
                var showSymbolSearch by remember { mutableStateOf(false) }
                
                Column {
                    Text(stringResource(Res.string.phrase_image_label), style = MaterialTheme.typography.labelMedium)
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
                                Icon(Icons.Filled.Close, stringResource(Res.string.phrase_clear_image_cd))
                            }
                        }
                    }
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                         // File Picker Button
                        if (filePicker != null) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val picked = filePicker.pickFile(selectImageTitle, listOf("png", "jpg", "jpeg", "svg"))
                                        if (picked != null) {
                                            // Ensure file protocol for local files
                                            imageUrl = if (picked.startsWith("http")) picked else "file://$picked"
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(Res.string.phrase_pick_file))
                            }
                        }
                        
                        // OpenSymbols Button
                        OutlinedButton(
                            onClick = { showSymbolSearch = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(Res.string.phrase_open_symbols))
                        }
                    }
                }
                
                if (showSymbolSearch) {
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

                val canRecord = recordingService?.isSupported == true
                if (canRecord || recordingPath.isNotBlank()) {
                    Column {
                        Text(stringResource(Res.string.phrase_recording_label), style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(4.dp))

                        if (recordingPath.isNotBlank()) {
                            Text(
                                text = recordingPath.takeLast(40).let { if (recordingPath.length > 40) "...$it" else it },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (canRecord && !recordingInProgress) {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            recordingError = null
                                            val hint = initialPhrase?.id ?: text.ifBlank { altText }.ifBlank { recordingHintFallback }
                                            val result = recordingService?.startRecording(hint)
                                            if (result == null) {
                                                recordingError = recordingUnavailable
                                                return@launch
                                            }
                                            result
                                                .onSuccess { recordingInProgress = true }
                                                .onFailure {
                                                    recordingInProgress = false
                                                    recordingError = it.message ?: recordingStartFailed
                                                }
                                        }
                                    }
                                ) {
                                    Text(
                                        if (recordingPath.isBlank()) {
                                            stringResource(Res.string.phrase_record_button)
                                        } else {
                                            stringResource(Res.string.phrase_replace_button)
                                        }
                                    )
                                }
                            }

                            if (canRecord && recordingInProgress) {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            recordingError = null
                                            val result = recordingService?.stopRecording()
                                            recordingInProgress = false
                                            if (result == null) {
                                                recordingError = recordingUnavailable
                                                return@launch
                                            }
                                            result
                                                .onSuccess { path -> recordingPath = path }
                                                .onFailure {
                                                    recordingError = it.message ?: recordingFinalizeFailed
                                                }
                                        }
                                    }
                                ) {
                                    Text(stringResource(Res.string.phrase_stop_button))
                                }
                            }

                            if (recordingPath.isNotBlank()) {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            recordingError = null
                                            val played = runCatching {
                                                speechService?.speakRecordedAudio(
                                                    audioFilePath = recordingPath,
                                                    textForHistory = altText.trim().ifBlank { text.trim() }
                                                )
                                            }.getOrNull() == true
                                            if (!played) {
                                                recordingError = recordingPlayFailed
                                            }
                                        }
                                    }
                                ) {
                                    Text(stringResource(Res.string.phrase_play_button))
                                }

                                OutlinedButton(onClick = {
                                    recordingPath = ""
                                    recordingError = null
                                }) {
                                    Text(stringResource(Res.string.phrase_clear_button))
                                }
                            }
                        }

                        if (recordingInProgress) {
                            Text(
                                text = stringResource(Res.string.phrase_recording_in_progress),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        recordingError?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Color preview + nested color picker dialog trigger
                var showColorDialog by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.phrase_color_label), modifier = Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(8.dp))
                    // preview
                    Box(modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (useColor) selectedColor else MaterialTheme.colorScheme.surface)
                        .border(width = 1.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), shape = CircleShape))
                    Spacer(modifier = Modifier.width(8.dp))
                    if (useColor) {
                        TextButton(onClick = { showColorDialog = true }) { Text(stringResource(Res.string.phrase_pick_color)) }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = { useColor = false }) { Text(stringResource(Res.string.phrase_none)) }
                    } else {
                        TextButton(onClick = { useColor = true; showColorDialog = true }) { Text(stringResource(Res.string.phrase_use_color)) }
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
                Spacer(modifier = Modifier.height(16.dp))

                // Fitzgerald Key Presets
                Text(stringResource(Res.string.phrase_color_label), style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val presets = listOf(
                        Color(0xFFFFF176) to "People", // Yellow
                        Color(0xFFAED581) to "Action", // Green
                        Color(0xFFFFB74D) to "Things", // Orange
                        Color(0xFF81D4FA) to "Descriptive", // Blue
                        Color(0xFFF48FB1) to "Social", // Pink
                        Color(0xFFE0E0E0) to "Misc" // Grey
                    )
                    presets.forEach { (color, _) ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (useColor && selectedColor == color) 2.dp else 1.dp,
                                    color = if (useColor && selectedColor == color) MaterialTheme.colorScheme.primary else Color.Gray,
                                    shape = CircleShape
                                )
                                .clickable {
                                    selectedColor = color
                                    useColor = true
                                }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))

                // Hidden Toggle
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = isHidden,
                        onCheckedChange = { isHidden = it }
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(stringResource(Res.string.phrase_edit_hidden_title), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(Res.string.phrase_edit_hidden_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

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
                            label = { Text(stringResource(Res.string.phrase_belongs_to_category)) },
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
                                    text = { Text(cat.name ?: stringResource(Res.string.common_no_name)) },
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
                scope.launch {
                    recordingError = null

                    var finalRecordingPath: String? = recordingPath.trim().ifEmpty { null }
                    if (recordingInProgress) {
                        val stopResult = recordingService?.stopRecording()
                        recordingInProgress = false
                        if (stopResult == null) {
                            recordingError = recordingUnavailable
                            return@launch
                        }
                        stopResult
                            .onSuccess { path ->
                                finalRecordingPath = path
                                recordingPath = path
                            }
                            .onFailure {
                                recordingError = it.message ?: recordingFinalizeFailed
                                return@launch
                            }
                    }

                    // Normalize to six-digit RRGGBB hex string (lowercase) or null when disabled
                    val hex = if (useColor) String.format("%06X", (selectedColor.toArgb() and 0xFFFFFF)).lowercase() else null

                    val finalVocalization = altText.trim().ifEmpty { null }
                    // If Label (text) is empty, default to Vocalization (altText/finalVocalization) if available
                    val finalLabel = text.trim().ifEmpty { finalVocalization ?: "" }

                    val phrase = if (initialPhrase != null) {
                        initialPhrase.copy(
                            text = finalLabel,
                            name = finalVocalization,
                            backgroundColor = hex,
                            imageUrl = imageUrl.trim().ifEmpty { null },
                            parentId = selectedCategory?.id,
                            recordingPath = finalRecordingPath,
                            isHidden = isHidden
                        )
                    } else {
                        Phrase(
                            id = java.util.UUID.randomUUID().toString(),
                            text = finalLabel,
                            name = finalVocalization,
                            backgroundColor = hex,
                            imageUrl = imageUrl.trim().ifEmpty { null },
                            parentId = selectedCategory?.id,
                            createdAt = System.currentTimeMillis(),
                            recordingPath = finalRecordingPath,
                            isHidden = isHidden
                        )
                    }
                    onSave(phrase)
                    onDismiss()
                }
            }) {
                Text(stringResource(Res.string.common_save))
            }
        },
        dismissButton = {
            Row {
                if (initialPhrase != null && onDelete != null) {
                    TextButton(onClick = {
                        scope.launch {
                            if (recordingInProgress) {
                                runCatching { recordingService?.stopRecording() }
                                recordingInProgress = false
                            }
                            onDelete(initialPhrase.id)
                            onDismiss()
                        }
                    }) { Text(stringResource(Res.string.common_delete), color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = {
                    scope.launch {
                        if (recordingInProgress) {
                            runCatching { recordingService?.stopRecording() }
                            recordingInProgress = false
                        }
                        onDismiss()
                    }
                }) { Text(stringResource(Res.string.common_cancel)) }
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

    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(Res.string.color_picker_title)) }, text = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ColorWheel(hue = hue, value = value, onHueChange = { h -> hue = h; tempColor = hsvToColor(h, 1f, value) }, modifier = Modifier.size(200.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.color_picker_brightness), style = MaterialTheme.typography.labelSmall)
                Slider(value = value, onValueChange = { v -> value = v; tempColor = hsvToColor(hue, 1f, v) }, valueRange = 0.1f..1f, modifier = Modifier.width(180.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(tempColor))
        }
    }, confirmButton = {
        TextButton(onClick = { onPick(tempColor) }) { Text(stringResource(Res.string.common_ok)) }
    }, dismissButton = {
        Row {
            TextButton(onClick = { onPick(null) }) { Text(stringResource(Res.string.common_clear)) }
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
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
