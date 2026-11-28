package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.application.EditingAccessState
import io.github.jdreioe.wingmate.domain.PointerEmphasisStyle
import io.github.jdreioe.wingmate.domain.SpeechPolicy
import io.github.jdreioe.wingmate.domain.StartupMode
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.infrastructure.ArasaacDownloadProgress

@Composable
internal fun AccessibilitySection(
    holdToSelectMillis: Long,
    onHoldToSelectChange: (Long) -> Unit,
    onHoldToSelectChangeFinished: () -> Unit,
    selectOnTouchDown: Boolean,
    onSelectOnTouchDownChange: (Boolean) -> Unit,
    selectOnTouchExit: Boolean,
    onSelectOnTouchExitChange: (Boolean) -> Unit,
    swipeBetweenPages: Boolean,
    onSwipeBetweenPagesChange: (Boolean) -> Unit,
    dwellToSelectMillis: Long,
    onDwellToSelectChange: (Long) -> Unit,
    onDwellToSelectChangeFinished: () -> Unit,
    selectionSoundEnabled: Boolean,
    onSelectionSoundChange: (Boolean) -> Unit,
    auditoryFishingEnabled: Boolean,
    onAuditoryFishingChange: (Boolean) -> Unit,
    routeAuditoryPreviewToHeadphones: Boolean,
    onRouteAuditoryPreviewToHeadphonesChange: (Boolean) -> Unit,
    speechPolicy: SpeechPolicy,
    onSpeechPolicyChange: (SpeechPolicy) -> Unit,
    selectionDebounceMillis: Long,
    onSelectionDebounceChange: (Long) -> Unit,
    onSelectionDebounceChangeFinished: () -> Unit,
    dwellRearmDelayMillis: Long,
    onDwellRearmDelayChange: (Long) -> Unit,
    onDwellRearmDelayChangeFinished: () -> Unit,
    selectionHighlightMillis: Long,
    onSelectionHighlightChange: (Long) -> Unit,
    onSelectionHighlightChangeFinished: () -> Unit,
    selectKeyBinding: String,
    onSelectKeyBindingChange: (String) -> Unit,
    restModeKeyBinding: String,
    onRestModeKeyBindingChange: (String) -> Unit,
    pointerEmphasisStyle: PointerEmphasisStyle,
    onPointerEmphasisStyleChange: (PointerEmphasisStyle) -> Unit,
    pointerEmphasisScale: Float,
    onPointerEmphasisScaleChange: (Float) -> Unit,
    onPointerEmphasisScaleChangeFinished: () -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.ui_settings_interaction)) {
        SettingsChoiceChips(
            title = stringResource(R.string.ui_settings_select_key),
            description = stringResource(R.string.ui_settings_select_key_desc),
            selected = selectKeyBinding,
            options = listOf("", "Space", "Enter", "F8", "F9"),
            label = { it.ifBlank { stringResource(R.string.common_off) } },
            onSelect = onSelectKeyBindingChange,
        )
        SettingsGroupDivider()
        SettingsChoiceChips(
            title = stringResource(R.string.ui_settings_rest_key),
            description = stringResource(R.string.ui_settings_rest_key_desc),
            selected = restModeKeyBinding,
            options = listOf("", "Space", "Enter", "F8", "F9"),
            label = { it.ifBlank { stringResource(R.string.common_off) } },
            onSelect = onRestModeKeyBindingChange,
        )
    }

    SettingsGroup(title = stringResource(R.string.ui_settings_touch_timing)) {
        SettingsSlider(
            title = stringResource(R.string.ui_settings_hold_to_select_title),
            description = stringResource(R.string.ui_settings_hold_to_select_desc),
            value = holdToSelectMillis.toFloat(),
            onValueChange = { onHoldToSelectChange(it.toLong()) },
            onValueChangeFinished = onHoldToSelectChangeFinished,
            valueRange = 0f..2000f,
            steps = 19,
            valueLabel = "${holdToSelectMillis.toInt()} ms"
        )
        SettingsGroupDivider()
        SettingsSwitch(
            checked = selectOnTouchDown,
            onCheckedChange = onSelectOnTouchDownChange,
            title = stringResource(R.string.ui_settings_select_on_touch_down_title),
            description = stringResource(R.string.ui_settings_select_on_touch_down_desc)
        )
        SettingsGroupDivider()
        SettingsSwitch(
            checked = selectOnTouchExit,
            onCheckedChange = onSelectOnTouchExitChange,
            title = stringResource(R.string.ui_settings_select_on_touch_exit_title),
            description = stringResource(R.string.ui_settings_select_on_touch_exit_desc)
        )
        SettingsGroupDivider()
        SettingsSwitch(
            checked = swipeBetweenPages,
            onCheckedChange = onSwipeBetweenPagesChange,
            title = stringResource(R.string.ui_settings_swipe_pages_title),
            description = stringResource(R.string.ui_settings_swipe_pages_desc)
        )
        SettingsGroupDivider()
        SettingsSlider(
            title = stringResource(R.string.ui_settings_dwell_to_select_title),
            description = stringResource(R.string.ui_settings_dwell_to_select_desc),
            value = dwellToSelectMillis.toFloat(),
            onValueChange = { onDwellToSelectChange(it.toLong()) },
            onValueChangeFinished = onDwellToSelectChangeFinished,
            valueRange = 0f..5000f,
            steps = 19,
            valueLabel = "${dwellToSelectMillis.toInt()} ms"
        )
        SettingsGroupDivider()
        SettingsSlider(
            title = stringResource(R.string.ui_settings_selection_debounce_title),
            description = stringResource(R.string.ui_settings_selection_debounce_desc),
            value = selectionDebounceMillis.toFloat(),
            onValueChange = { onSelectionDebounceChange(it.toLong()) },
            onValueChangeFinished = onSelectionDebounceChangeFinished,
            valueRange = 0f..1000f,
            steps = 19,
            valueLabel = "${selectionDebounceMillis.toInt()} ms"
        )
        SettingsGroupDivider()
        SettingsSlider(
            title = stringResource(R.string.ui_settings_dwell_rearm_title),
            description = stringResource(R.string.ui_settings_dwell_rearm_desc),
            value = dwellRearmDelayMillis.toFloat(),
            onValueChange = { onDwellRearmDelayChange(it.toLong()) },
            onValueChangeFinished = onDwellRearmDelayChangeFinished,
            valueRange = 0f..500f,
            steps = 9,
            valueLabel = "${dwellRearmDelayMillis.toInt()} ms"
        )
    }

    SettingsGroup(title = stringResource(R.string.ui_settings_feedback)) {
        SettingsSwitch(
            checked = selectionSoundEnabled,
            onCheckedChange = onSelectionSoundChange,
            title = stringResource(R.string.ui_settings_selection_sound_title)
        )
        SettingsGroupDivider()
        SettingsChoiceChips(
            title = stringResource(R.string.ui_settings_speech_policy_title),
            description = stringResource(R.string.ui_settings_speech_policy_desc),
            selected = speechPolicy,
            options = SpeechPolicy.entries,
            label = { policy ->
                stringResource(
                    when (policy) {
                        SpeechPolicy.Immediate -> R.string.ui_settings_speech_policy_immediate
                        SpeechPolicy.SentenceOnly -> R.string.ui_settings_speech_policy_sentence_only
                    }
                )
            },
            onSelect = onSpeechPolicyChange
        )
        SettingsGroupDivider()
        SettingsSwitch(
            checked = auditoryFishingEnabled,
            onCheckedChange = onAuditoryFishingChange,
            title = stringResource(R.string.ui_settings_auditory_fishing_title),
            description = stringResource(R.string.ui_settings_auditory_fishing_desc)
        )
        SettingsGroupDivider()
        SettingsSwitch(
            checked = routeAuditoryPreviewToHeadphones,
            onCheckedChange = onRouteAuditoryPreviewToHeadphonesChange,
            title = stringResource(R.string.ui_settings_auditory_preview_headphones_title),
            description = stringResource(R.string.ui_settings_auditory_preview_headphones_desc)
        )
        SettingsGroupDivider()
        SettingsSlider(
            title = stringResource(R.string.ui_settings_selection_highlight_title),
            description = stringResource(R.string.ui_settings_selection_highlight_desc),
            value = selectionHighlightMillis.toFloat(),
            onValueChange = { onSelectionHighlightChange(it.toLong()) },
            onValueChangeFinished = onSelectionHighlightChangeFinished,
            valueRange = 0f..2000f,
            steps = 19,
            valueLabel = "${selectionHighlightMillis.toInt()} ms"
        )
    }

    SettingsGroup(title = stringResource(R.string.ui_settings_pointer_emphasis)) {
        SettingsChoiceChips(
            title = stringResource(R.string.ui_settings_pointer_style),
            description = stringResource(R.string.ui_settings_pointer_style_desc),
            selected = pointerEmphasisStyle,
            options = PointerEmphasisStyle.entries,
            label = { it.name },
            onSelect = onPointerEmphasisStyleChange,
        )
        SettingsGroupDivider()
        SettingsSlider(
            title = stringResource(R.string.ui_settings_pointer_size),
            description = stringResource(R.string.ui_settings_pointer_size_desc),
            value = pointerEmphasisScale,
            onValueChange = onPointerEmphasisScaleChange,
            onValueChangeFinished = onPointerEmphasisScaleChangeFinished,
            valueRange = 1f..3f,
            steps = 7,
            valueLabel = "%.1f×".format(pointerEmphasisScale),
        )
    }
}

@Composable
private fun BackupSettingsGroup(
    working: Boolean,
    statusMessage: String?,
    onCreateBackup: () -> Unit,
    onRestoreBackup: () -> Unit
) {
    SettingsGroup(title = stringResource(R.string.backup_title)) {
        Text(
            stringResource(R.string.backup_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !working,
                onClick = onCreateBackup,
            ) { Text(stringResource(R.string.backup_create)) }
            OutlinedButton(
                enabled = !working,
                onClick = onRestoreBackup,
            ) { Text(stringResource(R.string.backup_restore)) }
        }
        statusMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
internal fun PrivacySection(
    historyVisible: Boolean,
    onHistoryVisibleChange: (Boolean) -> Unit,
    likelyNextPageEnabled: Boolean,
    onLikelyNextPageChange: (Boolean) -> Unit,
    boardSets: List<ObfBoardSet>,
    onBoardSetSentenceCachingChange: (ObfBoardSet, Boolean) -> Unit,
    usageLoggingEnabled: Boolean,
    onUsageLoggingChange: (Boolean) -> Unit,
    featureUsageReportingEnabled: Boolean,
    onFeatureReportingChange: (Boolean) -> Unit
) {
    SettingsGroup(title = stringResource(R.string.ui_settings_privacy_local_data_title)) {
        SettingsSwitch(
            checked = historyVisible,
            onCheckedChange = onHistoryVisibleChange,
            title = stringResource(R.string.ui_settings_history_visible_title),
            description = stringResource(R.string.ui_settings_history_visible_desc)
        )
        // The experimental Page is built from visible History, so it only applies while History is shown.
        if (historyVisible) {
            SettingsGroupDivider()
            SettingsSwitch(
                checked = likelyNextPageEnabled,
                onCheckedChange = onLikelyNextPageChange,
                title = stringResource(R.string.ui_settings_likely_next_title),
                description = stringResource(R.string.ui_settings_likely_next_desc)
            )
        }
    }

    SettingsGroup(title = stringResource(R.string.ui_settings_board_cache_title)) {
        Text(
            stringResource(R.string.ui_settings_board_cache_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (boardSets.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.ui_settings_board_cache_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            boardSets.forEach { boardSet ->
                SettingsGroupDivider()
                SettingsSwitch(
                    checked = boardSet.cacheWholeSentences,
                    onCheckedChange = { onBoardSetSentenceCachingChange(boardSet, it) },
                    title = boardSet.name
                )
            }
        }
    }

    SettingsGroup(title = stringResource(R.string.ui_settings_privacy_collection_title)) {
        SettingsSwitch(
            checked = featureUsageReportingEnabled,
            onCheckedChange = onFeatureReportingChange,
            title = stringResource(R.string.ui_settings_feature_reporting_title),
            description = stringResource(R.string.ui_settings_feature_reporting_desc)
        )
        SettingsGroupDivider()
        SettingsSwitch(
            checked = usageLoggingEnabled,
            onCheckedChange = onUsageLoggingChange,
            title = stringResource(R.string.ui_settings_usage_logging_title),
            description = stringResource(R.string.ui_settings_usage_logging_desc)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GeneralSection(
    onBackToWelcome: (() -> Unit)? = null,
    startupMode: StartupMode,
    startupBoardSetId: String?,
    availableBoardSets: List<ObfBoardSet>,
    arasaacAvailable: Boolean,
    cachedArasaacSymbols: Int,
    arasaacProgress: ArasaacDownloadProgress?,
    arasaacDownloadError: Boolean,
    arasaacFailedCount: Int,
    editingAccessState: EditingAccessState?,
    backupWorking: Boolean,
    backupStatusMessage: String?,
    onStartupModeChange: (StartupMode) -> Unit,
    onStartupBoardSetChange: (String?) -> Unit,
    onDownloadArasaac: () -> Unit,
    onCreateBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onEditingAccessConfigure: () -> Unit,
    onEditingAccessUnlock: () -> Unit,
    onEditingAccessDisable: () -> Unit,
    onEditingAccessLockNow: () -> Unit
) {
    BackupSettingsGroup(
        working = backupWorking,
        statusMessage = backupStatusMessage,
        onCreateBackup = onCreateBackup,
        onRestoreBackup = onRestoreBackup
    )

    SettingsGroup(title = stringResource(R.string.editing_access_title)) {
        Text(
            stringResource(R.string.editing_access_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        val access = editingAccessState
        if (access == null) {
            Text(stringResource(R.string.editing_access_unavailable), color = MaterialTheme.colorScheme.error)
        } else if (!access.supported) {
            Text(stringResource(R.string.editing_access_unavailable), color = MaterialTheme.colorScheme.error)
        } else if (!access.enabled) {
            OutlinedButton(
                onClick = onEditingAccessConfigure,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.editing_access_enable)) }
        } else {
            if (!access.unlocked) {
                OutlinedButton(
                    onClick = onEditingAccessUnlock,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.editing_access_unlock_title)) }
            }
            OutlinedButton(
                onClick = onEditingAccessConfigure,
                enabled = access.unlocked,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.editing_access_change)) }
            OutlinedButton(
                onClick = onEditingAccessDisable,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.editing_access_disable)) }
            OutlinedButton(
                onClick = onEditingAccessLockNow,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.editing_access_lock_now)) }
        }
    }

    SettingsGroup(title = stringResource(R.string.ui_settings_startup_mode_title)) {
        Text(
            stringResource(R.string.ui_settings_startup_mode_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = startupMode == StartupMode.Keyboard,
                onClick = { onStartupModeChange(StartupMode.Keyboard) },
                label = { Text(stringResource(R.string.ui_settings_startup_mode_keyboard)) },
                leadingIcon = { Icon(Icons.Filled.Keyboard, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = startupMode == StartupMode.Screens,
                onClick = { onStartupModeChange(StartupMode.Screens) },
                label = { Text(stringResource(R.string.ui_settings_startup_mode_screens)) },
                leadingIcon = { Icon(Icons.Filled.GridView, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.weight(1f)
            )
        }
        if (startupMode == StartupMode.Screens) {
            var targetExpanded by remember { mutableStateOf(false) }
            val selectedName = availableBoardSets
                .firstOrNull { it.id == startupBoardSetId }
                ?.name
                ?: stringResource(R.string.ui_settings_startup_screen_library)

            Spacer(modifier = Modifier.height(12.dp))
            ExposedDropdownMenuBox(
                expanded = targetExpanded,
                onExpandedChange = { targetExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.ui_settings_startup_screen_title)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = targetExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                )
                ExposedDropdownMenu(
                    expanded = targetExpanded,
                    onDismissRequest = { targetExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ui_settings_startup_screen_library)) },
                        onClick = { onStartupBoardSetChange(null); targetExpanded = false }
                    )
                    availableBoardSets.forEach { boardSet ->
                        DropdownMenuItem(
                            text = { Text(boardSet.name) },
                            onClick = { onStartupBoardSetChange(boardSet.id); targetExpanded = false }
                        )
                    }
                }
            }
        }
    }

    SettingsGroup(title = stringResource(R.string.ui_settings_symbols_title)) {
        Text(
            stringResource(R.string.ui_settings_symbols_download_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        val activeProgress = arasaacProgress
        OutlinedButton(
            onClick = onDownloadArasaac,
            enabled = arasaacAvailable && activeProgress == null
        ) {
            if (activeProgress != null) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (activeProgress != null) R.string.ui_settings_symbols_downloading else R.string.ui_settings_symbols_download))
        }
        if (activeProgress != null && activeProgress.total > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(progress = { activeProgress.completed.toFloat() / activeProgress.total }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            when {
                !arasaacAvailable -> stringResource(R.string.ui_settings_symbols_unavailable)
                arasaacDownloadError -> pluralStringResource(
                    R.plurals.ui_settings_symbols_failed,
                    arasaacFailedCount,
                    arasaacFailedCount,
                )
                cachedArasaacSymbols > 0 -> pluralStringResource(
                    R.plurals.ui_settings_symbols_cached,
                    cachedArasaacSymbols,
                    cachedArasaacSymbols,
                )
                else -> stringResource(R.string.ui_settings_symbols_download_title)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (arasaacDownloadError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (onBackToWelcome != null) {
        SettingsGroup(title = stringResource(R.string.phrase_screen_welcome_screen)) {
            OutlinedButton(
                onClick = onBackToWelcome,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Home, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.phrase_screen_welcome_screen))
            }
        }
    }
}
