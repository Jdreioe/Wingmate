package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.SoundPlayer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFAudio.AVAudioPlayer
import platform.Foundation.NSData
import platform.Foundation.create

@OptIn(ExperimentalForeignApi::class)
class IosSoundPlayer : SoundPlayer {
    private var player: AVAudioPlayer? = null

    override suspend fun playBytes(bytes: ByteArray, contentType: String?): Boolean {
        return runCatching {
            stop()
            if (bytes.isEmpty()) return false
            val data = bytes.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
            }
            val audioPlayer = AVAudioPlayer(data = data, error = null) ?: return false
            player = audioPlayer
            audioPlayer.prepareToPlay()
            audioPlayer.play()
        }.getOrDefault(false)
    }

    override suspend fun stop() {
        player?.stop()
        player = null
    }
}
