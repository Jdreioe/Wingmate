package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.PhraseRecordingService
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.getKoin
import wingmatekmp.composeapp.generated.resources.*

internal data class FieldLanguageOption(val tag: String, val label: String)
internal data class FieldSpanOption(val rows: Int, val columns: Int)
internal enum class BoardSetTemplate { Blank, Calculator }

@Composable
internal fun CreateBoardSetDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, rows: Int, columns: Int, template: BoardSetTemplate) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var rowsText by remember { mutableStateOf("4") }
    var columnsText by remember { mutableStateOf("8") }
    var template by remember { mutableStateOf(BoardSetTemplate.Blank) }
    val calculatorName = stringResource(Res.string.calculator_default_name)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.board_dialog_new_set)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.board_dialog_set_name)) },
                    singleLine = true
                )
                Text(stringResource(Res.string.board_dialog_template), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = template == BoardSetTemplate.Blank,
                        onClick = { template = BoardSetTemplate.Blank },
                        label = { Text(stringResource(Res.string.board_dialog_template_blank)) }
                    )
                    FilterChip(
                        selected = template == BoardSetTemplate.Calculator,
                        onClick = {
                            template = BoardSetTemplate.Calculator
                            if (name.isBlank()) name = calculatorName
                        },
                        label = { Text(stringResource(Res.string.board_dialog_template_calculator)) }
                    )
                }
                if (template == BoardSetTemplate.Blank) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = rowsText,
                        onValueChange = { rowsText = it.filter(Char::isDigit) },
                        label = { Text(stringResource(Res.string.board_dialog_rows)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = columnsText,
                        onValueChange = { columnsText = it.filter(Char::isDigit) },
                        label = { Text(stringResource(Res.string.board_dialog_columns)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, rowsText.toIntOrNull() ?: 4, columnsText.toIntOrNull() ?: 8, template) },
                enabled = name.isNotBlank()
            ) { Text(stringResource(Res.string.board_dialog_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
        }
    )
}

@Composable
internal fun CreateBoardDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, rows: Int, columns: Int) -> Unit
) {
    val defaultBoardName = stringResource(Res.string.board_dialog_default_board_name)
    var name by remember { mutableStateOf(defaultBoardName) }
    var rowsText by remember { mutableStateOf("4") }
    var columnsText by remember { mutableStateOf("8") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.board_dialog_new_board)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.board_workspace_board_name)) },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = rowsText,
                        onValueChange = { rowsText = it.filter(Char::isDigit) },
                        label = { Text(stringResource(Res.string.board_dialog_rows)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = columnsText,
                        onValueChange = { columnsText = it.filter(Char::isDigit) },
                        label = { Text(stringResource(Res.string.board_dialog_columns)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, rowsText.toIntOrNull() ?: 4, columnsText.toIntOrNull() ?: 8) },
                enabled = name.isNotBlank()
            ) { Text(stringResource(Res.string.board_dialog_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
        }
    )
}

@Composable
internal fun EditBoardCellDialog(
    boardName: String,
    row: Int,
    column: Int,
    initialLabel: String,
    initialVocalization: String,
    initialImageUrl: String,
    initialRecordingPath: String? = null,
    initialBackgroundColor: String? = null,
    availableLanguages: List<FieldLanguageOption> = emptyList(),
    initialLanguage: String? = null,
    initialMathMode: Boolean = false,
    availableBoards: List<ObfBoard> = emptyList(),
    initialLinkedBoardId: String? = null,
    availableSpans: List<FieldSpanOption> = listOf(FieldSpanOption(rows = 1, columns = 1)),
    initialRowSpan: Int = 1,
    initialColumnSpan: Int = 1,
    initialAction: String? = null,
    initialActions: List<String> = emptyList(),
    hasExistingValue: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        label: String,
        vocalization: String?,
        imageUrl: String?,
        recordingPath: String?,
        backgroundColor: String?,
        language: String?,
        mathMode: Boolean,
        linkedBoardId: String?,
        rowSpan: Int,
        columnSpan: Int,
        action: String?,
        actions: List<String>
    ) -> Unit,
    onClearCell: () -> Unit
) {
    var label by remember { mutableStateOf(initialLabel) }
    var vocalization by remember { mutableStateOf(initialVocalization) }
    var imageUrl by remember { mutableStateOf(initialImageUrl) }
    var recordingPath by remember { mutableStateOf(initialRecordingPath.orEmpty()) }
    var recordingInProgress by remember { mutableStateOf(false) }
    var recordingError by remember { mutableStateOf<String?>(null) }
    var backgroundColor by remember { mutableStateOf(initialBackgroundColor) }
    var language by remember { mutableStateOf(initialLanguage) }
    var mathMode by remember { mutableStateOf(initialMathMode) }
    var linkedBoardId by remember { mutableStateOf(initialLinkedBoardId) }
    var action by remember { mutableStateOf(initialAction) }
    val actions by remember { mutableStateOf(initialActions) }
    var selectedSpan by remember(initialRowSpan, initialColumnSpan) {
        mutableStateOf(FieldSpanOption(initialRowSpan, initialColumnSpan))
    }
    var opensPage by remember { mutableStateOf(initialLinkedBoardId != null) }
    var showLanguageMenu by remember { mutableStateOf(false) }
    var showBoardMenu by remember { mutableStateOf(false) }
    var showSymbolSearch by remember { mutableStateOf(false) }
    var showImageSourcePicker by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    var showSpanMenu by remember { mutableStateOf(false) }
    val koin = getKoin()
    val recordingService = remember(koin) { koin.getOrNull<PhraseRecordingService>() }
    val scope = rememberCoroutineScope()
    val micState = rememberMicrophonePermissionState()
    var waitingForMicPermission by remember { mutableStateOf(false) }
    val recordingStartFailed = stringResource(Res.string.phrase_recording_start_failed)
    val recordingFinalizeFailed = stringResource(Res.string.phrase_recording_finalize_failed)

    val startRecording: () -> Unit = {
        if (micState.isGranted) {
            scope.launch {
                recordingError = null
                recordingService?.startRecording("field-${row + 1}-${column + 1}")
                    ?.onSuccess { recordingInProgress = true }
                    ?.onFailure { recordingError = it.message ?: recordingStartFailed }
            }
        } else {
            waitingForMicPermission = true
            micState.request()
        }
    }

    LaunchedEffect(micState.isGranted, waitingForMicPermission) {
        if (micState.isGranted && waitingForMicPermission) {
            waitingForMicPermission = false
            startRecording()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.board_dialog_edit_cell, row + 1, column + 1)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    boardName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(Res.string.board_dialog_label)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = vocalization,
                    onValueChange = { vocalization = it },
                    label = { Text(stringResource(Res.string.board_dialog_vocalization)) },
                    singleLine = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(Res.string.speech_math_mode),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            stringResource(Res.string.speech_math_mode_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = mathMode, onCheckedChange = { mathMode = it })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showImageSourcePicker = true }) {
                        Text(stringResource(Res.string.phrase_image_label))
                    }
                    if (imageUrl.isNotBlank()) {
                        OutlinedButton(onClick = { imageUrl = "" }) {
                            Text(stringResource(Res.string.board_dialog_clear_image))
                        }
                    }
                }
                if (recordingService?.isSupported == true || recordingPath.isNotBlank()) {
                    Text(
                        stringResource(Res.string.phrase_recording_label),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!recordingInProgress && recordingService?.isSupported == true) {
                            OutlinedButton(onClick = startRecording) {
                                Text(stringResource(if (recordingPath.isBlank()) Res.string.phrase_record_button else Res.string.phrase_replace_button))
                            }
                        }
                        if (recordingInProgress) {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    recordingService?.stopRecording()
                                        ?.onSuccess { recordingPath = it }
                                        ?.onFailure { recordingError = it.message ?: recordingFinalizeFailed }
                                    recordingInProgress = false
                                }
                            }) { Text(stringResource(Res.string.phrase_stop_button)) }
                        }
                        if (recordingPath.isNotBlank()) {
                            TextButton(onClick = { recordingPath = "" }) {
                                Text(stringResource(Res.string.phrase_clear_button))
                            }
                        }
                    }
                    if (recordingInProgress) {
                        Text(stringResource(Res.string.phrase_recording_in_progress))
                    }
                    recordingError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(Res.string.board_dialog_color),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge
                    )
                    val previewColor = backgroundColor
                        ?.let { runCatching { parseHexToColor(it) }.getOrNull() }
                        ?: MaterialTheme.colorScheme.surfaceVariant
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(previewColor)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                CircleShape
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { showColorPicker = true }) {
                        Text(stringResource(Res.string.board_dialog_pick_color))
                    }
                    if (backgroundColor != null) {
                        TextButton(onClick = { backgroundColor = null }) {
                            Text(stringResource(Res.string.common_clear))
                        }
                    }
                }

                Text(
                    stringResource(Res.string.board_dialog_field_size),
                    style = MaterialTheme.typography.labelLarge
                )
                Box {
                    OutlinedButton(
                        onClick = { showSpanMenu = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(
                                Res.string.board_dialog_field_size_value,
                                selectedSpan.columns,
                                selectedSpan.rows
                            )
                        )
                    }
                    DropdownMenu(
                        expanded = showSpanMenu,
                        onDismissRequest = { showSpanMenu = false }
                    ) {
                        availableSpans.forEach { span ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            Res.string.board_dialog_field_size_value,
                                            span.columns,
                                            span.rows
                                        )
                                    )
                                },
                                onClick = {
                                    selectedSpan = span
                                    showSpanMenu = false
                                }
                            )
                        }
                    }
                }
                Text(
                    stringResource(Res.string.board_dialog_field_size_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    stringResource(Res.string.board_dialog_language),
                    style = MaterialTheme.typography.labelLarge
                )
                Box {
                    OutlinedButton(
                        onClick = { showLanguageMenu = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            availableLanguages.firstOrNull { it.tag == language }?.label
                                ?: stringResource(Res.string.board_dialog_language_default)
                        )
                    }
                    DropdownMenu(
                        expanded = showLanguageMenu,
                        onDismissRequest = { showLanguageMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.board_dialog_language_default)) },
                            onClick = { language = null; showLanguageMenu = false }
                        )
                        availableLanguages.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = { language = option.tag; showLanguageMenu = false }
                            )
                        }
                    }
                }

                Text(
                    stringResource(Res.string.board_dialog_action),
                    style = MaterialTheme.typography.labelLarge
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(
                        selected = !opensPage,
                        onClick = {
                            opensPage = false
                            linkedBoardId = null
                        },
                        label = { Text(stringResource(Res.string.board_dialog_action_speak)) }
                    )
                    androidx.compose.material3.FilterChip(
                        selected = opensPage,
                        onClick = {
                            opensPage = true
                        },
                        enabled = availableBoards.isNotEmpty(),
                        label = { Text(stringResource(Res.string.board_dialog_action_open_page)) }
                    )
                }
                if (opensPage && availableBoards.isNotEmpty()) {
                    Text(
                        stringResource(Res.string.board_dialog_destination_page),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Box {
                        OutlinedButton(
                            onClick = { showBoardMenu = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val targetName = availableBoards.firstOrNull { it.id == linkedBoardId }?.name
                            Text(
                                targetName?.let { stringResource(Res.string.board_cell_opens_board, it) }
                                    ?: stringResource(Res.string.board_dialog_choose_page)
                            )
                        }
                        DropdownMenu(
                            expanded = showBoardMenu,
                            onDismissRequest = { showBoardMenu = false }
                        ) {
                            availableBoards.forEach { board ->
                                DropdownMenuItem(
                                    text = {
                                        Text(board.name ?: stringResource(Res.string.board_workspace_board_fallback))
                                    },
                                    onClick = { linkedBoardId = board.id; showBoardMenu = false }
                                )
                            }
                        }
                    }
                }

                Text(
                    stringResource(Res.string.board_dialog_special_actions),
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    stringResource(Res.string.board_dialog_special_actions_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = action == ":spell",
                        onClick = { action = if (action == ":spell") null else ":spell" },
                        label = { Text(stringResource(Res.string.board_dialog_action_spell)) }
                    )
                    FilterChip(
                        selected = action == ":space",
                        onClick = { action = if (action == ":space") null else ":space" },
                        label = { Text(stringResource(Res.string.board_dialog_action_space)) }
                    )
                    FilterChip(
                        selected = action == ":backspace",
                        onClick = { action = if (action == ":backspace") null else ":backspace" },
                        label = { Text(stringResource(Res.string.board_dialog_action_erase)) }
                    )
                    FilterChip(
                        selected = action == ":clear",
                        onClick = { action = if (action == ":clear") null else ":clear" },
                        label = { Text(stringResource(Res.string.board_dialog_action_clear)) }
                    )
                    FilterChip(
                        selected = action == ":home",
                        onClick = { action = if (action == ":home") null else ":home" },
                        label = { Text(stringResource(Res.string.board_dialog_action_home)) }
                    )
                    FilterChip(
                        selected = action == ":speak",
                        onClick = { action = if (action == ":speak") null else ":speak" },
                        label = { Text(stringResource(Res.string.board_dialog_action_speak_sentence)) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        label.trim(),
                        vocalization.trim().ifBlank { null },
                        imageUrl.trim().ifBlank { null },
                        recordingPath.trim().ifBlank { null },
                        backgroundColor,
                        language,
                        mathMode,
                        linkedBoardId.takeIf { opensPage },
                        selectedSpan.rows,
                        selectedSpan.columns,
                        action?.trim()?.ifBlank { null },
                        actions
                    )
                },
                enabled = label.isNotBlank() && (!opensPage || linkedBoardId != null)
            ) { Text(stringResource(Res.string.common_save)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (hasExistingValue) {
                    TextButton(onClick = onClearCell) {
                        Text(stringResource(Res.string.board_dialog_clear_cell))
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
            }
        }
    )

    if (showSymbolSearch) {
        OpenSymbolsSearchDialog(
            onDismiss = { showSymbolSearch = false },
            onSelect = { selectedUrl ->
                imageUrl = selectedUrl
                showSymbolSearch = false
            }
        )
    }

    if (showImageSourcePicker) {
        ImageSourcePickerDialog(
            onDismiss = { showImageSourcePicker = false },
            onPhoto = { pickedImage ->
                imageUrl = pickedImage
                showImageSourcePicker = false
            },
            onSymbol = {
                showImageSourcePicker = false
                showSymbolSearch = true
            }
        )
    }

    if (showColorPicker) {
        ColorPickerDialog(
            initialColor = backgroundColor
                ?.let { runCatching { parseHexToColor(it) }.getOrNull() }
                ?: Color(0xFF81D4FA),
            initialUse = backgroundColor != null,
            onDismiss = { showColorPicker = false },
            onPick = { color ->
                backgroundColor = color?.let {
                    "#" + (it.toArgb() and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()
                }
                showColorPicker = false
            }
        )
    }
}
