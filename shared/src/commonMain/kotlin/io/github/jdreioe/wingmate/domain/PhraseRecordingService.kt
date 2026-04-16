package io.github.jdreioe.wingmate.domain

interface PhraseRecordingService {
    val isSupported: Boolean
        get() = false

    fun isRecording(): Boolean = false

    suspend fun startRecording(phraseIdHint: String? = null): Result<String> {
        return Result.failure(UnsupportedOperationException("Phrase recording is not supported on this platform."))
    }

    suspend fun stopRecording(): Result<String> {
        return Result.failure(UnsupportedOperationException("Phrase recording is not supported on this platform."))
    }
}
