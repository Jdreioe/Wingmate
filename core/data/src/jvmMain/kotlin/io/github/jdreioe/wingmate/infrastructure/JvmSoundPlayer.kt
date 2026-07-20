package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.SoundPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine

class JvmSoundPlayer : SoundPlayer {
    private var clip: Clip? = null

    override suspend fun playBytes(bytes: ByteArray, contentType: String?): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                stopInternal()
                val stream = AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes))
                val info = DataLine.Info(Clip::class.java, stream.format)
                val newClip = AudioSystem.getLine(info) as Clip
                newClip.open(stream)
                newClip.start()
                clip = newClip
                true
            }.getOrDefault(false)
        }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        stopInternal()
    }

    private fun stopInternal() {
        clip?.runCatching {
            stop()
            close()
        }
        clip = null
    }
}
