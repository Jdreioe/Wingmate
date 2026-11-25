package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.domain.obf.ObfBoard

/**
 * Slim use-mode top row, Grid 3 style: Menu on the start holds everything outside the board and
 * message bar; Page navigation (Back / Start page) sits opposite when the Screen has several Pages.
 */
@Composable
internal fun ScreensRunTopRow(
    canEdit: Boolean,
    showHiddenButtons: Boolean,
    isExporting: Boolean,
    pageNavigation: PageNavigation?,
    onEditScreen: () -> Unit,
    onToggleHiddenButtons: () -> Unit,
    onExport: () -> Unit,
    onPrint: () -> Unit,
    onBackToLibrary: () -> Unit,
    onSwitchToTyping: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val large = currentWindowWidth().isLarge
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .testTag("screens-run-top-row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            TextButton(
                onClick = { menuExpanded = true },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .testTag("screens-workspace-menu"),
            ) {
                Icon(Icons.Default.Menu, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.board_workspace_menu),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.board_workspace_edit)) },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    enabled = canEdit,
                    onClick = {
                        menuExpanded = false
                        onEditScreen()
                    },
                )
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
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onToggleHiddenButtons()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.board_workspace_export)) },
                    leadingIcon = { Icon(Icons.Default.ImportExport, contentDescription = null) },
                    enabled = !isExporting,
                    onClick = {
                        menuExpanded = false
                        onExport()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.board_workspace_print)) },
                    leadingIcon = { Icon(Icons.Default.Print, contentDescription = null) },
                    enabled = !isExporting,
                    onClick = {
                        menuExpanded = false
                        onPrint()
                    },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.board_workspace_back_to_library)) },
                    leadingIcon = { Icon(Icons.Default.GridView, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onBackToLibrary()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mode_switch_to_keyboard)) },
                    leadingIcon = { Icon(Icons.Default.Keyboard, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onSwitchToTyping()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.phrase_screen_app_settings)) },
                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onOpenSettings()
                    },
                )
            }
        }

        Spacer(Modifier.weight(1f))

        if (pageNavigation != null) {
            val backLabel = stringResource(R.string.board_page_back)
            val startLabel = stringResource(R.string.board_page_start)
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (large) {
                    TextButton(
                        onClick = pageNavigation.onBack,
                        enabled = pageNavigation.canGoBack,
                        modifier = Modifier.heightIn(min = 44.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(backLabel, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    }
                    TextButton(
                        onClick = pageNavigation.onStartPage,
                        enabled = !pageNavigation.atStartPage,
                        modifier = Modifier.heightIn(min = 44.dp),
                    ) {
                        Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(startLabel, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    }
                } else {
                    IconButton(
                        onClick = pageNavigation.onBack,
                        enabled = pageNavigation.canGoBack,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backLabel)
                    }
                    IconButton(
                        onClick = pageNavigation.onStartPage,
                        enabled = !pageNavigation.atStartPage,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(Icons.Default.Home, contentDescription = startLabel)
                    }
                }
            }
        }
    }
}

/**
 * Streamlined single-row edit header: Back, "Editing ‹Screen› ⌄" with Screen actions, Page chips
 * (selected Page chip opens Page actions) + Add page, Undo, and Done. Narrow widths move the
 * Page chips onto a dedicated second row so nothing clips.
 */
@Composable
internal fun ScreensEditHeader(
    screenName: String,
    boards: List<ObfBoard>,
    selectedBoardId: String?,
    rootBoardId: String,
    canResizePage: Boolean,
    canUndo: Boolean,
    onLeave: () -> Unit,
    onRenameScreen: () -> Unit,
    onOpenScreenSettings: () -> Unit,
    onSelectBoard: (String) -> Unit,
    onOpenPageSettings: () -> Unit,
    onResizePage: () -> Unit,
    onSetRootPage: (String) -> Unit,
    onDeletePage: () -> Unit,
    onAddPage: () -> Unit,
    onUndo: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val large = currentWindowWidth().isLarge
    var screenMenuExpanded by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.fillMaxWidth().testTag("screens-edit-header")) {
        val compactHeader = maxWidth < 600.dp

        @Composable
        fun PageStrip(stripModifier: Modifier) {
            EditPageStrip(
                boards = boards,
                selectedBoardId = selectedBoardId,
                rootBoardId = rootBoardId,
                canResizePage = canResizePage,
                onSelectBoard = onSelectBoard,
                onOpenPageSettings = onOpenPageSettings,
                onResizePage = onResizePage,
                onSetRootPage = onSetRootPage,
                onDeletePage = onDeletePage,
                onAddPage = onAddPage,
                modifier = stripModifier,
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (large) {
                    TextButton(
                        onClick = onLeave,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.heightIn(min = 44.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.board_sets_title),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                } else {
                    IconButton(
                        onClick = onLeave,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.board_workspace_back_to_library),
                        )
                    }
                }

                Box(modifier = if (compactHeader) Modifier.weight(1f) else Modifier) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(role = Role.Button) { screenMenuExpanded = true }
                            .heightIn(min = 44.dp)
                            .padding(horizontal = 8.dp)
                            .semantics { heading() }
                            .testTag("screens-edit-title-menu"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.board_workspace_editing, screenName),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (compactHeader) Modifier.weight(1f, fill = false) else Modifier,
                        )
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = screenMenuExpanded,
                        onDismissRequest = { screenMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.board_workspace_rename_screen)) },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = {
                                screenMenuExpanded = false
                                onRenameScreen()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.board_settings_screen_title)) },
                            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            onClick = {
                                screenMenuExpanded = false
                                onOpenScreenSettings()
                            },
                        )
                    }
                }

                if (!compactHeader) {
                    PageStrip(Modifier.weight(1f))
                }

                IconButton(
                    onClick = onUndo,
                    enabled = canUndo,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Undo,
                        contentDescription = stringResource(R.string.board_workspace_undo),
                    )
                }

                TextButton(
                    onClick = onFinish,
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .testTag("screens-edit-done"),
                ) {
                    Text(
                        stringResource(R.string.common_done),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }

            if (compactHeader) {
                PageStrip(Modifier.fillMaxWidth().padding(horizontal = 4.dp))
            }
        }
    }
}

@Composable
private fun EditPageStrip(
    boards: List<ObfBoard>,
    selectedBoardId: String?,
    rootBoardId: String,
    canResizePage: Boolean,
    onSelectBoard: (String) -> Unit,
    onOpenPageSettings: () -> Unit,
    onResizePage: () -> Unit,
    onSetRootPage: (String) -> Unit,
    onDeletePage: () -> Unit,
    onAddPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val fallbackName = stringResource(R.string.board_workspace_board_fallback)
    val outlineColor = MaterialTheme.colorScheme.outlineVariant

    LaunchedEffect(boards, selectedBoardId) {
        val index = boards.indexOfFirst { it.id == selectedBoardId }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    LazyRow(
        modifier = modifier.testTag("screens-edit-page-strip"),
        state = listState,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(boards, key = { it.id }) { board ->
            val isSelected = board.id == selectedBoardId
            val isStartPage = board.id == rootBoardId
            val title = board.name?.takeIf(String::isNotBlank) ?: fallbackName
            var pageMenuExpanded by remember(board.id) { mutableStateOf(false) }

            Box {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    modifier = Modifier
                        .heightIn(min = 38.dp)
                        .clip(RoundedCornerShape(50))
                        .semantics {
                            contentDescription = title
                            selected = isSelected
                        }
                        .clickable(role = Role.Button) {
                            if (isSelected) {
                                pageMenuExpanded = true
                            } else {
                                onSelectBoard(board.id)
                            }
                        }
                        .testTag("edit-page-chip-${board.id}"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        if (isStartPage) {
                            Icon(
                                Icons.Default.Home,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            ),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isSelected) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
                if (isSelected) {
                    DropdownMenu(
                        expanded = pageMenuExpanded,
                        onDismissRequest = { pageMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.board_settings_page_title)) },
                            leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) },
                            onClick = {
                                pageMenuExpanded = false
                                onOpenPageSettings()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.board_workspace_resize)) },
                            leadingIcon = { Icon(Icons.Default.GridOn, contentDescription = null) },
                            enabled = canResizePage,
                            onClick = {
                                pageMenuExpanded = false
                                onResizePage()
                            },
                        )
                        if (!isStartPage) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.board_workspace_set_home)) },
                                leadingIcon = { Icon(Icons.Default.Home, contentDescription = null) },
                                onClick = {
                                    pageMenuExpanded = false
                                    onSetRootPage(board.id)
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.board_workspace_delete),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                enabled = boards.size > 1,
                                onClick = {
                                    pageMenuExpanded = false
                                    onDeletePage()
                                },
                            )
                        }
                    }
                }
            }
        }

        item(key = "add-page") {
            Box(
                modifier = Modifier
                    .heightIn(min = 38.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(role = Role.Button, onClick = onAddPage)
                    .testTag("screens-edit-add-page"),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    val strokeWidth = 1.dp.toPx()
                    val radius = size.height / 2f
                    drawRoundRect(
                        color = outlineColor,
                        cornerRadius = CornerRadius(radius, radius),
                        style = Stroke(
                            width = strokeWidth,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f),
                        ),
                    )
                }
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.board_workspace_add),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
