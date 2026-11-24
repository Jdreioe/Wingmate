package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.WordTypeColorScheme
import io.github.jdreioe.wingmate.domain.obf.BoardActivationBehavior
import io.github.jdreioe.wingmate.domain.obf.BoardReturnBehavior

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpeechSection(
    ttsEngine: TtsEngine,
    onTtsEngineChange: (TtsEngine) -> Unit,
    googleCredentialConfigured: Boolean,
    onOpenElevenLabsSetup: () -> Unit,
    onOpenGoogleSetup: () -> Unit,
    onClearGoogleCredentials: () -> Unit,
    onOpenVoiceSelection: () -> Unit = {},
    onOpenLanguageSelection: () -> Unit = {},
    onOpenF0Setup: () -> Unit = {}
) {
    SettingsGroup(title = stringResource(R.string.ui_settings_tts_engine_group)) {
        SettingsPreferenceRow(
            title = stringResource(R.string.ui_settings_speech_engine),
            subtitle = stringResource(
                when (ttsEngine) {
                    TtsEngine.SYSTEM -> R.string.ui_settings_system_tts
                    TtsEngine.ELEVENLABS -> R.string.elevenlabs_title
                    TtsEngine.GOOGLE_CLOUD -> R.string.ui_settings_google_tts
                    TtsEngine.AZURE_USER_RESOURCE, TtsEngine.AZURE_MANAGED -> R.string.ui_settings_azure_tts
                }
            )
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = ttsEngine == TtsEngine.ELEVENLABS,
                    onClick = { onTtsEngineChange(TtsEngine.ELEVENLABS) },
                    label = { Text("ElevenLabs") },
                )
                FilterChip(
                    selected = ttsEngine == TtsEngine.AZURE_USER_RESOURCE || ttsEngine == TtsEngine.AZURE_MANAGED,
                    onClick = { onTtsEngineChange(TtsEngine.AZURE_USER_RESOURCE) },
                    label = { Text(stringResource(R.string.ui_settings_azure)) }
                )
                FilterChip(
                    selected = ttsEngine == TtsEngine.GOOGLE_CLOUD,
                    onClick = { onTtsEngineChange(TtsEngine.GOOGLE_CLOUD) },
                    label = { Text(stringResource(R.string.ui_settings_google)) },
                )
                FilterChip(
                    selected = ttsEngine == TtsEngine.SYSTEM,
                    onClick = { onTtsEngineChange(TtsEngine.SYSTEM) },
                    label = { Text(stringResource(R.string.ui_settings_system)) }
                )
            }
        }
        if (ttsEngine == TtsEngine.AZURE_USER_RESOURCE || ttsEngine == TtsEngine.AZURE_MANAGED) {
            SettingsGroupDivider()
            Button(onClick = onOpenF0Setup, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ui_settings_azure_setup))
            }
        }

        if (ttsEngine == TtsEngine.ELEVENLABS) {
            SettingsGroupDivider()
            Button(onClick = onOpenElevenLabsSetup, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.elevenlabs_setup))
            }
        }
        if (ttsEngine == TtsEngine.GOOGLE_CLOUD) {
            SettingsGroupDivider()
            Column(
                modifier = Modifier.padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (googleCredentialConfigured) {
                    Text(stringResource(R.string.google_tts_configured))
                    OutlinedButton(onClick = onOpenGoogleSetup) {
                        Text(stringResource(R.string.google_tts_replace_key))
                    }
                    TextButton(onClick = onClearGoogleCredentials) {
                        Text(stringResource(R.string.google_tts_clear_key))
                    }
                } else {
                    Button(onClick = onOpenGoogleSetup, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.ui_settings_google_setup))
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }

    SettingsGroup(title = stringResource(R.string.phrase_screen_voice_settings)) {
        SettingsNavRow(
            title = stringResource(R.string.voice_select_title),
            subtitle = stringResource(R.string.ui_settings_speech_desc),
            icon = Icons.Filled.RecordVoiceOver,
            onClick = onOpenVoiceSelection
        )
        SettingsGroupDivider()
        SettingsNavRow(
            title = stringResource(R.string.common_language),
            subtitle = stringResource(R.string.ui_settings_speech_desc),
            icon = Icons.Filled.Language,
            onClick = onOpenLanguageSelection
        )
    }
}

@Composable
internal fun DisplaySection(
    fontSizeScale: Float,
    onFontSizeScaleChange: (Float) -> Unit,
    playbackIconScale: Float,
    onPlaybackIconScaleChange: (Float) -> Unit,
    categoryChipScale: Float,
    onCategoryChipScaleChange: (Float) -> Unit,
    buttonScale: Float,
    onButtonScaleChange: (Float) -> Unit,
    inputFieldScale: Float,
    onInputFieldScaleChange: (Float) -> Unit,
    showLabels: Boolean,
    onShowLabelsChange: (Boolean) -> Unit,
    showSymbols: Boolean,
    onShowSymbolsChange: (Boolean) -> Unit,
    labelAtTop: Boolean,
    onLabelAtTopChange: (Boolean) -> Unit,
    boardShowMessageBar: Boolean,
    onBoardShowMessageBarChange: (Boolean) -> Unit,
    boardShowSpeakButton: Boolean,
    onBoardShowSpeakButtonChange: (Boolean) -> Unit,
    boardMessageBarEditable: Boolean,
    onBoardMessageBarEditableChange: (Boolean) -> Unit,
    boardActivationBehavior: BoardActivationBehavior,
    onBoardActivationBehaviorChange: (BoardActivationBehavior) -> Unit,
    boardReturnBehavior: BoardReturnBehavior,
    onBoardReturnBehaviorChange: (BoardReturnBehavior) -> Unit,
    gridColumns: Int,
    onGridColumnsChange: (Int) -> Unit,
    onGridColumnsChangeFinished: () -> Unit,
    highContrastMode: Boolean,
    onHighContrastModeChange: (Boolean) -> Unit,
    wordTypeColorScheme: WordTypeColorScheme,
    onWordTypeColorSchemeChange: (WordTypeColorScheme) -> Unit
) {
    SettingsGroup(title = stringResource(R.string.ui_settings_grid_layout)) {
        SettingsSwitch(
            checked = showLabels,
            onCheckedChange = onShowLabelsChange,
            title = stringResource(R.string.ui_settings_show_labels_title)
        )
        SettingsGroupDivider()
        SettingsSwitch(
            checked = showSymbols,
            onCheckedChange = onShowSymbolsChange,
            title = stringResource(R.string.ui_settings_show_symbols_title)
        )
        if (showLabels && showSymbols) {
            SettingsGroupDivider()
            SettingsSwitch(
                checked = labelAtTop,
                onCheckedChange = onLabelAtTopChange,
                title = stringResource(R.string.ui_settings_label_at_top_title)
            )
        }
        SettingsGroupDivider()
        SettingsSlider(
            title = stringResource(R.string.ui_settings_grid_columns_title),
            value = gridColumns.toFloat(),
            onValueChange = { onGridColumnsChange(it.toInt()) },
            onValueChangeFinished = onGridColumnsChangeFinished,
            valueRange = 1f..6f,
            steps = 4,
            valueLabel = "$gridColumns"
        )
        SettingsGroupDivider()
        SettingsSwitch(
            checked = highContrastMode,
            onCheckedChange = onHighContrastModeChange,
            title = stringResource(R.string.ui_settings_high_contrast_title)
        )
        SettingsGroupDivider()
        SettingsSwitch(
            checked = wordTypeColorScheme == WordTypeColorScheme.Fitzgerald,
            onCheckedChange = {
                onWordTypeColorSchemeChange(
                    if (it) WordTypeColorScheme.Fitzgerald else WordTypeColorScheme.None
                )
            },
            title = stringResource(R.string.ui_settings_word_type_colors_title),
            description = stringResource(R.string.ui_settings_word_type_colors_desc)
        )
    }

    SettingsGroup(title = stringResource(R.string.ui_settings_scaling)) {
        ScaleSlider(stringResource(R.string.ui_settings_font_size), fontSizeScale, onFontSizeScaleChange)
        SettingsGroupDivider()
        ScaleSlider(stringResource(R.string.ui_settings_playback_icons), playbackIconScale, onPlaybackIconScaleChange)
        SettingsGroupDivider()
        ScaleSlider(stringResource(R.string.ui_settings_category_chips), categoryChipScale, onCategoryChipScaleChange)
        SettingsGroupDivider()
        ScaleSlider(stringResource(R.string.ui_settings_buttons), buttonScale, onButtonScaleChange)
        SettingsGroupDivider()
        ScaleSlider(stringResource(R.string.ui_settings_input_fields), inputFieldScale, onInputFieldScaleChange)
    }

    SettingsGroup(title = stringResource(R.string.board_settings_group_communication)) {
        SettingsSwitch(
            checked = boardShowMessageBar,
            onCheckedChange = onBoardShowMessageBarChange,
            title = stringResource(R.string.board_settings_message_bar),
            description = stringResource(R.string.board_settings_global_message_bar_desc)
        )
        SettingsGroupDivider()
        SettingsSwitch(
            checked = boardShowSpeakButton,
            onCheckedChange = onBoardShowSpeakButtonChange,
            title = stringResource(R.string.board_settings_speak_button),
            description = stringResource(R.string.board_settings_global_speak_button_desc)
        )
        SettingsGroupDivider()
        SettingsSwitch(
            checked = boardMessageBarEditable,
            onCheckedChange = onBoardMessageBarEditableChange,
            title = stringResource(R.string.board_settings_message_bar_editable),
            description = stringResource(R.string.board_settings_global_message_bar_editable_desc)
        )
        SettingsGroupDivider()
        SettingsChoiceChips(
            title = stringResource(R.string.board_settings_activation),
            description = stringResource(R.string.board_settings_global_activation_desc),
            selected = boardActivationBehavior,
            options = BoardActivationBehavior.entries,
            label = { behavior ->
                stringResource(
                    when (behavior) {
                        BoardActivationBehavior.SpeakAndAdd -> R.string.board_settings_activation_speak_add
                        BoardActivationBehavior.AddOnly -> R.string.board_settings_activation_add
                        BoardActivationBehavior.SpeakOnly -> R.string.board_settings_activation_speak
                    }
                )
            },
            onSelect = onBoardActivationBehaviorChange
        )
        SettingsGroupDivider()
        SettingsChoiceChips(
            title = stringResource(R.string.board_settings_after_selection),
            description = stringResource(R.string.board_settings_global_return_desc),
            selected = boardReturnBehavior,
            options = BoardReturnBehavior.entries,
            label = { behavior ->
                stringResource(
                    when (behavior) {
                        BoardReturnBehavior.Stay -> R.string.board_settings_return_stay
                        BoardReturnBehavior.Previous -> R.string.board_settings_return_previous
                        BoardReturnBehavior.StartPage -> R.string.board_settings_return_start
                    }
                )
            },
            onSelect = onBoardReturnBehaviorChange
        )
    }
}
