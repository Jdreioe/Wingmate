package io.github.jdreioe.wingmate.platform

actual class AudioRecorder {
    actual suspend fun start(outputPath: String): Result<Unit> {
        throw UnsupportedOperationException("Chatterbox not yet supported on iOS")
    }
    actual suspend fun stop(): ByteArray {
        throw UnsupportedOperationException("Chatterbox not yet supported on iOS")
    }
    actual suspend fun getDurationMs(): Long = 0
    actual fun isRecording(): Boolean = false
}