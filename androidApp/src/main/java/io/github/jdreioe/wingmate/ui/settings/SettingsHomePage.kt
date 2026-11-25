package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hojmoseit.wingmate.R

private data class SettingsCategoryItem(
    val tab: SettingsTab?,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val iconContainerColor: Color,
    val iconColor: Color,
    val keywords: List<String> = emptyList(),
    val openPronunciation: Boolean = false
) {
    fun matches(query: String): Boolean {
        if (query.isEmpty()) return true
        if (title.lowercase().contains(query)) return true
        if (subtitle.lowercase().contains(query)) return true
        return keywords.any { it.lowercase().contains(query) }
    }
}

@Composable
internal fun SettingsHomePage(
    onSelectCategory: (SettingsTab) -> Unit,
    onOpenPronunciation: () -> Unit,
    modifier: Modifier = Modifier,
    /** On a two-pane layout, the category open beside the list. */
    selectedTab: SettingsTab? = null,
    pronunciationSelected: Boolean = false,
) {
    var query by remember { mutableStateOf("") }
    val speechTitle = stringResource(R.string.ui_settings_speech_title)
    val displayTitle = stringResource(R.string.ui_settings_display_title)
    val accessibilityTitle = stringResource(R.string.ui_settings_accessibility_title)
    val privacyTitle = stringResource(R.string.ui_settings_privacy_title)
    val generalTitle = stringResource(R.string.ui_settings_general_title)

    val categories = listOf(
        SettingsCategoryItem(
            tab = SettingsTab.Speech,
            title = speechTitle,
            subtitle = stringResource(R.string.ui_settings_speech_desc),
            icon = Icons.Filled.RecordVoiceOver,
            iconContainerColor = Color(0xFF78D6F7),
            iconColor = Color(0xFF004E65),
            keywords = listOf(
                "tts", "azure", "system tts", "engine", "endpoint", "subscription",
                "region", "key", "voice", stringResource(R.string.phrase_screen_voice_settings),
                stringResource(R.string.voice_select_title), stringResource(R.string.common_language),
                "language"
            )
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = displayTitle,
            subtitle = stringResource(R.string.ui_settings_display_desc),
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf(
                "grid", "layout", "scaling", "ui scaling", "font", "font size",
                "playback", "icons", "category", "chips", "buttons", "input",
                "fields", stringResource(R.string.ui_settings_show_labels_title),
                stringResource(R.string.ui_settings_show_symbols_title),
                stringResource(R.string.ui_settings_label_at_top_title),
                stringResource(R.string.ui_settings_grid_columns_title),
                stringResource(R.string.ui_settings_high_contrast_title),
                stringResource(R.string.board_settings_message_bar),
                stringResource(R.string.board_settings_activation),
                stringResource(R.string.board_settings_after_selection),
                "contrast", "symbols", "labels", "communication", "message bar",
                "speak and add", "return page"
            )
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Accessibility,
            title = accessibilityTitle,
            subtitle = stringResource(R.string.ui_settings_accessibility_desc),
            icon = Icons.Filled.Accessibility,
            iconContainerColor = Color(0xFFFFA8D8),
            iconColor = Color(0xFF700044),
            keywords = listOf(
                "touch", "timing", "feedback",
                stringResource(R.string.ui_settings_hold_to_select_title),
                stringResource(R.string.ui_settings_hold_to_select_desc),
                stringResource(R.string.ui_settings_dwell_to_select_title),
                stringResource(R.string.ui_settings_dwell_to_select_desc),
                stringResource(R.string.ui_settings_selection_sound_title),
                stringResource(R.string.ui_settings_auditory_fishing_title),
                stringResource(R.string.ui_settings_auditory_fishing_desc),
                "hold", "dwell", "hover", "sound", "whisper"
            )
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Sync,
            title = stringResource(R.string.ui_settings_sync_title),
            subtitle = stringResource(R.string.ui_settings_sync_desc),
            icon = Icons.Filled.Sync,
            iconContainerColor = Color(0xFF90CAF9),
            iconColor = Color(0xFF0D47A1),
            keywords = listOf("sync", "google drive", "onedrive", "folder", "cloud", "wingmate", "screens")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Privacy,
            title = privacyTitle,
            subtitle = stringResource(R.string.ui_settings_privacy_desc),
            icon = Icons.Filled.Security,
            iconContainerColor = Color(0xFFB8C4FF),
            iconColor = Color(0xFF263B80),
            keywords = listOf(
                stringResource(R.string.ui_settings_history_visible_title),
                stringResource(R.string.ui_settings_history_visible_desc),
                stringResource(R.string.ui_settings_feature_reporting_title),
                stringResource(R.string.ui_settings_feature_reporting_desc),
                stringResource(R.string.ui_settings_usage_logging_title),
                stringResource(R.string.ui_settings_usage_logging_desc),
                "history", "cache", "privacy", "analytics", "logging", "data"
            )
        ),
        SettingsCategoryItem(
            tab = SettingsTab.General,
            title = generalTitle,
            subtitle = stringResource(R.string.ui_settings_general_desc),
            icon = Icons.Filled.Storage,
            iconContainerColor = Color(0xFFA9D49A),
            iconColor = Color(0xFF1D4E18),
            keywords = listOf(
                stringResource(R.string.ui_settings_startup_mode_title),
                stringResource(R.string.ui_settings_startup_mode_desc),
                stringResource(R.string.ui_settings_startup_mode_keyboard),
                stringResource(R.string.ui_settings_startup_mode_screens),
                stringResource(R.string.ui_settings_startup_screen_title),
                stringResource(R.string.ui_settings_startup_screen_library),
                stringResource(R.string.ui_settings_symbols_title),
                stringResource(R.string.ui_settings_symbols_download_title),
                stringResource(R.string.ui_settings_symbols_download_desc),
                stringResource(R.string.ui_settings_symbols_download),
                stringResource(R.string.phrase_screen_welcome_screen),
                stringResource(R.string.backup_title),
                stringResource(R.string.backup_create),
                stringResource(R.string.backup_restore),
                "startup", "arasaac", "offline",
                "welcome", "boards", "screens", "backup", "restore"
            )
        )
    )
    val pronunciationItem = SettingsCategoryItem(
        tab = null,
        title = stringResource(R.string.dictionary_title),
        subtitle = stringResource(R.string.dictionary_description),
        icon = Icons.AutoMirrored.Filled.MenuBook,
        iconContainerColor = Color(0xFFB39DDB),
        iconColor = Color(0xFF4A148C),
        openPronunciation = true,
        keywords = listOf(
            "pronunciation", "dictionary", "phoneme", "ipa", "speech",
            stringResource(R.string.dictionary_word_label),
            stringResource(R.string.dictionary_phoneme_label),
            stringResource(R.string.dictionary_add_entry_title)
        )
    )

    val subSettings = listOf(
        // Speech
        SettingsCategoryItem(
            tab = SettingsTab.Speech,
            title = stringResource(R.string.ui_settings_tts_engine_group),
            subtitle = speechTitle,
            icon = Icons.Filled.RecordVoiceOver,
            iconContainerColor = Color(0xFF78D6F7),
            iconColor = Color(0xFF004E65),
            keywords = listOf("tts", "azure", "system tts", "engine", "endpoint", "subscription", "region", "key")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Speech,
            title = stringResource(R.string.phrase_screen_voice_settings),
            subtitle = speechTitle,
            icon = Icons.Filled.RecordVoiceOver,
            iconContainerColor = Color(0xFF78D6F7),
            iconColor = Color(0xFF004E65),
            keywords = listOf(
                stringResource(R.string.voice_select_title),
                stringResource(R.string.common_language),
                "voice", "language"
            )
        ),
        // Display
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(R.string.ui_settings_show_labels_title),
            subtitle = displayTitle,
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("labels", "grid", "layout")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(R.string.ui_settings_show_symbols_title),
            subtitle = displayTitle,
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("symbols", "images", "grid")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(R.string.ui_settings_label_at_top_title),
            subtitle = displayTitle,
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000)
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(R.string.ui_settings_grid_columns_title),
            subtitle = displayTitle,
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("grid", "columns", "layout")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(R.string.ui_settings_high_contrast_title),
            subtitle = displayTitle,
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("contrast", "accessibility")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(R.string.board_settings_message_bar),
            subtitle = stringResource(R.string.board_settings_global_message_bar_desc),
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("communication", "message", "sentence", "bar")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(R.string.board_settings_activation),
            subtitle = stringResource(R.string.board_settings_global_activation_desc),
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("communication", "speak", "add", "button", "activation")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(R.string.board_settings_after_selection),
            subtitle = stringResource(R.string.board_settings_global_return_desc),
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("communication", "navigation", "return", "previous", "start page")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(R.string.ui_settings_font_size),
            subtitle = displayTitle,
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("ui scaling", "font", "text size", "scale")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(R.string.ui_settings_playback_icons),
            subtitle = displayTitle,
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("ui scaling", "icons", "scale")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(R.string.ui_settings_category_chips),
            subtitle = displayTitle,
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("ui scaling", "category", "chips", "scale")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(R.string.ui_settings_buttons),
            subtitle = displayTitle,
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("ui scaling", "button", "scale")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Display,
            title = stringResource(R.string.ui_settings_input_fields),
            subtitle = displayTitle,
            icon = Icons.Filled.Tune,
            iconContainerColor = Color(0xFFFFB77F),
            iconColor = Color(0xFF6B3000),
            keywords = listOf("ui scaling", "input", "text field", "scale")
        ),
        // Accessibility
        SettingsCategoryItem(
            tab = SettingsTab.Accessibility,
            title = stringResource(R.string.ui_settings_hold_to_select_title),
            subtitle = stringResource(R.string.ui_settings_hold_to_select_desc),
            icon = Icons.Filled.Accessibility,
            iconContainerColor = Color(0xFFFFA8D8),
            iconColor = Color(0xFF700044),
            keywords = listOf("hold", "touch", "timing")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Accessibility,
            title = stringResource(R.string.ui_settings_dwell_to_select_title),
            subtitle = stringResource(R.string.ui_settings_dwell_to_select_desc),
            icon = Icons.Filled.Accessibility,
            iconContainerColor = Color(0xFFFFA8D8),
            iconColor = Color(0xFF700044),
            keywords = listOf("dwell", "hover", "timing")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Accessibility,
            title = stringResource(R.string.ui_settings_selection_sound_title),
            subtitle = accessibilityTitle,
            icon = Icons.Filled.Accessibility,
            iconContainerColor = Color(0xFFFFA8D8),
            iconColor = Color(0xFF700044),
            keywords = listOf("sound", "feedback")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Accessibility,
            title = stringResource(R.string.ui_settings_auditory_fishing_title),
            subtitle = stringResource(R.string.ui_settings_auditory_fishing_desc),
            icon = Icons.Filled.Accessibility,
            iconContainerColor = Color(0xFFFFA8D8),
            iconColor = Color(0xFF700044),
            keywords = listOf("whisper", "audio", "feedback")
        ),
        // Privacy
        SettingsCategoryItem(
            tab = SettingsTab.Privacy,
            title = stringResource(R.string.ui_settings_usage_logging_title),
            subtitle = stringResource(R.string.ui_settings_usage_logging_desc),
            icon = Icons.Filled.Security,
            iconContainerColor = Color(0xFFB8C4FF),
            iconColor = Color(0xFF263B80),
            keywords = listOf("obl", "logging", "clinical")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Privacy,
            title = stringResource(R.string.ui_settings_history_visible_title),
            subtitle = stringResource(R.string.ui_settings_history_visible_desc),
            icon = Icons.Filled.Security,
            iconContainerColor = Color(0xFFB8C4FF),
            iconColor = Color(0xFF263B80),
            keywords = listOf("history", "cache", "local data")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.Privacy,
            title = stringResource(R.string.ui_settings_feature_reporting_title),
            subtitle = stringResource(R.string.ui_settings_feature_reporting_desc),
            icon = Icons.Filled.Security,
            iconContainerColor = Color(0xFFB8C4FF),
            iconColor = Color(0xFF263B80),
            keywords = listOf(
                stringResource(R.string.ui_settings_analytics_title),
                "analytics", "privacy", "telemetry"
            )
        ),
        // General
        SettingsCategoryItem(
            tab = SettingsTab.General,
            title = stringResource(R.string.backup_title),
            subtitle = stringResource(R.string.backup_description),
            icon = Icons.Filled.Storage,
            iconContainerColor = Color(0xFFA9D49A),
            iconColor = Color(0xFF1D4E18),
            keywords = listOf("backup", "restore", "azure", "data")
        ),
        SettingsCategoryItem(
            tab = SettingsTab.General,
            title = stringResource(R.string.ui_settings_startup_mode_title),
            subtitle = stringResource(R.string.ui_settings_startup_mode_desc),
            icon = Icons.Filled.Storage,
            iconContainerColor = Color(0xFFA9D49A),
            iconColor = Color(0xFF1D4E18),
            keywords = listOf(
                stringResource(R.string.ui_settings_startup_mode_keyboard),
                stringResource(R.string.ui_settings_startup_mode_screens),
                stringResource(R.string.ui_settings_startup_screen_title),
                "startup", "open"
            )
        ),
        SettingsCategoryItem(
            tab = SettingsTab.General,
            title = stringResource(R.string.ui_settings_symbols_title),
            subtitle = stringResource(R.string.ui_settings_symbols_download_desc),
            icon = Icons.Filled.Storage,
            iconContainerColor = Color(0xFFA9D49A),
            iconColor = Color(0xFF1D4E18),
            keywords = listOf(
                stringResource(R.string.ui_settings_symbols_download_title),
                "arasaac", "offline", "download", "symbols"
            )
        ),
        SettingsCategoryItem(
            tab = SettingsTab.General,
            title = stringResource(R.string.phrase_screen_welcome_screen),
            subtitle = generalTitle,
            icon = Icons.Filled.Storage,
            iconContainerColor = Color(0xFFA9D49A),
            iconColor = Color(0xFF1D4E18),
            keywords = listOf("welcome", "onboarding")
        ),
        pronunciationItem
    )

    val normalizedQuery = query.trim().lowercase()
    val showingSearch = normalizedQuery.isNotEmpty()
    val results = if (!showingSearch) {
        categories + pronunciationItem
    } else {
        val matchedCategories = categories.filter { it.matches(normalizedQuery) }
        val matchedSubs = subSettings.filter { it.matches(normalizedQuery) }
        // Prefer specific sub-settings; keep matching categories that have no matching child title.
        val coveredTabs = matchedSubs.mapNotNull { it.tab }.toSet()
        val uncoveredCategories = matchedCategories.filter { it.tab !in coveredTabs }
        (matchedSubs + uncoveredCategories).distinctBy { "${it.tab}-${it.title}-${it.openPronunciation}" }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.ui_settings_search)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent
            )
        )

        if (results.isEmpty()) {
            Text(
                text = stringResource(R.string.ui_settings_search_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp)
            )
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                results.forEachIndexed { index, item ->
                    SettingsCategoryRow(
                        item = item,
                        selected = !showingSearch && if (item.openPronunciation) pronunciationSelected else item.tab == selectedTab,
                        onClick = {
                            when {
                                item.openPronunciation -> onOpenPronunciation()
                                item.tab != null -> onSelectCategory(item.tab)
                            }
                        }
                    )
                    if (index < results.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 88.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SettingsCategoryRow(
    item: SettingsCategoryItem,
    onClick: () -> Unit,
    /** Open beside the list: shown by a filled background and announced as selected. */
    selected: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            // Only the open category is marked: a plain list (phones) announces no selection state.
            .then(if (selected) Modifier.semantics { this.selected = true } else Modifier)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(52.dp),
            shape = CircleShape,
            color = item.iconContainerColor
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = item.iconColor,
                    modifier = Modifier.size(27.dp)
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                item.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
