package io.github.jdreioe.wingmate.domain

import io.github.jdreioe.wingmate.domain.obf.BoardActivationBehavior
import io.github.jdreioe.wingmate.domain.obf.BoardReturnBehavior
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How selections speak while the user composes, applied as a global access
 * preference across every native client.
 *
 * - [Immediate]: each applicable selection (phrase, board button, recording)
 *   speaks as it is selected.
 * - [SentenceOnly]: composition is silent; speech happens only when the
 *   constructed sentence is explicitly activated (the [`:speak` action] or the
 *   sentence-bar speak button). Helpers such as auditory fishing, button
 *   previews, and scanning prompts are documented exceptions and still speak.
 */
@Serializable
enum class SpeechPolicy {
    @SerialName("immediate")
    Immediate,

    @SerialName("sentence_only")
    SentenceOnly
}

@Serializable
data class Phrase(
    val id: String,
    val text: String,                   // Display label (maps to OBF label)
    val name: String? = null,           // Vocalization - what to speak if different from text (maps to OBF vocalization)
    val backgroundColor: String? = null, // Background and border color
    val imageUrl: String? = null,       // Image URL for visual display
    val parentId: String? = null,       // Parent category ID
    val linkedBoardId: String? = null,  // Link to another board (implements category/folder functionality)
    val createdAt: Long,
    // Optional local recording path for this phrase (platform-specific file path)
    val recordingPath: String? = null,
    // Layout preference: if true, this item (even if it is a folder) appears in the grid.
    // If null, defaults to true for items (linkedBoardId == null) and false for folders (linkedBoardId != null).
    val isGridItem: Boolean? = null,
    val isHidden: Boolean = false
)

@Serializable
data class CategoryItem(
    val id: String,
    val name: String? = null,
    val isFolder: Boolean = false,
    // selected language for this category (one of the supported languages for the selected voice)
    val selectedLanguage: String? = null
)

@Serializable
enum class StartupMode {
    Keyboard,
    Screens
}

@Serializable
enum class TtsEngine {
    SYSTEM,
    AZURE_USER_RESOURCE,
    AZURE_MANAGED
}

@Serializable
enum class PointerEmphasisStyle {
    System,
    Ring,
    Outline
}

@Serializable
enum class WordTypeColorScheme {
    None,
    Fitzgerald
}

@Serializable
data class Settings(
    val language: String = "en-US",
    val voice: String = "default",
    val speechRate: Float = 1.0f,
    // The primary locale is used by default. An empty secondary locale means it is disabled.
    val primaryLanguage: String = "en-US",
    val secondaryLanguage: String = "",
    // TTS engine selection
    val ttsEngine: TtsEngine = TtsEngine.SYSTEM,
    // Desktop (Linux) only: when true, route TTS audio to a virtual sink whose monitor can be used as a microphone in apps like Zoom
    val virtualMicEnabled: Boolean = false,
    // Auto-update settings
    val autoUpdateEnabled: Boolean = true,
    val checkUpdateInterval: Long = 24 * 60 * 60 * 1000L, // 24 hours in milliseconds
    val lastUpdateCheck: Long = 0L,
    // UI scaling settings (multipliers)
    val fontSizeScale: Float = 1.0f,
    val playbackIconScale: Float = 1.0f,
    val categoryChipScale: Float = 1.0f,
    val buttonScale: Float = 1.0f,
    val inputFieldScale: Float = 1.0f,
    // Theme settings (for hot theme switching)
    val forceDarkTheme: Boolean? = null, // null = follow system, true = dark, false = light
    val primaryColor: String? = null, // hex color for custom primary color
    val useCustomColors: Boolean = false, // enable custom color theming
    // Welcome flow completion
    val welcomeFlowCompleted: Boolean = false,
    // Screen shown after launch once onboarding has been completed.
    val startupMode: StartupMode = StartupMode.Keyboard,
    // Optional screen set opened directly when startupMode is Screens.
    val startupBoardSetId: String? = null,
    // Partner window display (TD-I13 via FTDI FT232H) — desktop only
    val partnerWindowEnabled: Boolean = false,
    // EVE ROM font index (16-34); 31 = largest standard ROM font
    val partnerWindowFontSize: Int = 31,
    // Number of text lines to show (1-4); word-wrapping is done in software
    val partnerWindowMaxLines: Int = 2,
    // Show idle face on partner window after 10s of no text input
    val partnerWindowIdleEnabled: Boolean = true,
    // On-screen keyboard scale (0.5 = half, 1.0 = normal, 2.0 = double)
    val oskKeyboardScale: Float = 1.0f,
    // Optional product analytics (Aptabase on Android). Default is opt-out.
    val featureUsageReportingEnabled: Boolean = false,
    // Accessibility settings (OpenAAC)
    val showLabels: Boolean = true,
    val showSymbols: Boolean = true,
    val labelAtTop: Boolean = false,
    // Global defaults inherited by Screens and then Pages.
    val boardShowMessageBar: Boolean = true,
    val boardActivationBehavior: BoardActivationBehavior = BoardActivationBehavior.SpeakAndAdd,
    val boardReturnBehavior: BoardReturnBehavior = BoardReturnBehavior.Stay,
    val holdToSelectMillis: Long = 0,
    val gridColumns: Int = 3,
    val highContrastMode: Boolean = false,
    // Automatically color vocabulary buttons by grammatical word type. Explicit
    // author colors always take precedence.
    val wordTypeColorScheme: WordTypeColorScheme = WordTypeColorScheme.None,
    val dwellToSelectMillis: Long = 0,
    // Interaction shortcuts use portable tokens such as "Space", "Enter", or "F8".
    // Empty disables the shortcut so ordinary typing is never intercepted by default.
    val selectKeyBinding: String = "",
    val restModeKeyBinding: String = "",
    val pointerEmphasisStyle: PointerEmphasisStyle = PointerEmphasisStyle.System,
    val pointerEmphasisScale: Float = 1.5f,
    val selectionSoundEnabled: Boolean = false,
    val auditoryFishingEnabled: Boolean = false,
    // #119: immediate speech on each selection versus keeping composition silent
    // until the constructed sentence is activated.
    val speechPolicy: SpeechPolicy = SpeechPolicy.Immediate,
    // #118: ignore repeated activations of the same target inside this window (ms). 0 disables.
    val selectionDebounceMillis: Long = 0,
    // #120: show a time-bounded visual highlight on the last selected target (ms). 0 disables.
    val selectionHighlightMillis: Long = 0,
    val usageLoggingEnabled: Boolean = false,
    // Controls whether cached speech history is exposed in the UI. Recording and
    // local cache reuse continue when this is false.
    val historyVisible: Boolean = true,
    // Switch-scanning configuration used by the native iOS accessibility UI.
    val scanningEnabled: Boolean = false,
    val scanPlaybackAreaEnabled: Boolean = true,
    val scanInputFieldEnabled: Boolean = true,
    val scanPhraseGridEnabled: Boolean = true,
    val scanCategoryItemsEnabled: Boolean = true,
    val scanTopBarEnabled: Boolean = true,
    val scanPhraseGridOrder: String = "row-major",
    val scanDwellTimeSeconds: Float = 1.0f,
    val scanAutoAdvanceSeconds: Float = 1.2f
)

@Serializable
data class AppVersion(
    val version: String,
    val major: Int,
    val minor: Int,
    val patch: Int
) {
    fun isNewerThan(other: AppVersion): Boolean {
        return when {
            major > other.major -> true
            major < other.major -> false
            minor > other.minor -> true
            minor < other.minor -> false
            patch > other.patch -> true
            else -> false
        }
    }
    
    companion object {
        fun parse(versionString: String): AppVersion {
            val cleanVersion = versionString.removePrefix("v")
            val parts = cleanVersion.split(".")
            val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
            return AppVersion(cleanVersion, major, minor, patch)
        }
    }
}

@Serializable
data class UpdateInfo(
    val version: AppVersion,
    val downloadUrl: String,
    val releaseNotes: String,
    val publishedAt: String,
    val assetName: String,
    val assetSize: Long
)

enum class UpdateStatus {
    CHECKING,
    AVAILABLE,
    DOWNLOADING,
    DOWNLOADED,
    INSTALLING,
    UP_TO_DATE,
    ERROR
}
