package io.github.jdreioe.wingmate.domain.obf

import io.github.jdreioe.wingmate.domain.SpeechPolicy
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

/** What one of the up to four buttons beside a Screen's message bar does. */
@Serializable
enum class MessageBarAction {
    /** Speaks the Message; stops speech while it plays. */
    @SerialName("speak")
    Speak,

    /** Parks the Message, or swaps it with the parked one. */
    @SerialName("hold")
    Hold,

    @SerialName("delete_last")
    DeleteLast,

    @SerialName("clear")
    Clear,
}

const val MAX_MESSAGE_BAR_ACTIONS = 4
const val MAX_KEYGUARD_CELL_GAP_DP = 48
const val MAX_KEYGUARD_OUTER_MARGIN_DP = 64
const val MIN_KEYGUARD_CELL_SIZE_DP = 24.0

/**
 * Viewport-constrained keyguard spacing in dp/pt.
 *
 * Stored Screen/Page overrides remain untouched (for tablets and OBZ exports), while
 * narrow viewports clamp effective margin and gaps so Buttons never collapse to zero size.
 */
data class EffectiveKeyguardSpacing(
    val horizontalGapDp: Double,
    val verticalGapDp: Double,
    val outerMarginDp: Double,
)

fun effectiveKeyguardSpacing(
    viewportWidthDp: Double,
    viewportHeightDp: Double,
    columns: Int,
    rows: Int,
    desiredHorizontalGapDp: Double,
    desiredVerticalGapDp: Double,
    desiredOuterMarginDp: Double,
    minimumCellSizeDp: Double = MIN_KEYGUARD_CELL_SIZE_DP,
): EffectiveKeyguardSpacing {
    val safeCols = columns.coerceAtLeast(1)
    val safeRows = rows.coerceAtLeast(1)
    val clampedMargin = desiredOuterMarginDp.coerceAtLeast(0.0)
    val clampedHGap = desiredHorizontalGapDp.coerceAtLeast(0.0)
    val clampedVGap = desiredVerticalGapDp.coerceAtLeast(0.0)
    if (viewportWidthDp <= 0.0 || viewportHeightDp <= 0.0) {
        return EffectiveKeyguardSpacing(
            horizontalGapDp = clampedHGap,
            verticalGapDp = clampedVGap,
            outerMarginDp = clampedMargin,
        )
    }

    val minCell = minimumCellSizeDp.coerceAtLeast(1.0)
    val maxMarginX = ((viewportWidthDp - minCell * safeCols) / 2.0).coerceAtLeast(0.0)
    val maxMarginY = ((viewportHeightDp - minCell * safeRows) / 2.0).coerceAtLeast(0.0)
    val effectiveMargin = minOf(clampedMargin, maxMarginX, maxMarginY)

    val availableWidth = (viewportWidthDp - effectiveMargin * 2.0).coerceAtLeast(0.0)
    val availableHeight = (viewportHeightDp - effectiveMargin * 2.0).coerceAtLeast(0.0)
    val effectiveHGap = if (safeCols > 1) {
        val maxGapX = ((availableWidth - minCell * safeCols) / (safeCols - 1)).coerceAtLeast(0.0)
        minOf(clampedHGap, maxGapX)
    } else {
        clampedHGap
    }
    val effectiveVGap = if (safeRows > 1) {
        val maxGapY = ((availableHeight - minCell * safeRows) / (safeRows - 1)).coerceAtLeast(0.0)
        minOf(clampedVGap, maxGapY)
    } else {
        clampedVGap
    }
    return EffectiveKeyguardSpacing(
        horizontalGapDp = effectiveHGap,
        verticalGapDp = effectiveVGap,
        outerMarginDp = effectiveMargin,
    )
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
    val showSpeakButton: Boolean? = null,
    val messageBarEditable: Boolean? = null,
    val activationBehavior: BoardActivationBehavior? = null,
    val returnBehavior: BoardReturnBehavior? = null,
    /** Prepared speeches can speak directly without changing the app-wide preference. */
    val speechPolicy: SpeechPolicy? = null,
    /** Generate cloud speech for every Button ahead of use. Off unless explicitly enabled. */
    val cacheSpeech: Boolean? = null,
    /**
     * The buttons beside the message bar, in order. Replaces [showSpeakButton]
     * once set; until then the speak setting decides between Speak and none.
     */
    val messageBarActions: List<MessageBarAction>? = null,
    /** Physical keyguard gap between Buttons in dp/pt. Null inherits from Screen or default layout. */
    val cellGapDp: Int? = null,
    /** Physical keyguard outer margin around the Page grid in dp/pt. Null inherits from Screen or default layout. */
    val outerMarginDp: Int? = null,
) {
    val isEmpty: Boolean
        get() = showLabels == null &&
            showSymbols == null &&
            labelAtTop == null &&
            showMessageBar == null &&
            showSpeakButton == null &&
            messageBarEditable == null &&
            activationBehavior == null &&
            returnBehavior == null &&
            speechPolicy == null &&
            cacheSpeech == null &&
            messageBarActions == null &&
            cellGapDp == null &&
            outerMarginDp == null
}

data class ResolvedBoardSettings(
    val showLabels: Boolean,
    val showSymbols: Boolean,
    val labelAtTop: Boolean,
    val showMessageBar: Boolean,
    val showSpeakButton: Boolean,
    val messageBarEditable: Boolean,
    val activationBehavior: BoardActivationBehavior,
    val returnBehavior: BoardReturnBehavior,
    val speechPolicy: SpeechPolicy? = null,
    val cacheSpeech: Boolean = false,
    val messageBarActions: List<MessageBarAction>,
    val cellGapDp: Int? = null,
    val outerMarginDp: Int? = null,
)

fun resolveBoardSettings(
    appShowLabels: Boolean,
    appShowSymbols: Boolean,
    appLabelAtTop: Boolean,
    appShowMessageBar: Boolean = true,
    appShowSpeakButton: Boolean = true,
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

    val legacyShowSpeakButton = page.showSpeakButton ?: screen.showSpeakButton ?: appShowSpeakButton
    val messageBarActions = (page.messageBarActions ?: screen.messageBarActions)
        ?.distinct()
        ?.take(MAX_MESSAGE_BAR_ACTIONS)
        ?: if (legacyShowSpeakButton) listOf(MessageBarAction.Speak) else emptyList()

    return ResolvedBoardSettings(
        showLabels = showLabels,
        showSymbols = showSymbols,
        labelAtTop = page.labelAtTop ?: screen.labelAtTop ?: appLabelAtTop,
        showMessageBar = page.showMessageBar ?: screen.showMessageBar ?: appShowMessageBar,
        // Clients that only know the speak toggle follow the button list.
        showSpeakButton = MessageBarAction.Speak in messageBarActions,
        messageBarEditable = page.messageBarEditable ?: screen.messageBarEditable ?: appMessageBarEditable,
        activationBehavior = page.activationBehavior
            ?: screen.activationBehavior
            ?: appActivationBehavior,
        returnBehavior = page.returnBehavior
            ?: screen.returnBehavior
            ?: appReturnBehavior,
        speechPolicy = page.speechPolicy ?: screen.speechPolicy,
        cacheSpeech = page.cacheSpeech ?: screen.cacheSpeech ?: false,
        messageBarActions = messageBarActions,
        cellGapDp = (page.cellGapDp ?: screen.cellGapDp)?.coerceIn(0, MAX_KEYGUARD_CELL_GAP_DP),
        outerMarginDp = (page.outerMarginDp ?: screen.outerMarginDp)?.coerceIn(0, MAX_KEYGUARD_OUTER_MARGIN_DP),
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
