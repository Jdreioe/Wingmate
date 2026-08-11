package io.github.jdreioe.wingmate.domain.obf

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

const val OBF_MATH_MODE_EXTENSION = "ext_wingmate_math_mode"
const val OBF_BUTTON_TYPE_EXTENSION = "ext_wingmate_button_type"
const val OBF_COMPACT_GRID_EXTENSION = "ext_wingmate_compact_grid"
const val OBF_GRID_HEIGHT_FRACTION_EXTENSION = "ext_wingmate_grid_height_fraction"
const val OBF_SPELLING_MODE_EXTENSION = "ext_wingmate_spelling_mode"
const val OBF_KEYBOARD_EXTENSION = "ext_wingmate_keyboard"
const val OBF_BUTTON_STYLE_EXTENSION = "ext_wingmate_button_style"

/**
 * Wingmate-specific button behaviours, stored in OBF extensions for portability.
 */
enum class ObfButtonType(val wireValue: String) {
    Standard("standard"),
    NGramPrediction("ngram_prediction")
}

/**
 * Visual shapes a button field can render as. Stored per-button in the
 * `ext_wingmate_button_style` extension so it round-trips through OBF/OBZ and
 * falls back to the default ([Square]) for consumers that do not know a value.
 */
enum class ObfButtonShape(val wireValue: String) {
    Rounded("rounded"),
    Square("square"),
    Pill("pill"),
    Speech("speech"),
    Thought("thought")
}

/**
 * Keyboard page kinds, stored in the board-level `ext_wingmate_keyboard`
 * extension so keyboard boards can be recognized explicitly (instead of
 * inferred from spelling mode or id prefixes) and round-trip through OBF/OBZ.
 */
enum class ObfKeyboardLayout(val wireValue: String) {
    Qwerty("qwerty"),
    Alphabetical("alphabetical"),
    Symbols("symbols")
}

@Serializable
data class ObfBoard(
    val format: String,
    val id: String,
    val locale: String? = null,
    val url: String? = null,
    val name: String? = null,
    @SerialName("description_html")
    val descriptionHtml: String? = null,
    @SerialName("background_color")
    val backgroundColor: String? = null,
    val buttons: List<ObfButton> = emptyList(),
    val images: List<ObfImage> = emptyList(),
    val grid: ObfGrid? = null,
    val sounds: List<ObfSound> = emptyList(),
    val strings: Map<String, Map<String, String>> = emptyMap(),
    val license: ObfLicense? = null,
    val extensions: Map<String, JsonElement> = emptyMap()
) {
    val isAbsoluteLayout: Boolean
        get() = buttons.isNotEmpty() && buttons.all {
            it.top != null && it.left != null && it.width != null && it.height != null
        }

    /** Keeps keyboard-style grids at touch-friendly key heights instead of stretching to the page. */
    val compactGrid: Boolean
        get() = (extensions[OBF_COMPACT_GRID_EXTENSION] as? JsonPrimitive)?.booleanOrNull == true

    fun withCompactGrid(enabled: Boolean): ObfBoard = copy(
        extensions = if (enabled) {
            extensions + (OBF_COMPACT_GRID_EXTENSION to JsonPrimitive(true))
        } else {
            extensions - OBF_COMPACT_GRID_EXTENSION
        }
    )

    /** Optional editor-controlled share of the available board area used by a grid. */
    val gridHeightFraction: Float?
        get() = (extensions[OBF_GRID_HEIGHT_FRACTION_EXTENSION] as? JsonPrimitive)
            ?.contentOrNull
            ?.toFloatOrNull()
            ?.coerceIn(0.15f, 1f)

    fun withGridHeightFraction(fraction: Float?): ObfBoard = copy(
        extensions = fraction?.let {
            extensions + (OBF_GRID_HEIGHT_FRACTION_EXTENSION to JsonPrimitive(it.coerceIn(0.15f, 1f)))
        } ?: (extensions - OBF_GRID_HEIGHT_FRACTION_EXTENSION)
    )

    /**
     * Spelling boards (e.g. the keyboard preset) compose characters/predictions
     * without auto-inserting a space between tokens; word boards join words by space.
     */
    val spellingMode: Boolean
        get() = (extensions[OBF_SPELLING_MODE_EXTENSION] as? JsonPrimitive)?.booleanOrNull == true

    fun withSpellingMode(enabled: Boolean): ObfBoard = copy(
        extensions = if (enabled) {
            extensions + (OBF_SPELLING_MODE_EXTENSION to JsonPrimitive(true))
        } else {
            extensions - OBF_SPELLING_MODE_EXTENSION
        }
    )

    /**
     * The keyboard page kind, or `null` for non-keyword boards. Presence of this
     * extension is the authoritative way to identify a keyboard board; it is
     * preserved by `copy` and therefore by duplicate/import flows.
     */
    val keyboardLayout: ObfKeyboardLayout?
        get() = (extensions[OBF_KEYBOARD_EXTENSION] as? JsonPrimitive)?.contentOrNull
            ?.let { value -> ObfKeyboardLayout.entries.firstOrNull { it.wireValue == value } }

    val isKeyboard: Boolean
        get() = keyboardLayout != null

    fun withKeyboardLayout(layout: ObfKeyboardLayout?): ObfBoard = copy(
        extensions = layout?.let {
            extensions + (OBF_KEYBOARD_EXTENSION to JsonPrimitive(it.wireValue))
        } ?: (extensions - OBF_KEYBOARD_EXTENSION)
    )
}

@Serializable
data class ObfButton(
    val id: String,
    val label: String? = null,
    val vocalization: String? = null,
    val locale: String? = null,
    @SerialName("image_id")
    val imageId: String? = null,
    @SerialName("sound_id")
    val soundId: String? = null,
    @SerialName("background_color")
    val backgroundColor: String? = null,
    @SerialName("border_color")
    val borderColor: String? = null,
    val top: Double? = null,
    val left: Double? = null,
    val width: Double? = null,
    val height: Double? = null,
    @SerialName("load_board")
    val loadBoard: ObfLoadBoard? = null,
    val action: String? = null,
    val actions: List<String> = emptyList(),
    val hidden: Boolean = false,
    val extensions: Map<String, JsonElement> = emptyMap()
) {
    val mathMode: Boolean
        get() = (extensions[OBF_MATH_MODE_EXTENSION] as? JsonPrimitive)?.booleanOrNull == true

    val type: ObfButtonType
        get() = ObfButtonType.entries.firstOrNull {
            it.wireValue == (extensions[OBF_BUTTON_TYPE_EXTENSION] as? JsonPrimitive)?.contentOrNull
        } ?: ObfButtonType.Standard

    fun withMathMode(enabled: Boolean): ObfButton = copy(
        extensions = if (enabled) {
            extensions + (OBF_MATH_MODE_EXTENSION to JsonPrimitive(true))
        } else {
            extensions - OBF_MATH_MODE_EXTENSION
        }
    )

    fun withType(type: ObfButtonType): ObfButton = copy(
        extensions = if (type == ObfButtonType.Standard) {
            extensions - OBF_BUTTON_TYPE_EXTENSION
        } else {
            extensions + (OBF_BUTTON_TYPE_EXTENSION to JsonPrimitive(type.wireValue))
        }
    )

    /**
     * The visual shape of this button, falling back to [ObfButtonShape.Square]
     * when the extension is missing, non-string, or holds an unknown value so
     * forward-compatible OBF/OBZ imports never crash.
     */
    val shape: ObfButtonShape
        get() = ObfButtonShape.entries.firstOrNull {
            it.wireValue == (extensions[OBF_BUTTON_STYLE_EXTENSION] as? JsonPrimitive)?.contentOrNull
        } ?: ObfButtonShape.Square

    fun withShape(shape: ObfButtonShape): ObfButton = copy(
        extensions = if (shape == ObfButtonShape.Square) {
            extensions - OBF_BUTTON_STYLE_EXTENSION
        } else {
            extensions + (OBF_BUTTON_STYLE_EXTENSION to JsonPrimitive(shape.wireValue))
        }
    )

    fun resolvedActions(): List<String> =
        if (actions.isNotEmpty()) actions else listOfNotNull(action?.takeIf { it.isNotBlank() })
}

@Serializable
data class ObfGrid(
    val rows: Int,
    val columns: Int,
    val order: List<List<String?>>
)

@Serializable
data class ObfImage(
    val id: String,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("content_type")
    val contentType: String? = null,
    val url: String? = null,
    val path: String? = null,
    val data: String? = null,
    @SerialName("data_url")
    val dataUrl: String? = null,
    val symbol: ObfSymbol? = null,
    val license: ObfLicense? = null,
    val extensions: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class ObfSymbol(
    val set: String? = null,
    val filename: String? = null,
    @SerialName("library_key")
    val libraryKey: String? = null
)

@Serializable
data class ObfSound(
    val id: String,
    @SerialName("content_type")
    val contentType: String? = null,
    val url: String? = null,
    val path: String? = null,
    val data: String? = null,
    @SerialName("data_url")
    val dataUrl: String? = null,
    val license: ObfLicense? = null,
    val extensions: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class ObfLoadBoard(
    val id: String? = null,
    val name: String? = null,
    val url: String? = null,
    val path: String? = null,
    @SerialName("data_url")
    val dataUrl: String? = null,
    val extensions: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class ObfLicense(
    val type: String? = null,
    @SerialName("copyright_notice_url")
    val copyrightNoticeUrl: String? = null,
    @SerialName("source_url")
    val sourceUrl: String? = null,
    @SerialName("author_name")
    val authorName: String? = null,
    @SerialName("author_url")
    val authorUrl: String? = null,
    @SerialName("author_email")
    val authorEmail: String? = null,
    val extensions: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class ObfManifest(
    val format: String,
    val root: String,
    val paths: ObfManifestPaths,
    val extensions: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class ObfManifestPaths(
    val boards: Map<String, String> = emptyMap(),
    val images: Map<String, String> = emptyMap(),
    val sounds: Map<String, String> = emptyMap(),
    val extensions: Map<String, JsonElement> = emptyMap()
)
