package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import android.media.MediaPlayer
import io.github.jdreioe.wingmate.domain.SoundPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AndroidSoundPlayer(
    private val context: Context
) : SoundPlayer {
    private var mediaPlayer: MediaPlayer? = null

    override suspend fun playBytes(bytes: ByteArray, contentType: String?): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                stopInternal()
                val extension = when (contentType?.lowercase()) {
                    "audio/wav", "audio/x-wav" -> "wav"
                    "audio/ogg" -> "ogg"
                    "audio/mpeg", "audio/mp3" -> "mp3"
                    "audio/mp4", "audio/m4a", "audio/aac" -> "m4a"
                    else -> "bin"
                }
                val file = File(context.cacheDir, "obf-sound-${System.currentTimeMillis()}.$extension")
                file.writeBytes(bytes)
                val player = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setOnCompletionListener {
                        it.release()
                        runCatching { file.delete() }
                        if (mediaPlayer === it) mediaPlayer = null
                    }
                    prepare()
                    start()
                }
                mediaPlayer = player
                true
            }.getOrDefault(false)
        }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        stopInternal()
    }

    private fun stopInternal() {
        mediaPlayer?.runCatching {
            stop()
            release()
        }
        mediaPlayer = null
    }
}
