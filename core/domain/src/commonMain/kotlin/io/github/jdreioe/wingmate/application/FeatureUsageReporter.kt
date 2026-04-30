package io.github.jdreioe.wingmate.application

/**
 * Minimal abstraction for feature usage reporting.
 *
 * Implementations must avoid sending sensitive data. Callers should only
 * provide metadata such as action names, result status, and coarse counts.
 */
interface FeatureUsageReporter {
    /**
     * Enable/disable telemetry collection.
     */
    fun setEnabled(enabled: Boolean) {}

    /**
     * Report a single feature usage event.
     */
    fun report(event: String, metadata: Map<String, String> = emptyMap())
}

class NoopFeatureUsageReporter : FeatureUsageReporter {
    override fun report(event: String, metadata: Map<String, String>) = Unit
}

object FeatureUsageEvents {
    const val APP_STARTED = "app_started"
    const val SCREEN_VIEW = "screen_view"
    const val WELCOME_COMPLETED = "welcome_completed"

    const val PLAYBACK_PLAY = "playback_play"
    const val PLAYBACK_PAUSE = "playback_pause"
    const val PLAYBACK_RESUME = "playback_resume"
    const val PLAYBACK_STOP = "playback_stop"
    const val PLAYBACK_SECONDARY_TOGGLE = "playback_secondary_toggle"
    const val PLAYBACK_ON_THAT_THOUGHT = "playback_on_that_thought"
    const val FULLSCREEN_TOGGLE = "fullscreen_toggle"

    const val PHRASE_ADDED = "phrase_added"
    const val PHRASE_EDITED = "phrase_edited"
    const val PHRASE_DELETED = "phrase_deleted"
    const val PHRASE_MOVED = "phrase_moved"
    const val PHRASE_PLAYED = "phrase_played"
    const val PHRASE_PLAYED_SECONDARY = "phrase_played_secondary"
    const val PHRASE_INSERTED = "phrase_inserted"

    const val CATEGORY_ADDED = "category_added"
    const val CATEGORY_DELETED = "category_deleted"
    const val CATEGORY_MOVED = "category_moved"

    const val VOICE_SELECTED = "voice_selected"
    const val VOICE_REFRESHED = "voice_refreshed"
    const val LANGUAGE_UPDATED = "language_updated"
    const val SETTINGS_UPDATED = "settings_updated"
    const val ANALYTICS_CONSENT_CHANGED = "analytics_consent_changed"

    const val BOARD_IMPORT_STARTED = "board_import_started"
    const val BOARD_IMPORT_COMPLETED = "board_import_completed"
    const val BOARD_IMPORT_FAILED = "board_import_failed"
    const val BOARD_SETUP_CHOICE = "board_setup_choice"

    const val BOARDSET_CREATED = "boardset_created"
    const val BOARDSET_LOCK_TOGGLED = "boardset_lock_toggled"
    const val BOARDSET_TOUCHED = "boardset_touched"
    const val BOARD_CREATED = "board_created"
    const val BOARD_CELL_UPSERTED = "board_cell_upserted"
    const val BOARD_CELL_CLEARED = "board_cell_cleared"

    const val DICTIONARY_ENTRY_ADDED = "dictionary_entry_added"
    const val DICTIONARY_ENTRY_DELETED = "dictionary_entry_deleted"
}

fun FeatureUsageReporter.reportEvent(
    event: String,
    vararg metadata: Pair<String, String?>
) {
    val sanitizedMetadata = metadata
        .mapNotNull { (key, value) ->
            val cleanKey = key.trim()
            val cleanValue = value?.trim()
            if (cleanKey.isBlank() || cleanValue.isNullOrBlank()) null else cleanKey to cleanValue
        }
        .toMap()
    report(event, sanitizedMetadata)
}
