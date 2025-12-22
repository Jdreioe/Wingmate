package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.AudioPlayer
import io.github.jdreioe.wingmate.domain.Voice
import javazoom.jl.player.Player
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

class DesktopAudioPlayer : AudioPlayer {
    private var currentPlayer: Player? = null
    @Volatile private var isPlaying = false

    override suspend fun playFile(path: String) {
        stop()
        withContext(Dispatchers.IO) {
            try {
                val fis = FileInputStream(path)
                val player = Player(fis)
                currentPlayer = player
                isPlaying = true
                player.play()
                isPlaying = false
            } catch (e: Exception) {
                logger.error(e) { "Error playing file: $path" }
            }
        }
    }

    override suspend fun playSystemTts(text: String, voice: Voice?) {
        withContext(Dispatchers.IO) {
            // Simplified system TTS using 'say' (macOS) or 'espeak' (Linux)
            val command = if (System.getProperty("os.name").lowercase().contains("mac")) {
                 listOf("say", text)
            } else {
                 listOf("espeak", text)
            }

            try {
                // Blocking call moved to IO dispatcher to avoid freezing UI
                ProcessBuilder(command).start().waitFor()
            } catch (e: Exception) {
                logger.error(e) { "System TTS failed" }
            }
        }
    }

    override suspend fun stop() {
        currentPlayer?.close()
        currentPlayer = null
        isPlaying = false
    }

    override suspend fun pause() {
        // Basic pause support: stop player but keep track?
        // JLayer is a simple decoder. True pause requires keeping the stream open or tracking position.
        // For this refactor, stopping is safer than freezing.
        stop()
    }

    override suspend fun resume() {
        // Resume not supported in basic JLayer wrapper without tracking frames.
        // Leaving empty as per previous implementation but documented.
    }

    override fun isPlaying() = isPlaying
}
