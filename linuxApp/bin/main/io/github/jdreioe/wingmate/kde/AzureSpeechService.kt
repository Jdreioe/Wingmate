package io.github.jdreioe.wingmate.kde

import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.infrastructure.AzureTtsClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AzureSpeechService(
    private val configRepository: ConfigRepository
) : SpeechService {
    
    // Uses default engine (should be OkHttp from shared dependency)
    private val client = HttpClient()
    private var currentProcess: Process? = null

    override suspend fun speak(text: String, voice: Voice?, pitch: Double?, rate: Double?) {
        val config = configRepository.getSpeechConfig()
        
        if (config == null || config.subscriptionKey.isBlank() || config.endpoint.isBlank()) {
            println("[SPEECH] Azure TTS config missing or incomplete. Endpoint: ${config?.endpoint}, Key present: ${!config?.subscriptionKey.isNullOrBlank()}")
            return
        }

        try {
            println("[SPEECH] Synthesizing with Azure TTS... Text: '$text', Voice: ${voice?.name}")
            
            // Create default voice if null
            val voiceToUse = voice ?: Voice(name = "en-US-JennyNeural", selectedLanguage = "en-US")
            
            // Generate SSML
            val ssml = AzureTtsClient.generateSsml(text, voiceToUse)
            println("[SPEECH] Generated SSML: $ssml")
            
            // Use WAV format for easier playback with aplay
            val audioData = AzureTtsClient.synthesize(
                client, 
                ssml, 
                config, 
                AzureTtsClient.AudioFormat.WAV_24KHZ_16BIT
            )
            
            println("[SPEECH] Received ${audioData.size} bytes audio. Playing...")
            playAudio(audioData)
            
        } catch (e: Exception) {
            println("[SPEECH] Azure TTS failed: ${e.message}")
            e.printStackTrace()
        }
    }

    override suspend fun speakSegments(segments: List<SpeechSegment>, voice: Voice?, pitch: Double?, rate: Double?) {
        // Join text for now as simple implementation
        val text = segments.joinToString(" ") { it.text }
        speak(text, voice, pitch, rate)
    }

    private suspend fun playAudio(data: ByteArray) = withContext(Dispatchers.IO) {
        stop() // Stop previous
        
        try {
            // Pipe to aplay
            val process = ProcessBuilder("aplay")
                .redirectErrorStream(true)
                .start()
            
            currentProcess = process
            
            process.outputStream.use { 
                it.write(data)
                it.flush()
            }
            
            val reader = process.inputStream.bufferedReader()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                // println("aplay: $line")
            }
            
            process.waitFor()
            println("[SPEECH] Audio playback finished.")
        } catch (e: Exception) {
            println("[SPEECH] Audio playback failed: ${e.message}")
            e.printStackTrace()
        }
    }

    override suspend fun pause() {
        stop()
    }

    override suspend fun stop() {
        currentProcess?.destroy()
        currentProcess = null
    }

    override suspend fun resume() {
        // Not supported
    }

    override fun isPlaying(): Boolean {
        return currentProcess?.isAlive == true
    }

    override fun isPaused(): Boolean = false
    
    override suspend fun guessPronunciation(text: String, language: String): String? = null
}
