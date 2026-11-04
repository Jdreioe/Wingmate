package io.github.jdreioe.wingmate.domain

/** One file of a Piper download, pinned by size and SHA-256 so a damaged or changed file is rejected. */
data class PiperFile(
    val name: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
)

/**
 * A downloadable Piper voice from Wingmate's pinned catalog. [licence] is the training
 * dataset's licence from the voice's model card, shown before download and kept for attribution.
 */
data class PiperVoicePack(
    val key: String,
    val languageTag: String,
    val speaker: String,
    val quality: String,
    val speakerCount: Int,
    val licence: String,
    val files: List<PiperFile>,
) {
    val sizeBytes: Long get() = files.sumOf { it.sizeBytes }

    /** The voice Wingmate selects and speaks with once this pack is installed. */
    fun toVoice(): Voice = Voice(
        name = PIPER_VOICE_PREFIX + key,
        displayName = "${speaker.replace('_', ' ').replaceFirstChar(Char::uppercaseChar)} (${quality.replace('_', '-')})",
        primaryLanguage = languageTag,
        supportedLanguages = listOf(languageTag),
        selectedLanguage = languageTag,
        provider = VoiceProvider.PIPER,
        providerVoiceName = key,
    )
}

private const val PIPER_VOICE_PREFIX = "piper|"

/** The catalog key of a Piper voice, or null when [this] is not one. */
val Voice.piperVoiceKey: String?
    get() = providerVoiceName?.takeIf { provider == VoiceProvider.PIPER }

/** Why a Piper download stopped. Each maps to one actionable message. */
enum class PiperDownloadFailure {
    /** The connection dropped or the server refused; downloaded data is kept for resuming. */
    Network,

    /** A file did not match its pinned checksum and was deleted. */
    Damaged,

    /** The device could not store the files. */
    Storage,
}

/** Download state of one catalog voice on this device. */
sealed interface PiperVoiceState {
    data object NotInstalled : PiperVoiceState

    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : PiperVoiceState

    /**
     * A download stopped before finishing. Kept bytes are resumed by downloading again.
     * [failure] is null when the app closed mid-download.
     */
    data class Incomplete(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val failure: PiperDownloadFailure?,
    ) : PiperVoiceState

    data object Installed : PiperVoiceState
}
