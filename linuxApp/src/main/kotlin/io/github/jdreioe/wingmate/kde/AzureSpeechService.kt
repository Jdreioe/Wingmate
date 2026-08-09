package io.github.jdreioe.wingmate.kde

import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.infrastructure.AzureTtsClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

class AzureSpeechService(
    private val configRepository: ConfigRepository,
    private val pronunciationRepository: PronunciationDictionaryRepository,
) : SpeechService {
    
    // Uses default engine (should be OkHttp from shared dependency)
    private val client = HttpClient()
    @Volatile
    private var currentProcess: Process? = null
    @Volatile
    private var paused = false

    override suspend fun speak(text: String, voice: Voice?, pitch: Double?, rate: Double?) {
        val config = configRepository.getSpeechConfig()
        
        if (config == null || config.subscriptionKey.isBlank() || config.endpoint.isBlank()) {
            throw IllegalStateException("Azure Speech configuration is missing or incomplete")
        }

        println("[SPEECH] Synthesizing with Azure TTS... Voice: ${voice?.name}")
            
            // Create default voice if null
        val voiceToUse = (voice ?: Voice(name = "en-US-JennyNeural", selectedLanguage = "en-US")).copy(
            pitch = pitch ?: voice?.pitch,
            rate = rate ?: voice?.rate,
        )
            
            // Generate SSML
        val ssml = AzureTtsClient.generateSsml(text, voiceToUse, pronunciationRepository.getAll())
            
            // Use WAV format for easier playback with aplay
        val audioData = AzureTtsClient.synthesize(
            client,
            ssml,
            config,
            AzureTtsClient.AudioFormat.WAV_24KHZ_16BIT
        )
            
        println("[SPEECH] Received ${audioData.size} bytes audio. Playing...")
        playAudio(audioData)
    }

    override suspend fun speakSegments(segments: List<SpeechSegment>, voice: Voice?, pitch: Double?, rate: Double?) {
        // Join text for now as simple implementation
        val text = segments.joinToString(" ") { it.text }
        speak(text, voice, pitch, rate)
    }

    private suspend fun playAudio(data: ByteArray) = withContext(Dispatchers.IO) {
        stop() // Stop previous
        
        paused = false
        val process = ProcessBuilder("aplay")
            .redirectErrorStream(true)
            .start()
            
            currentProcess = process
            
            process.outputStream.use { 
                it.write(data)
                it.flush()
            }
            
            // Drain aplay's output on a daemon thread. The old blocking
            // readLine() here threw "IOException: Stream closed" whenever
            // stop() destroyed the process mid-playback.
            val drainThread = thread(isDaemon = true, name = "aplay-drain") {
                try {
                    process.inputStream.readBytes()
                } catch (e: Exception) {
                    // Stream closed by stop()/new play - expected, ignore
                }
            }
            
        val exitCode = process.waitFor()
        drainThread.join(2000)
        if (currentProcess === process) {
            currentProcess = null
            paused = false
        }
        check(exitCode == 0) { "aplay exited with code $exitCode" }
        println("[SPEECH] Audio playback finished.")
    }

    override suspend fun pause() {
        currentProcess?.takeIf { it.isAlive }?.let {
            signal(it, "-STOP")
            paused = true
        }
    }

    override suspend fun stop() {
        paused = false
        currentProcess?.destroy()
        currentProcess = null
    }

    override suspend fun resume() {
        currentProcess?.takeIf { it.isAlive && paused }?.let {
            signal(it, "-CONT")
            paused = false
        }
    }

    override fun isPlaying(): Boolean {
        return currentProcess?.isAlive == true && !paused
    }

    override fun isPaused(): Boolean = currentProcess?.isAlive == true && paused

    private fun signal(process: Process, signal: String) {
        val result = ProcessBuilder("kill", signal, process.pid().toString()).start().waitFor()
        check(result == 0) { "Could not send $signal to audio player" }
    }
    
    override suspend fun guessPronunciation(text: String, language: String): String? = null
}
