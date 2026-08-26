package io.github.jdreioe.wingmate.domain.obf

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

const val OBF_PAGE_SETTINGS_EXTENSION = "ext_wingmate_page_settings"
const val OBF_SCREEN_SETTINGS_EXTENSION = "ext_wingmate_screen_settings"

@Serializable
enum class BoardActivationBehavior {
    @SerialName("speak_and_add")
    SpeakAndAdd,

    @SerialName("add_only")
    AddOnly,

    @SerialName("speak_only")
    SpeakOnly
}

@Serializable
enum class BoardReturnBehavior {
    @SerialName("stay")
    Stay,

    @SerialName("previous")
    Previous,

    @SerialName("start_page")
    StartPage
}

/**
 * Nullable settings used at both inheritance levels:
 * Screen values inherit from the app, while Page values inherit from the Screen.
 */
@Serializable
data class BoardSettingsOverrides(
    val showLabels: Boolean? = null,
    val showSymbols: Boolean? = null,
    val labelAtTop: Boolean? = null,
    val showMessageBar: Boolean? = null,
    val messageBarEditable: Boolean? = null,
    val activationBehavior: BoardActivationBehavior? = null,
    val returnBehavior: BoardReturnBehavior? = null
) {
    val isEmpty: Boolean
        get() = showLabels == null &&
            showSymbols == null &&
            labelAtTop == null &&
            showMessageBar == null &&
            messageBarEditable == null &&
            activationBehavior == null &&
            returnBehavior == null
}

data class ResolvedBoardSettings(
    val showLabels: Boolean,
    val showSymbols: Boolean,
    val labelAtTop: Boolean,
    val showMessageBar: Boolean,
    val messageBarEditable: Boolean,
    val activationBehavior: BoardActivationBehavior,
    val returnBehavior: BoardReturnBehavior
)

fun resolveBoardSettings(
    appShowLabels: Boolean,
    appShowSymbols: Boolean,
    appLabelAtTop: Boolean,
    appShowMessageBar: Boolean = true,
    appMessageBarEditable: Boolean = true,
    appActivationBehavior: BoardActivationBehavior = BoardActivationBehavior.SpeakAndAdd,
    appReturnBehavior: BoardReturnBehavior = BoardReturnBehavior.Stay,
    screen: BoardSettingsOverrides = BoardSettingsOverrides(),
    page: BoardSettingsOverrides = BoardSettingsOverrides()
): ResolvedBoardSettings {
    var showLabels = page.showLabels ?: screen.showLabels ?: appShowLabels
    val showSymbols = page.showSymbols ?: screen.showSymbols ?: appShowSymbols

    // Imported extensions can contain an unusable combination even though the
    // Wingmate editor prevents it. Always leave at least one communication cue.
    if (!showLabels && !showSymbols) showLabels = true

    return ResolvedBoardSettings(
        showLabels = showLabels,
        showSymbols = showSymbols,
        labelAtTop = page.labelAtTop ?: screen.labelAtTop ?: appLabelAtTop,
        showMessageBar = page.showMessageBar ?: screen.showMessageBar ?: appShowMessageBar,
        messageBarEditable = page.messageBarEditable ?: screen.messageBarEditable ?: appMessageBarEditable,
        activationBehavior = page.activationBehavior
            ?: screen.activationBehavior
            ?: appActivationBehavior,
        returnBehavior = page.returnBehavior
            ?: screen.returnBehavior
            ?: appReturnBehavior
    )
}

fun ObfBoard.pageSettingsOverrides(): BoardSettingsOverrides =
    decodeBoardSettings(extensions[OBF_PAGE_SETTINGS_EXTENSION]) ?: BoardSettingsOverrides()

fun ObfBoard.withPageSettingsOverrides(settings: BoardSettingsOverrides): ObfBoard = copy(
    extensions = if (settings.isEmpty) {
        extensions - OBF_PAGE_SETTINGS_EXTENSION
    } else {
        extensions + (OBF_PAGE_SETTINGS_EXTENSION to encodeBoardSettings(settings))
    }
)

fun encodeBoardSettings(settings: BoardSettingsOverrides): JsonElement =
    boardSettingsJson.encodeToJsonElement(settings)

fun decodeBoardSettings(element: JsonElement?): BoardSettingsOverrides? {
    if (element == null) return null
    return runCatching {
        boardSettingsJson.decodeFromJsonElement(BoardSettingsOverrides.serializer(), element)
    }.getOrNull()
}

private val boardSettingsJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = false
}
