package io.github.jdreioe.wingmate.platform

import android.media.MediaPlayer
import java.io.File

actual class PlatformAudioPlayer {
    private var player: MediaPlayer? = null

    actual suspend fun play(audio: ByteArray) {
        stop()
        val tempFile = File.createTempFile("wingmate_audio", ".wav")
        tempFile.writeBytes(audio)
        player = MediaPlayer().apply {
            setDataSource(tempFile.absolutePath)
            setOnCompletionListener { tempFile.delete() }
            prepare()
            start()
        }
    }

    actual suspend fun stop() {
        player?.apply {
            if (isPlaying) stop()
            release()
        }
        player = null
    }

    actual suspend fun pause() {
        player?.apply {
            if (isPlaying) pause()
        }
    }

    actual suspend fun resume() {
        player?.apply {
            if (!isPlaying) start()
        }
    }

    actual fun isPlaying(): Boolean = player?.isPlaying ?: false

    actual fun isPaused(): Boolean {
        val p = player ?: return false
        return !p.isPlaying
    }
}