package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.domain.obf.BoardSetGraph
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BoardWorkspaceTopBar(
    isFullscreen: Boolean,
    mode: BoardWorkspaceMode,
    activeGraph: BoardSetGraph?,
    activeBoard: ObfBoard?,
    selectedButton: ObfButton?,
    selectedField: Pair<Int, Int>?,
    canUndo: Boolean,
    showHiddenButtons: Boolean,
    isExporting: Boolean,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onLeave: () -> Unit,
    onUndo: () -> Unit,
    onRequestFinishEditing: () -> Unit,
    onOpenAddBoard: () -> Unit,
    onOpenSettings: (BoardSettingsTarget) -> Unit,
    onOpenResizeBoard: () -> Unit,
    onSetHomeBoard: () -> Unit,
    onOpenDeleteBoard: () -> Unit,
    onToggleHiddenButtons: () -> Unit,
    onEnterFullscreen: () -> Unit,
    onSwitchToKeyboard: () -> Unit,
    onOpenExport: () -> Unit,
    onPrint: () -> Unit,
    onStartEditing: () -> Unit,
    onEditSelectedButton: (Pair<Int, Int>, ObfButton) -> Unit,
    onRemoveSelectedButton: (Pair<Int, Int>) -> Unit,
    onDeselectButton: () -> Unit,
) {
    if (!isFullscreen && selectedButton != null && selectedField != null) {
        SelectedButtonTopBar(
            label = selectedButton.label ?: selectedButton.vocalization.orEmpty(),
            onEdit = { onEditSelectedButton(selectedField, selectedButton) },
            onRemove = { onRemoveSelectedButton(selectedField) },
            onDeselect = onDeselectButton,
        )
        return
    }
    if (isFullscreen) return

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val useOverflowMenu = maxWidth < if (mode == BoardWorkspaceMode.Edit) 720.dp else 560.dp
        val canSetHome = activeBoard != null && activeGraph != null && activeBoard.id != activeGraph.boardSet.rootBoardId
        val canDeletePage = activeGraph?.boards?.size?.let { it > 1 } == true

        TopAppBar(
            title = {
                Column {
                    Text(
                        if (mode == BoardWorkspaceMode.Edit) {
                            stringResource(
                                R.string.board_workspace_editing,
                                activeGraph?.boardSet?.name.orEmpty()
                            )
                        } else {
                            // "Screen · Page", so the Communicator sees which Page of which Screen.
                            val page = activeBoard?.name ?: stringResource(R.string.board_workspace_board_fallback)
                            val screen = activeGraph?.boardSet?.name
                            if (screen != null && screen != page && (activeGraph.boards.size) > 1) "$screen · $page" else page
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (mode == BoardWorkspaceMode.Edit) activeBoard?.name?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onLeave) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
            },
            actions = {
                if (useOverflowMenu && mode == BoardWorkspaceMode.Edit) {
                    // Undo is the safety net and Finish the way out, so a phone keeps both
                    // visible; the rest of the editing tools are in the menu.
                    IconButton(onClick = onUndo, enabled = canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.board_workspace_undo))
                    }
                    TextButton(onClick = onRequestFinishEditing) {
                        Text(stringResource(R.string.board_workspace_finish))
                    }
                }
                if (useOverflowMenu) {
                    Box {
                        IconButton(onClick = { onMenuExpandedChange(true) }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.common_more_actions)
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { onMenuExpandedChange(false) }
                        ) {
                            if (mode == BoardWorkspaceMode.Edit) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.board_workspace_add)) },
                                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                    onClick = {
                                        onMenuExpandedChange(false)
                                        onOpenAddBoard()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.board_settings_page_title)) },
                                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                    onClick = {
                                        onMenuExpandedChange(false)
                                        onOpenSettings(BoardSettingsTarget.Page)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.board_settings_screen_title)) },
                                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                    onClick = {
                                        onMenuExpandedChange(false)
                                        onOpenSettings(BoardSettingsTarget.Screen)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.board_workspace_resize)) },
                                    leadingIcon = { Icon(Icons.Default.ImportExport, contentDescription = null) },
                                    enabled = activeBoard?.grid != null,
                                    onClick = {
                                        onMenuExpandedChange(false)
                                        onOpenResizeBoard()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.board_workspace_set_home)) },
                                    leadingIcon = { Icon(Icons.Default.Home, contentDescription = null) },
                                    enabled = canSetHome,
                                    onClick = {
                                        onMenuExpandedChange(false)
                                        onSetHomeBoard()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.board_workspace_delete)) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    enabled = canDeletePage,
                                    onClick = {
                                        onMenuExpandedChange(false)
                                        onOpenDeleteBoard()
                                    }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (showHiddenButtons) R.string.board_workspace_hide_hidden
                                                else R.string.board_workspace_show_hidden
                                            )
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (showHiddenButtons) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        onMenuExpandedChange(false)
                                        onToggleHiddenButtons()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.board_workspace_enter_fullscreen)) },
                                    leadingIcon = { Icon(Icons.Default.Fullscreen, contentDescription = null) },
                                    onClick = {
                                        onMenuExpandedChange(false)
                                        onEnterFullscreen()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.mode_switch_to_keyboard)) },
                                    leadingIcon = { Icon(Icons.Default.Keyboard, contentDescription = null) },
                                    onClick = {
                                        onMenuExpandedChange(false)
                                        onSwitchToKeyboard()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.phrase_screen_import_export_data)) },
                                    leadingIcon = { Icon(Icons.Default.ImportExport, contentDescription = null) },
                                    enabled = !isExporting,
                                    onClick = {
                                        onMenuExpandedChange(false)
                                        onOpenExport()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.board_workspace_print)) },
                                    leadingIcon = { Icon(Icons.Default.Print, contentDescription = null) },
                                    enabled = !isExporting,
                                    onClick = {
                                        onMenuExpandedChange(false)
                                        onPrint()
                                    }
                                )
                                if (activeGraph?.boardSet?.isLocked == false) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.board_workspace_edit)) },
                                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                        onClick = {
                                            onMenuExpandedChange(false)
                                            onStartEditing()
                                        }
                                    )
                                }
                            }
                        }
                    }
                } else if (mode == BoardWorkspaceMode.Edit) {
                    IconButton(onClick = onUndo, enabled = canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.board_workspace_undo))
                    }
                    TextButton(onClick = onRequestFinishEditing) {
                        Text(stringResource(R.string.board_workspace_finish))
                    }
                    IconButton(onClick = { onOpenSettings(BoardSettingsTarget.Page) }) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.board_settings_page_title))
                    }
                    IconButton(onClick = { onOpenSettings(BoardSettingsTarget.Screen) }) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.board_settings_screen_title))
                    }
                    IconButton(onClick = onOpenResizeBoard, enabled = activeBoard?.grid != null) {
                        Icon(Icons.Default.ImportExport, contentDescription = stringResource(R.string.board_workspace_resize))
                    }
                    IconButton(onClick = onSetHomeBoard, enabled = canSetHome) {
                        Icon(Icons.Default.Home, contentDescription = stringResource(R.string.board_workspace_set_home))
                    }
                    IconButton(onClick = onOpenDeleteBoard, enabled = canDeletePage) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.board_workspace_delete),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    Box {
                        IconButton(onClick = { onMenuExpandedChange(true) }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.common_more_actions)
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { onMenuExpandedChange(false) }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.board_workspace_add)) },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                onClick = {
                                    onMenuExpandedChange(false)
                                    onOpenAddBoard()
                                }
                            )
                        }
                    }
                } else {
                    IconButton(onClick = onToggleHiddenButtons) {
                        Icon(
                            if (showHiddenButtons) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = stringResource(
                                if (showHiddenButtons) R.string.board_workspace_hide_hidden
                                else R.string.board_workspace_show_hidden
                            )
                        )
                    }
                    IconButton(onClick = onEnterFullscreen) {
                        Icon(
                            Icons.Default.Fullscreen,
                            contentDescription = stringResource(R.string.board_workspace_enter_fullscreen)
                        )
                    }
                    IconButton(onClick = onSwitchToKeyboard) {
                        Icon(
                            Icons.Default.Keyboard,
                            contentDescription = stringResource(R.string.mode_switch_to_keyboard)
                        )
                    }
                    IconButton(onClick = onOpenExport, enabled = !isExporting) {
                        Icon(
                            Icons.Default.ImportExport,
                            contentDescription = stringResource(R.string.phrase_screen_import_export_data)
                        )
                    }
                    if (activeGraph?.boardSet?.isLocked == false) {
                        IconButton(onClick = onStartEditing) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.board_workspace_edit))
                        }
                    }
                }
            },
            colors = if (mode == BoardWorkspaceMode.Edit) {
                TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.primary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            } else TopAppBarDefaults.topAppBarColors()
        )
    }
}
