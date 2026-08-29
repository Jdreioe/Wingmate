package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.obf.BoardActivationBehavior
import io.github.jdreioe.wingmate.domain.obf.BoardReturnBehavior
import io.github.jdreioe.wingmate.domain.obf.BoardSettingsOverrides
import io.github.jdreioe.wingmate.domain.obf.ResolvedBoardSettings
import io.github.jdreioe.wingmate.domain.obf.resolveBoardSettings
import androidx.compose.ui.res.stringResource

import com.hojmoseit.wingmate.R
internal enum class BoardSettingsTarget { Screen, Page }

private enum class BoardSettingPreference {
    ShowLabels,
    ShowSymbols,
    LabelPosition,
    MessageBar,
    SpeakButton,
    MessageBarEditable,
    Activation,
    Return
}

private data class BoardSettingChoice(
    val label: String,
    val selected: Boolean,
    val enabled: Boolean = true,
    val select: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BoardSettingsScreen(
    target: BoardSettingsTarget,
    initialName: String,
    initialBackgroundColor: String?,
    screenSettings: BoardSettingsOverrides,
    pageSettings: BoardSettingsOverrides,
    appShowLabels: Boolean,
    appShowSymbols: Boolean,
    appLabelAtTop: Boolean,
    appShowMessageBar: Boolean,
    appShowSpeakButton: Boolean,
    appMessageBarEditable: Boolean,
    appActivationBehavior: BoardActivationBehavior,
    appReturnBehavior: BoardReturnBehavior,
    onCommit: (name: String, settings: BoardSettingsOverrides, backgroundColor: String?) -> Unit,
    onBack: () -> Unit
) {
    val initialOverrides = if (target == BoardSettingsTarget.Screen) screenSettings else pageSettings
    var name by remember(target, initialName) { mutableStateOf(initialName) }
    var draft by remember(target, initialOverrides) { mutableStateOf(initialOverrides) }
    var backgroundColor by remember(target, initialBackgroundColor) { mutableStateOf(initialBackgroundColor) }
    var showBackgroundColorPicker by remember(target) { mutableStateOf(false) }
    var preference by remember(target) { mutableStateOf<BoardSettingPreference?>(null) }

    val inherited = resolveBoardSettings(
        appShowLabels = appShowLabels,
        appShowSymbols = appShowSymbols,
        appLabelAtTop = appLabelAtTop,
        appShowMessageBar = appShowMessageBar,
        appShowSpeakButton = appShowSpeakButton,
        appMessageBarEditable = appMessageBarEditable,
        appActivationBehavior = appActivationBehavior,
        appReturnBehavior = appReturnBehavior,
        screen = if (target == BoardSettingsTarget.Page) screenSettings else BoardSettingsOverrides()
    )
    val resolved = resolveBoardSettings(
        appShowLabels = appShowLabels,
        appShowSymbols = appShowSymbols,
        appLabelAtTop = appLabelAtTop,
        appShowMessageBar = appShowMessageBar,
        appShowSpeakButton = appShowSpeakButton,
        appMessageBarEditable = appMessageBarEditable,
        appActivationBehavior = appActivationBehavior,
        appReturnBehavior = appReturnBehavior,
        screen = if (target == BoardSettingsTarget.Screen) draft else screenSettings,
        page = if (target == BoardSettingsTarget.Page) draft else BoardSettingsOverrides()
    )

    fun finish() {
        onCommit(name.trim().ifBlank { initialName }, draft, backgroundColor)
        onBack()
    }

    fun handleBack() {
        if (preference != null) preference = null else finish()
    }

    PlatformBackHandler(enabled = true, onBack = ::handleBack)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        if (preference != null) {
                            preferenceTitle(checkNotNull(preference))
                        } else {
                            stringResource(
                                if (target == BoardSettingsTarget.Screen) {
                                    R.string.board_settings_screen_title
                                } else {
                                    R.string.board_settings_page_title
                                }
                            )
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = ::handleBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                )
            )
        }
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            if (preference == null) {
                BoardSettingsHome(
                    target = target,
                    name = name,
                    onNameChange = { name = it },
                    draft = draft,
                    resolved = resolved,
                    inherited = inherited,
                    backgroundColor = backgroundColor,
                    onBackgroundColorChange = { backgroundColor = it },
                    onPickBackgroundColor = { showBackgroundColorPicker = true },
                    onOpenPreference = { preference = it },
                    onReset = {
                        draft = BoardSettingsOverrides()
                        if (target == BoardSettingsTarget.Page) backgroundColor = null
                    }
                )
            } else {
                BoardSettingChoicesPage(
                    target = target,
                    preference = checkNotNull(preference),
                    draft = draft,
                    resolved = resolved,
                    inherited = inherited,
                    onDraftChange = { draft = it }
                )
            }
        }
    }

    if (showBackgroundColorPicker) {
        ColorPickerDialog(
            initialColor = parseObfColorOrNull(backgroundColor) ?: MaterialTheme.colorScheme.background,
            initialUse = backgroundColor != null,
            onDismiss = { showBackgroundColorPicker = false },
            onPick = { color ->
                backgroundColor = color?.let {
                    "#" + (it.toArgb() and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()
                }
                showBackgroundColorPicker = false
            }
        )
    }
}

@Composable
private fun BoardSettingsHome(
    target: BoardSettingsTarget,
    name: String,
    onNameChange: (String) -> Unit,
    draft: BoardSettingsOverrides,
    resolved: ResolvedBoardSettings,
    inherited: ResolvedBoardSettings,
    backgroundColor: String?,
    onBackgroundColorChange: (String?) -> Unit,
    onPickBackgroundColor: () -> Unit,
    onOpenPreference: (BoardSettingPreference) -> Unit,
    onReset: () -> Unit
) {
    val showKeyboard = Modifier.showKeyboardOnFocus()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 920.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsGroup(
            title = stringResource(
                if (target == BoardSettingsTarget.Screen) {
                    R.string.board_settings_group_screen
                } else {
                    R.string.board_settings_group_page
                }
            )
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = {
                    Text(
                        stringResource(
                            if (target == BoardSettingsTarget.Screen) {
                                R.string.board_settings_screen_name
                            } else {
                                R.string.board_settings_page_name
                            }
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .then(showKeyboard),
                shape = MaterialTheme.shapes.large,
                singleLine = true
            )
        }

        SettingsGroup(title = stringResource(R.string.board_settings_group_appearance)) {
            if (target == BoardSettingsTarget.Page) {
                val previewColor = parseObfColorOrNull(backgroundColor)
                    ?: MaterialTheme.colorScheme.background
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(40.dp).clip(MaterialTheme.shapes.medium)
                            .background(previewColor)
                            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
                    )
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(stringResource(R.string.board_settings_background_color))
                        Text(
                            stringResource(
                                if (backgroundColor == null) R.string.board_settings_background_inherited
                                else R.string.board_settings_background_custom
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = onPickBackgroundColor) {
                        Text(stringResource(R.string.board_dialog_pick_color))
                    }
                    if (backgroundColor != null) {
                        IconButton(onClick = { onBackgroundColorChange(null) }) {
                            Icon(Icons.Default.Replay, contentDescription = stringResource(R.string.board_settings_background_reset))
                        }
                    }
                }
                SettingsGroupDivider()
            }
            BoardSettingNavRow(
                title = stringResource(R.string.board_settings_show_labels),
                subtitle = settingSubtitle(target, BoardSettingPreference.ShowLabels, draft.showLabels, shownHidden(resolved.showLabels), shownHidden(inherited.showLabels)),
                icon = Icons.AutoMirrored.Filled.Label,
                onClick = { onOpenPreference(BoardSettingPreference.ShowLabels) }
            )
            SettingsGroupDivider()
            BoardSettingNavRow(
                title = stringResource(R.string.board_settings_show_symbols),
                subtitle = settingSubtitle(target, BoardSettingPreference.ShowSymbols, draft.showSymbols, shownHidden(resolved.showSymbols), shownHidden(inherited.showSymbols)),
                icon = Icons.Filled.Image,
                onClick = { onOpenPreference(BoardSettingPreference.ShowSymbols) }
            )
            SettingsGroupDivider()
            BoardSettingNavRow(
                title = stringResource(R.string.board_settings_label_position),
                subtitle = if (resolved.showLabels && resolved.showSymbols) {
                    settingSubtitle(target, BoardSettingPreference.LabelPosition, draft.labelAtTop, topBottom(resolved.labelAtTop), topBottom(inherited.labelAtTop))
                } else {
                    stringResource(R.string.board_settings_label_position_disabled)
                },
                icon = Icons.Filled.VerticalAlignTop,
                enabled = resolved.showLabels && resolved.showSymbols,
                onClick = { onOpenPreference(BoardSettingPreference.LabelPosition) }
            )
        }

        SettingsGroup(title = stringResource(R.string.board_settings_group_communication)) {
            BoardSettingNavRow(
                title = stringResource(R.string.board_settings_message_bar),
                subtitle = settingSubtitle(target, BoardSettingPreference.MessageBar, draft.showMessageBar, shownHidden(resolved.showMessageBar), shownHidden(inherited.showMessageBar)),
                icon = Icons.Filled.ChatBubbleOutline,
                onClick = { onOpenPreference(BoardSettingPreference.MessageBar) }
            )
            SettingsGroupDivider()
            BoardSettingNavRow(
                title = stringResource(R.string.board_settings_speak_button),
                subtitle = settingSubtitle(target, BoardSettingPreference.SpeakButton, draft.showSpeakButton, shownHidden(resolved.showSpeakButton), shownHidden(inherited.showSpeakButton)),
                icon = Icons.Filled.VolumeUp,
                onClick = { onOpenPreference(BoardSettingPreference.SpeakButton) }
            )
            SettingsGroupDivider()
            BoardSettingNavRow(
                title = stringResource(R.string.board_settings_message_bar_editable),
                subtitle = settingSubtitle(target, BoardSettingPreference.MessageBarEditable, draft.messageBarEditable, editableReadOnly(resolved.messageBarEditable), editableReadOnly(inherited.messageBarEditable)),
                icon = Icons.Filled.Edit,
                onClick = { onOpenPreference(BoardSettingPreference.MessageBarEditable) }
            )
            SettingsGroupDivider()
            BoardSettingNavRow(
                title = stringResource(R.string.board_settings_activation),
                subtitle = settingSubtitle(
                    target,
                    BoardSettingPreference.Activation,
                    draft.activationBehavior,
                    activationLabel(resolved.activationBehavior),
                    activationLabel(inherited.activationBehavior)
                ),
                icon = Icons.Filled.TouchApp,
                onClick = { onOpenPreference(BoardSettingPreference.Activation) }
            )
            SettingsGroupDivider()
            BoardSettingNavRow(
                title = stringResource(R.string.board_settings_after_selection),
                subtitle = settingSubtitle(
                    target,
                    BoardSettingPreference.Return,
                    draft.returnBehavior,
                    returnLabel(resolved.returnBehavior),
                    returnLabel(inherited.returnBehavior)
                ),
                icon = Icons.Filled.Replay,
                onClick = { onOpenPreference(BoardSettingPreference.Return) }
            )
        }

        SettingsGroup(title = stringResource(R.string.board_settings_group_defaults)) {
            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                enabled = !draft.isEmpty || (target == BoardSettingsTarget.Page && backgroundColor != null)
            ) {
                Text(
                    stringResource(
                        if (target == BoardSettingsTarget.Screen) {
                            R.string.board_settings_reset_screen
                        } else {
                            R.string.board_settings_reset_page
                        }
                    )
                )
            }
        }
    }
}

@Composable
private fun BoardSettingNavRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    SettingsNavRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
private fun BoardSettingChoicesPage(
    target: BoardSettingsTarget,
    preference: BoardSettingPreference,
    draft: BoardSettingsOverrides,
    resolved: ResolvedBoardSettings,
    inherited: ResolvedBoardSettings,
    onDraftChange: (BoardSettingsOverrides) -> Unit
) {
    val choices = choicesFor(
        target = target,
        preference = preference,
        draft = draft,
        resolved = resolved,
        inherited = inherited,
        onDraftChange = onDraftChange
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 920.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        SettingsGroup(title = preferenceTitle(preference)) {
            choices.forEachIndexed { index, choice ->
                if (index > 0) SettingsGroupDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            enabled = choice.enabled,
                            role = Role.RadioButton,
                            onClick = choice.select
                        )
                        .alpha(if (choice.enabled) 1f else 0.5f)
                        .semantics { selected = choice.selected }
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        choice.label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(16.dp))
                    if (choice.selected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun choicesFor(
    target: BoardSettingsTarget,
    preference: BoardSettingPreference,
    draft: BoardSettingsOverrides,
    resolved: ResolvedBoardSettings,
    inherited: ResolvedBoardSettings,
    onDraftChange: (BoardSettingsOverrides) -> Unit
): List<BoardSettingChoice> {
    val inheritedPrefix = defaultLabel(target)
    fun inheritedChoice(value: String, selected: Boolean, select: () -> Unit) = BoardSettingChoice(
        label = "$inheritedPrefix · $value",
        selected = selected,
        select = select
    )

    return when (preference) {
        BoardSettingPreference.ShowLabels -> listOf(
            inheritedChoice(shownHidden(inherited.showLabels), draft.showLabels == null) {
                onDraftChange(draft.copy(showLabels = null))
            },
            BoardSettingChoice(shownHidden(true), draft.showLabels == true) {
                onDraftChange(draft.copy(showLabels = true))
            },
            BoardSettingChoice(
                label = shownHidden(false),
                selected = draft.showLabels == false,
                enabled = resolved.showSymbols
            ) { onDraftChange(draft.copy(showLabels = false)) }
        )
        BoardSettingPreference.ShowSymbols -> listOf(
            inheritedChoice(shownHidden(inherited.showSymbols), draft.showSymbols == null) {
                onDraftChange(draft.copy(showSymbols = null))
            },
            BoardSettingChoice(shownHidden(true), draft.showSymbols == true) {
                onDraftChange(draft.copy(showSymbols = true))
            },
            BoardSettingChoice(
                label = shownHidden(false),
                selected = draft.showSymbols == false,
                enabled = resolved.showLabels
            ) { onDraftChange(draft.copy(showSymbols = false)) }
        )
        BoardSettingPreference.LabelPosition -> listOf(
            inheritedChoice(topBottom(inherited.labelAtTop), draft.labelAtTop == null) {
                onDraftChange(draft.copy(labelAtTop = null))
            },
            BoardSettingChoice(topBottom(true), draft.labelAtTop == true) {
                onDraftChange(draft.copy(labelAtTop = true))
            },
            BoardSettingChoice(topBottom(false), draft.labelAtTop == false) {
                onDraftChange(draft.copy(labelAtTop = false))
            }
        )
        BoardSettingPreference.MessageBar -> listOf(
            inheritedChoice(shownHidden(inherited.showMessageBar), draft.showMessageBar == null) {
                onDraftChange(draft.copy(showMessageBar = null))
            },
            BoardSettingChoice(shownHidden(true), draft.showMessageBar == true) {
                onDraftChange(draft.copy(showMessageBar = true))
            },
            BoardSettingChoice(shownHidden(false), draft.showMessageBar == false) {
                onDraftChange(draft.copy(showMessageBar = false))
            }
        )
        BoardSettingPreference.SpeakButton -> listOf(
            inheritedChoice(shownHidden(inherited.showSpeakButton), draft.showSpeakButton == null) {
                onDraftChange(draft.copy(showSpeakButton = null))
            },
            BoardSettingChoice(shownHidden(true), draft.showSpeakButton == true) {
                onDraftChange(draft.copy(showSpeakButton = true))
            },
            BoardSettingChoice(shownHidden(false), draft.showSpeakButton == false) {
                onDraftChange(draft.copy(showSpeakButton = false))
            }
        )
        BoardSettingPreference.MessageBarEditable -> listOf(
            inheritedChoice(editableReadOnly(inherited.messageBarEditable), draft.messageBarEditable == null) {
                onDraftChange(draft.copy(messageBarEditable = null))
            },
            BoardSettingChoice(editableReadOnly(true), draft.messageBarEditable == true) {
                onDraftChange(draft.copy(messageBarEditable = true))
            },
            BoardSettingChoice(editableReadOnly(false), draft.messageBarEditable == false) {
                onDraftChange(draft.copy(messageBarEditable = false))
            }
        )
        BoardSettingPreference.Activation -> buildList {
            add(
                inheritedChoice(activationLabel(inherited.activationBehavior), draft.activationBehavior == null) {
                    onDraftChange(draft.copy(activationBehavior = null))
                }
            )
            BoardActivationBehavior.entries.forEach { behavior ->
                add(
                    BoardSettingChoice(
                        label = activationLabel(behavior),
                        selected = draft.activationBehavior == behavior
                    ) { onDraftChange(draft.copy(activationBehavior = behavior)) }
                )
            }
        }
        BoardSettingPreference.Return -> buildList {
            add(
                inheritedChoice(returnLabel(inherited.returnBehavior), draft.returnBehavior == null) {
                    onDraftChange(draft.copy(returnBehavior = null))
                }
            )
            BoardReturnBehavior.entries.forEach { behavior ->
                add(
                    BoardSettingChoice(
                        label = returnLabel(behavior),
                        selected = draft.returnBehavior == behavior
                    ) { onDraftChange(draft.copy(returnBehavior = behavior)) }
                )
            }
        }
    }
}

@Composable
private fun preferenceTitle(preference: BoardSettingPreference): String = stringResource(
    when (preference) {
        BoardSettingPreference.ShowLabels -> R.string.board_settings_show_labels
        BoardSettingPreference.ShowSymbols -> R.string.board_settings_show_symbols
        BoardSettingPreference.LabelPosition -> R.string.board_settings_label_position
        BoardSettingPreference.MessageBar -> R.string.board_settings_message_bar
        BoardSettingPreference.SpeakButton -> R.string.board_settings_speak_button
        BoardSettingPreference.MessageBarEditable -> R.string.board_settings_message_bar_editable
        BoardSettingPreference.Activation -> R.string.board_settings_activation
        BoardSettingPreference.Return -> R.string.board_settings_after_selection
    }
)

@Composable
private fun shownHidden(value: Boolean): String =
    stringResource(if (value) R.string.board_settings_shown else R.string.board_settings_hidden)

@Composable
private fun editableReadOnly(value: Boolean): String =
    stringResource(if (value) R.string.board_settings_editable else R.string.board_settings_read_only)

@Composable
private fun topBottom(value: Boolean): String =
    stringResource(if (value) R.string.board_settings_top else R.string.board_settings_bottom)

@Composable
private fun activationLabel(value: BoardActivationBehavior): String = stringResource(
    when (value) {
        BoardActivationBehavior.SpeakAndAdd -> R.string.board_settings_activation_speak_add
        BoardActivationBehavior.AddOnly -> R.string.board_settings_activation_add
        BoardActivationBehavior.SpeakOnly -> R.string.board_settings_activation_speak
    }
)

@Composable
private fun returnLabel(value: BoardReturnBehavior): String = stringResource(
    when (value) {
        BoardReturnBehavior.Stay -> R.string.board_settings_return_stay
        BoardReturnBehavior.Previous -> R.string.board_settings_return_previous
        BoardReturnBehavior.StartPage -> R.string.board_settings_return_start
    }
)

@Composable
private fun settingSubtitle(
    target: BoardSettingsTarget,
    preference: BoardSettingPreference,
    explicitValue: Any?,
    effectiveLabel: String,
    inheritedLabel: String
): String = if (explicitValue == null) {
    val prefix = defaultLabel(target)
    "$prefix · $inheritedLabel"
} else {
    effectiveLabel
}

@Composable
private fun defaultLabel(
    target: BoardSettingsTarget
): String = stringResource(
    if (target == BoardSettingsTarget.Page) {
        R.string.board_settings_use_screen
    } else {
        R.string.board_settings_use_default
    }
)
