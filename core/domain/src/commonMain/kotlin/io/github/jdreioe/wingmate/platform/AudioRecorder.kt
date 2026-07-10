package io.github.jdreioe.wingmate.platform

expect class AudioRecorder {
    suspend fun start(outputPath: String): Result<Unit>
    suspend fun stop(): ByteArray
    suspend fun getDurationMs(): Long
    fun isRecording(): Boolean
}