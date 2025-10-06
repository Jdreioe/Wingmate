package io.github.jdreioe.wingmate.domain

import kotlinx.serialization.Serializable

@Serializable
data class Phrase(
    val id: String,
    val text: String,
    val name: String? = null,
    val backgroundColor: String? = null,
    val parentId: String? = null,
    val isCategory: Boolean = false,
    val createdAt: Long,
    // Optional local recording path for this phrase (platform-specific file path)
    val recordingPath: String? = null
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
data class Settings(
    val language: String = "en",
    val voice: String = "default",
    val speechRate: Float = 1.0f,
    // UI-level settings: primary and secondary locales used by the UI language selector
    val primaryLanguage: String = "en-US",
    val secondaryLanguage: String = "en-US",
    // TTS preference: true = use system TTS, false = use Azure TTS
    val useSystemTts: Boolean = false,
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
    val useCustomColors: Boolean = false // enable custom color theming
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
