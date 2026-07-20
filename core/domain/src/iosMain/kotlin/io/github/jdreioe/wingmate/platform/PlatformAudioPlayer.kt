package io.github.jdreioe.wingmate.platform

actual class PlatformAudioPlayer {
    actual suspend fun play(audio: ByteArray) {
        throw UnsupportedOperationException("Chatterbox not yet supported on iOS")
    }
    actual suspend fun stop() {}
    actual suspend fun pause() {}
    actual suspend fun resume() {}
    actual fun isPlaying(): Boolean = false
    actual fun isPaused(): Boolean = false
}
