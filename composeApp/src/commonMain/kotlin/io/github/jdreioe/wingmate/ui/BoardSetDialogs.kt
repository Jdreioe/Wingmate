package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import org.jetbrains.compose.resources.stringResource
import wingmatekmp.composeapp.generated.resources.*

internal data class FieldLanguageOption(val tag: String, val label: String)

@Composable
internal fun CreateBoardSetDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, rows: Int, columns: Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var rowsText by remember { mutableStateOf("4") }
    var columnsText by remember { mutableStateOf("8") }

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
    availableLanguages: List<FieldLanguageOption> = emptyList(),
    initialLanguage: String? = null,
    availableBoards: List<ObfBoard> = emptyList(),
    initialLinkedBoardId: String? = null,
    hasExistingValue: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        label: String,
        vocalization: String?,
        imageUrl: String?,
        language: String?,
        linkedBoardId: String?
    ) -> Unit,
    onClearCell: () -> Unit
) {
    var label by remember { mutableStateOf(initialLabel) }
    var vocalization by remember { mutableStateOf(initialVocalization) }
    var imageUrl by remember { mutableStateOf(initialImageUrl) }
    var language by remember { mutableStateOf(initialLanguage) }
    var linkedBoardId by remember { mutableStateOf(initialLinkedBoardId) }
    var opensPage by remember { mutableStateOf(initialLinkedBoardId != null) }
    var showLanguageMenu by remember { mutableStateOf(false) }
    var showBoardMenu by remember { mutableStateOf(false) }
    var showSymbolSearch by remember { mutableStateOf(false) }

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
                OutlinedTextField(
                    value = imageUrl,
                    onValueChange = { imageUrl = it },
                    label = { Text(stringResource(Res.string.board_dialog_image_url)) },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showSymbolSearch = true }) { Text("OpenSymbols") }
                    if (imageUrl.isNotBlank()) {
                        OutlinedButton(onClick = { imageUrl = "" }) {
                            Text(stringResource(Res.string.board_dialog_clear_image))
                        }
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
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        label.trim(),
                        vocalization.trim().ifBlank { null },
                        imageUrl.trim().ifBlank { null },
                        language,
                        linkedBoardId.takeIf { opensPage }
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
}
