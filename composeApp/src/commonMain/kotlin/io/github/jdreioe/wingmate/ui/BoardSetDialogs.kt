package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import io.github.jdreioe.wingmate.domain.obf.ObfButtonShape
import io.github.jdreioe.wingmate.domain.obf.ObfKeyboardLayout
import io.github.jdreioe.wingmate.application.KeyboardPreset
import io.github.jdreioe.wingmate.domain.PhraseRecordingService
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.getKoin
import wingmatekmp.composeapp.generated.resources.*

internal data class FieldLanguageOption(val tag: String, val label: String)

private fun ObfButtonShape.labelRes(): StringResource = when (this) {
    ObfButtonShape.Rounded -> Res.string.board_dialog_shape_rounded
    ObfButtonShape.Square -> Res.string.board_dialog_shape_square
    ObfButtonShape.Pill -> Res.string.board_dialog_shape_pill
    ObfButtonShape.Speech -> Res.string.board_dialog_shape_speech
    ObfButtonShape.Thought -> Res.string.board_dialog_shape_thought
}

/** Small, literal previews make the authoring choice understandable without
 * requiring someone to save the field and leave the editor to inspect it. */
@Composable
private fun ButtonShapePreview(shape: ObfButtonShape, selected: Boolean) {
    val resolvedShape = shape.toShape()
    val fill = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val outline = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .size(width = 28.dp, height = 20.dp)
            .background(fill, resolvedShape)
            .border(1.dp, outline, resolvedShape)
    )
}
internal enum class BoardSetTemplate { Blank, Calculator, Keyboard }

@Composable
internal fun CreateBoardSetDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, rows: Int, columns: Int, template: BoardSetTemplate, keyboardPreset: KeyboardPreset) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var rowsText by remember { mutableStateOf("4") }
    var columnsText by remember { mutableStateOf("8") }
    var template by remember { mutableStateOf(BoardSetTemplate.Blank) }
    var keyboardPreset by remember { mutableStateOf(KeyboardPreset.Qwerty) }
    val calculatorName = stringResource(Res.string.calculator_default_name)
    val keyboardName = stringResource(Res.string.keyboard_default_name)

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
                    FilterChip(
                        selected = template == BoardSetTemplate.Keyboard,
                        onClick = {
                            template = BoardSetTemplate.Keyboard
                            if (name.isBlank()) name = keyboardName
                        },
                        label = { Text(stringResource(Res.string.board_dialog_template_keyboard)) }
                    )
                }
                if (template == BoardSetTemplate.Keyboard) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(Res.string.board_dialog_keyboard_preset),
                        modifier = Modifier.align(Alignment.CenterVertically),
                        style = MaterialTheme.typography.labelLarge
                    )
                    FilterChip(
                        selected = keyboardPreset == KeyboardPreset.Qwerty,
                        onClick = { keyboardPreset = KeyboardPreset.Qwerty },
                        label = { Text(stringResource(Res.string.board_dialog_keyboard_preset_qwerty)) }
                    )
                    FilterChip(
                        selected = keyboardPreset == KeyboardPreset.Alphabetical,
                        onClick = { keyboardPreset = KeyboardPreset.Alphabetical },
                        label = { Text(stringResource(Res.string.board_dialog_keyboard_preset_alphabetical)) }
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
                onClick = { onCreate(name, rowsText.toIntOrNull() ?: 4, columnsText.toIntOrNull() ?: 8, template, keyboardPreset) },
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
    initialKeyboardLayout: ObfKeyboardLayout? = null,
    onDismiss: () -> Unit,
    onCreate: (name: String, rows: Int, columns: Int, keyboardLayout: ObfKeyboardLayout?) -> Unit
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
                onClick = {
                    onCreate(
                        name,
                        rowsText.toIntOrNull() ?: 4,
                        columnsText.toIntOrNull() ?: 8,
                        initialKeyboardLayout
                    )
                },
                enabled = name.isNotBlank()
            ) { Text(stringResource(Res.string.board_dialog_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
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
    initialHidden: Boolean = false,
    initialShape: ObfButtonShape = ObfButtonShape.Rounded,
    isKeyboardBoard: Boolean = false,
    showMathMode: Boolean = true,
    availableBoards: List<ObfBoard> = emptyList(),
    initialLinkedBoardId: String? = null,
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
        hidden: Boolean,
        linkedBoardId: String?,
        action: String?,
        actions: List<String>,
        shape: ObfButtonShape
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
    var shape by remember { mutableStateOf(initialShape) }
    var language by remember { mutableStateOf(initialLanguage) }
    var mathMode by remember { mutableStateOf(initialMathMode) }
    var hidden by remember { mutableStateOf(initialHidden) }
    var linkedBoardId by remember { mutableStateOf(initialLinkedBoardId) }
    val initialInsertedText = (listOfNotNull(initialAction) + initialActions)
        .firstOrNull { it.startsWith("+") }
        ?.removePrefix("+")
        .orEmpty()
    var insertedText by remember { mutableStateOf(initialInsertedText) }
    var insertedTextFollowsLabel by remember {
        mutableStateOf(
            initialInsertedText.isNotEmpty() &&
                initialInsertedText == initialVocalization.ifBlank { initialLabel }
        )
    }
    val knownActions = listOf(":spell", ":space", ":backspace", ":clear", ":home", ":speak", ":prediction")
    var actions by remember {
        mutableStateOf(
            (listOfNotNull(initialAction) + initialActions)
                .filter { it.isNotBlank() && !it.startsWith("+") }
                .distinct()
        )
    }

    fun toggleAction(value: String) {
        actions = if (value in actions) actions - value else actions + value
    }

    fun moveAction(index: Int, direction: Int) {
        val target = index + direction
        if (target !in actions.indices) return
        actions = actions.toMutableList().apply {
            val tmp = this[index]
            this[index] = this[target]
            this[target] = tmp
        }
    }
    var opensPage by remember { mutableStateOf(initialLinkedBoardId != null) }
    var showLanguageMenu by remember { mutableStateOf(false) }
    var showBoardMenu by remember { mutableStateOf(false) }
    var showSymbolSearch by remember { mutableStateOf(false) }
    var showImageSourcePicker by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    val koin = getKoin()
    val recordingService = remember(koin) { koin.getOrNull<PhraseRecordingService>() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(label, vocalization, insertedTextFollowsLabel) {
        if (insertedTextFollowsLabel) insertedText = vocalization.ifBlank { label }
    }
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
                if (showMathMode) {
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
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(Res.string.board_dialog_hidden),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            stringResource(Res.string.board_dialog_hidden_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = hidden, onCheckedChange = { hidden = it })
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
                    stringResource(Res.string.board_dialog_shape),
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ObfButtonShape.entries.forEach { entry ->
                        FilterChip(
                            selected = shape == entry,
                            onClick = { shape = entry },
                            label = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ButtonShapePreview(entry, selected = shape == entry)
                                    Text(stringResource(entry.labelRes()))
                                }
                            }
                        )
                    }
                }
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
                if (isKeyboardBoard) {
                    OutlinedTextField(
                        value = insertedText,
                        onValueChange = {
                            insertedText = it
                            insertedTextFollowsLabel = false
                        },
                        label = { Text(stringResource(Res.string.board_dialog_insert_text)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                Text(
                    stringResource(Res.string.board_dialog_special_actions_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    knownActions.forEach { value ->
                        val labelRes = when (value) {
                            ":spell" -> Res.string.board_dialog_action_spell
                            ":space" -> Res.string.board_dialog_action_space
                            ":backspace" -> Res.string.board_dialog_action_erase
                            ":clear" -> Res.string.board_dialog_action_clear
                            ":home" -> Res.string.board_dialog_action_home
                            ":speak" -> Res.string.board_dialog_action_speak_sentence
                            ":prediction" -> Res.string.board_dialog_action_prediction
                            else -> return@forEach
                        }
                        FilterChip(
                            selected = value in actions,
                            onClick = {
                                if (value !in actions) {
                                    insertedText = ""
                                    insertedTextFollowsLabel = false
                                }
                                toggleAction(value)
                            },
                            label = { Text(stringResource(labelRes)) }
                        )
                    }
                }
                if (actions.size > 1) {
                    Text(
                        stringResource(Res.string.board_dialog_action_order),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    actions.forEachIndexed { index, value ->
                        val labelRes = when (value) {
                            ":spell" -> Res.string.board_dialog_action_spell
                            ":space" -> Res.string.board_dialog_action_space
                            ":backspace" -> Res.string.board_dialog_action_erase
                            ":clear" -> Res.string.board_dialog_action_clear
                            ":home" -> Res.string.board_dialog_action_home
                            ":speak" -> Res.string.board_dialog_action_speak_sentence
                            ":prediction" -> Res.string.board_dialog_action_prediction
                            else -> null
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stringResource(Res.string.board_dialog_action_order_position, index + 1),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    labelRes?.let { stringResource(it) } ?: value,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                IconButton(
                                    onClick = { moveAction(index, -1) },
                                    enabled = index > 0
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowUp,
                                        contentDescription = stringResource(Res.string.board_dialog_action_order_up)
                                    )
                                }
                                IconButton(
                                    onClick = { moveAction(index, 1) },
                                    enabled = index < actions.lastIndex
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = stringResource(Res.string.board_dialog_action_order_down)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
TextButton(
                        onClick = {
                            val normalized = actions
                                .plus(insertedText.takeIf { isKeyboardBoard && it.isNotEmpty() }?.let { "+$it" })
                                .filterNotNull()
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                                .distinct()
                            onSave(
                                label.trim(),
                                vocalization.trim().ifBlank { null },
                                imageUrl.trim().ifBlank { null },
                                recordingPath.trim().ifBlank { null },
                                backgroundColor,
                                language,
                                mathMode,
                                hidden,
                                linkedBoardId.takeIf { opensPage },
                                normalized.singleOrNull(),
                                if (normalized.size > 1) normalized else emptyList(),
                                shape
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
