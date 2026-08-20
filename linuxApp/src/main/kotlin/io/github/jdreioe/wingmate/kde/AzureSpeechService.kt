package io.github.jdreioe.wingmate.kde

import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository
import io.github.jdreioe.wingmate.domain.SettingsRepository
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.SpeechPlaybackState
import io.github.jdreioe.wingmate.domain.SpeechPlaybackStatus
import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.infrastructure.AzureTtsClient
import io.github.jdreioe.wingmate.infrastructure.GoogleTtsClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicLong

class CloudSpeechService(
    private val configRepository: ConfigRepository,
    private val pronunciationRepository: PronunciationDictionaryRepository,
    private val settingsRepository: SettingsRepository,
) : SpeechService {
    
    // Uses default engine (should be OkHttp from shared dependency)
    private val client = HttpClient {
        followRedirects = false
    }
    @Volatile
    private var currentProcess: Process? = null
    @Volatile
    private var paused = false
    private val generation = AtomicLong(0)
    @Volatile private var state = SpeechPlaybackState()
    private val ownershipLock = Any()

    override suspend fun speak(text: String, voice: Voice?, pitch: Double?, rate: Double?) {
        val requestId = generation.incrementAndGet()
        stopNative()
        state = SpeechPlaybackState(requestId, SpeechPlaybackStatus.PREPARING)
        val engine = settingsRepository.get().ttsEngine
        check(engine != TtsEngine.SYSTEM) { "System speech must use the native Linux speech service" }
            
            // Create default voice if null
        val voiceToUse = (voice ?: Voice(name = "en-US-JennyNeural", selectedLanguage = "en-US")).copy(
            pitch = pitch ?: voice?.pitch,
            rate = rate ?: voice?.rate,
        )
            
            // Generate SSML
        val audioData = try {
            when (engine) {
                TtsEngine.GOOGLE_CLOUD -> {
                    val config = configRepository.getGoogleSpeechConfig()
                        ?.takeIf { it.apiKey.isNotBlank() }
                        ?: error("Google Cloud Text-to-Speech is not configured")
                    GoogleTtsClient.synthesize(
                        client = client,
                        text = text,
                        voice = voiceToUse.copy(mathMode = false),
                        config = config,
                        pitch = pitch,
                        rate = rate,
                        audioEncoding = GoogleTtsClient.AudioEncoding.LINEAR16,
                    )
                }
                TtsEngine.AZURE_USER_RESOURCE, TtsEngine.AZURE_MANAGED -> {
                    val config = configRepository.getSpeechConfig()
                        ?.takeIf { it.subscriptionKey.isNotBlank() && it.endpoint.isNotBlank() }
                        ?: error("Azure Speech configuration is missing or incomplete")
                    val azureVoice = if (voiceToUse.name?.count { it == '-' }?.let { it >= 3 } == true) {
                        voiceToUse.copy(name = "en-US-JennyNeural")
                    } else {
                        voiceToUse
                    }
                    val ssml = AzureTtsClient.generateSsml(text, azureVoice, pronunciationRepository.getAll())
                    AzureTtsClient.synthesize(
                        client,
                        ssml,
                        config,
                        AzureTtsClient.AudioFormat.WAV_24KHZ_16BIT,
                    )
                }
                TtsEngine.SYSTEM -> error("System speech must use the native Linux speech service")
            }
        } catch (error: Throwable) {
            failRequest(requestId)
            throw error
        }
            
        try {
            playAudio(requestId, audioData)
        } catch (error: Throwable) {
            failRequest(requestId)
            throw error
        }
    }

    override suspend fun speakSegments(segments: List<SpeechSegment>, voice: Voice?, pitch: Double?, rate: Double?) {
        // Join text for now as simple implementation
        val text = segments.joinToString(" ") { it.text }
        speak(text, voice, pitch, rate)
    }

    private suspend fun playAudio(requestId: Long, data: ByteArray) = withContext(Dispatchers.IO) {
        check(generation.get() == requestId) { "Speech request was replaced" }
        paused = false
        val process = synchronized(ownershipLock) {
            check(generation.get() == requestId) { "Speech request was replaced" }
            ProcessBuilder("aplay")
                .redirectErrorStream(true)
                .start()
                .also {
                    currentProcess = it
                    state = SpeechPlaybackState(requestId, SpeechPlaybackStatus.PLAYING)
                }
        }
            
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
            state = SpeechPlaybackState(requestId, SpeechPlaybackStatus.IDLE)
        }
        check(exitCode == 0) { "aplay exited with code $exitCode" }
    }

    override suspend fun pause() {
        currentProcess?.takeIf { it.isAlive }?.let {
            signal(it, "-STOP")
            paused = true
            state = SpeechPlaybackState(generation.get(), SpeechPlaybackStatus.PAUSED)
        }
    }

    override suspend fun stop() {
        val requestId = generation.incrementAndGet()
        stopNative()
        state = SpeechPlaybackState(requestId, SpeechPlaybackStatus.IDLE)
    }

    private fun stopNative() {
        synchronized(ownershipLock) {
            paused = false
            currentProcess?.destroy()
            currentProcess = null
        }
    }

    override suspend fun resume() {
        currentProcess?.takeIf { it.isAlive && paused }?.let {
            signal(it, "-CONT")
            paused = false
            state = SpeechPlaybackState(generation.get(), SpeechPlaybackStatus.PLAYING)
        }
    }

    override fun isPlaying(): Boolean {
        return currentProcess?.isAlive == true && !paused
    }

    override fun isPaused(): Boolean = currentProcess?.isAlive == true && paused

    override fun playbackState(): SpeechPlaybackState = state

    private fun failRequest(requestId: Long) {
        if (generation.get() == requestId) {
            state = SpeechPlaybackState(requestId, SpeechPlaybackStatus.FAILED, "Speech could not be played")
        }
    }

    private fun signal(process: Process, signal: String) {
        val result = ProcessBuilder("kill", signal, process.pid().toString()).start().waitFor()
        check(result == 0) { "Could not send $signal to audio player" }
    }
    
    override suspend fun guessPronunciation(text: String, language: String): String? = null
}
