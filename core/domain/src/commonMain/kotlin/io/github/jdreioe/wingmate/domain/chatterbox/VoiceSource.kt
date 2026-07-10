package io.github.jdreioe.wingmate.domain.chatterbox

sealed interface VoiceSource {
    data class MicrophoneRecording(
        val filePath: String,
        val durationMs: Long,
    ) : VoiceSource

    data class ImportedAudio(
        val filePath: String,
        val durationMs: Long? = null,
    ) : VoiceSource

    data class ImportedProfile(
        val filePath: String,
    ) : VoiceSource
}