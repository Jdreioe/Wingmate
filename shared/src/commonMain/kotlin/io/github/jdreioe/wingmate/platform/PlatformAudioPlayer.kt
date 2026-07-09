package io.github.jdreioe.wingmate.platform

expect class PlatformAudioPlayer {
    suspend fun play(audio: ByteArray)
    suspend fun stop()
    suspend fun pause()
    suspend fun resume()
    fun isPlaying(): Boolean
    fun isPaused(): Boolean
}