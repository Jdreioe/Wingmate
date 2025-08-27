package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.domain.ConfigRepository
import org.koin.core.context.GlobalContext
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import java.io.File
import javazoom.jl.player.Player
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import javax.sound.sampled.*
import java.awt.Desktop
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

class DesktopSpeechService : SpeechService {
    private val client = HttpClient(OkHttp) {}
    private val log = LoggerFactory.getLogger("DesktopSpeechService")
    
    // Track current playback state
    private var currentPlayer: Player? = null
    private var currentProcess: Process? = null
    private var isPlaying = false
    private var isPaused = false

    init {
        log.info("Enhanced DesktopSpeechService initialized with Azure TTS and System TTS support")
    }

    override suspend fun speak(text: String, voice: Voice?, pitch: Double?, rate: Double?) {
        log.info("speak() called with text='{}', voice={}, pitch={}, rate={}", text.take(50), voice?.name, pitch, rate)
        log.info("speak() called on thread={}", Thread.currentThread().name)
        
        // Check if user prefers system TTS
        val koin = GlobalContext.getOrNull()
        val settingsRepo = koin?.let { runCatching { it.get<io.github.jdreioe.wingmate.domain.SettingsRepository>() }.getOrNull() }
        val uiSettings = settingsRepo?.let { runCatching { it.get() }.getOrNull() }
        
        if (uiSettings?.useSystemTts == true) {
            // Use system TTS
            withContext(Dispatchers.IO) {
                speakWithSystemTts(text, voice, pitch, rate)
            }
            return
        }
        
        // Use Azure TTS (enhanced implementation)
        withContext(Dispatchers.IO) {
            speakWithAzureTts(text, voice, pitch, rate)
        }
    }

    private suspend fun speakWithAzureTts(text: String, voice: Voice?, pitch: Double?, rate: Double?) {
        val cfg = getConfig() ?: throw IllegalStateException("No Azure TTS configuration found. Please configure Azure endpoint and subscription key.")
        
        // Enhanced voice parameter handling
        val baseVoice = voice ?: Voice(name = "en-US-JennyNeural", primaryLanguage = "en-US")
        
        // Apply pitch and rate parameters if provided
        val enhancedVoice = baseVoice.copy(
            pitch = pitch ?: baseVoice.pitch,
            rate = rate ?: baseVoice.rate
        )

        // Resolve effective language for SSML in priority order:
        // 1. voice.selectedLanguage (per-voice selection)
        // 2. persisted UI primary language (SettingsRepository)
        // 3. voice.primaryLanguage
        // 4. fallback "en-US"
        val koin = GlobalContext.getOrNull()
        val settingsRepo = koin?.let { runCatching { it.get<io.github.jdreioe.wingmate.domain.SettingsRepository>() }.getOrNull() }
        val uiSettings = settingsRepo?.let { runCatching { it.get() }.getOrNull() }
        val effectiveLang = when {
            !enhancedVoice.selectedLanguage.isNullOrBlank() -> enhancedVoice.selectedLanguage
            !uiSettings?.primaryLanguage.isNullOrBlank() -> uiSettings!!.primaryLanguage
            !enhancedVoice.primaryLanguage.isNullOrBlank() -> enhancedVoice.primaryLanguage
            else -> "en-US"
        }

        val vForSsml = enhancedVoice.copy(primaryLanguage = effectiveLang)
        
        try {
            val ssml = AzureTtsClient.generateSsml(text, vForSsml)
            log.info("sending synth request to Azure endpoint (endpoint: ${cfg.endpoint})")
            log.debug("SSML preview: ${ssml.take(200)}...")
            
            // Use enhanced Azure TTS with higher quality audio
            val bytes = AzureTtsClient.synthesize(
                client, 
                ssml, 
                cfg, 
                AzureTtsClient.AudioFormat.MP3_24KHZ_160KBPS
            )
            log.info("received {} bytes from Azure TTS", bytes.size)
            
            if (bytes.isEmpty()) {
                throw RuntimeException("Azure TTS returned empty audio data")
            }

            // Enhanced audio file handling with better directory structure
            val userHome = System.getProperty("user.home")
            val dataDir = Paths.get(userHome, ".local", "share", "wingmate", "audio", "azure")
            if (!Files.exists(dataDir)) Files.createDirectories(dataDir)
            
            // Create more descriptive filename with voice info
            val timestamp = System.currentTimeMillis()
            val safeName = text.take(32).replace("[^A-Za-z0-9-_ ]".toRegex(), "_").trim().ifBlank { "utterance" }
            val voiceShortName = (vForSsml.name ?: "unknown").split("-").lastOrNull() ?: "default"
            val outPath = dataDir.resolve("${timestamp}_${voiceShortName}_${safeName}.mp3")
            
            Files.write(outPath, bytes)
            log.info("saved audio to: ${outPath.toAbsolutePath()}")

            // Record enhanced history with more metadata
            recordAzureTtsHistory(text, vForSsml, outPath.toAbsolutePath().toString(), timestamp)

            // Enhanced audio playback with better error handling
            playAudioFile(outPath.toFile())
            
        } catch (e: Exception) {
            log.error("Azure TTS synthesis failed", e)
            throw RuntimeException("Azure TTS failed: ${e.message}", e)
        }
    }

    private suspend fun speakWithSystemTts(text: String, voice: Voice?, pitch: Double?, rate: Double?) {
        log.info("using system TTS for speech synthesis")
        
        val v = voice ?: Voice(name = "system-default", primaryLanguage = "en-US")
        
        // Determine which TTS system to use
        val success = when {
            v.name?.startsWith("espeak-") == true -> speakWithEspeak(text, v, pitch, rate)
            v.name?.startsWith("espeak-ng-") == true -> speakWithEspeakNg(text, v, pitch, rate)
            v.name?.startsWith("festival-") == true -> speakWithFestival(text, v, pitch, rate)
            v.name?.startsWith("say-") == true -> speakWithSay(text, v, pitch, rate)
            isCommandAvailable("espeak-ng") -> speakWithEspeakNg(text, v, pitch, rate)
            isCommandAvailable("espeak") -> speakWithEspeak(text, v, pitch, rate)
            isCommandAvailable("festival") -> speakWithFestival(text, v, pitch, rate)
            isCommandAvailable("say") -> speakWithSay(text, v, pitch, rate)
            else -> {
                log.warn("No system TTS available")
                false
            }
        }
        
        if (!success) {
            throw UnsupportedOperationException("System TTS failed or not available on this platform")
        }
        
        // Record history without audio file path for system TTS
        runCatching {
            val koin = GlobalContext.getOrNull()
            val repo = koin?.get<io.github.jdreioe.wingmate.domain.SaidTextRepository>()
            val now = System.currentTimeMillis()
            repo?.add(
                io.github.jdreioe.wingmate.domain.SaidText(
                    date = now,
                    saidText = text,
                    voiceName = v.name,
                    pitch = pitch,
                    speed = rate,
                    audioFilePath = null, // System TTS doesn't save files
                    createdAt = now,
                    position = 0,
                    primaryLanguage = v.primaryLanguage
                )
            )
        }
    }

    override suspend fun pause() {
        log.info("pause() called")
        withContext(Dispatchers.IO) {
            try {
                if (isPlaying && !isPaused) {
                    // For JLayer player, we need to stop it (no pause support)
                    currentPlayer?.let { player ->
                        log.info("stopping current audio playback")
                        runCatching { player.close() }
                        currentPlayer = null
                    }
                    
                    // For system TTS processes, try to pause/stop them
                    currentProcess?.let { process ->
                        log.info("stopping current system TTS process")
                        runCatching { 
                            // Send SIGTERM to gracefully stop
                            process.destroy()
                            process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                            if (process.isAlive) {
                                // Force kill if still running
                                process.destroyForcibly()
                            }
                        }
                        currentProcess = null
                    }
                    
                    isPaused = true
                    log.info("speech paused/stopped")
                } else {
                    log.info("no active speech to pause")
                }
            } catch (e: Exception) {
                log.error("error during pause", e)
            }
        }
    }

    override suspend fun stop() {
        log.info("stop() called")
        withContext(Dispatchers.IO) {
            try {
                // Stop any current playback
                currentPlayer?.let { player ->
                    log.info("stopping current audio player")
                    runCatching { player.close() }
                    currentPlayer = null
                }
                
                // Stop any system TTS processes
                currentProcess?.let { process ->
                    log.info("terminating current system TTS process")
                    runCatching {
                        process.destroy()
                        process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                        if (process.isAlive) {
                            process.destroyForcibly()
                        }
                    }
                    currentProcess = null
                }
                
                isPlaying = false
                isPaused = false
                log.info("speech stopped completely")
            } catch (e: Exception) {
                log.error("error during stop", e)
            }
        }
    }

    // Helper methods (implement these with the system TTS functionality)
    private fun isCommandAvailable(command: String): Boolean {
        return try {
            val process = ProcessBuilder("which", command)
                .redirectErrorStream(true)
                .start()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun speakWithEspeak(text: String, voice: Voice, pitch: Double?, rate: Double?): Boolean {
        return speakWithEspeakFamily("espeak", text, voice, pitch, rate)
    }
    
    private suspend fun speakWithEspeakNg(text: String, voice: Voice, pitch: Double?, rate: Double?): Boolean {
        return speakWithEspeakFamily("espeak-ng", text, voice, pitch, rate)
    }
    
    private suspend fun speakWithEspeakFamily(espeakCommand: String, text: String, voice: Voice, pitch: Double?, rate: Double?): Boolean {
        return try {
            val command = mutableListOf(espeakCommand)
            
            // Voice selection
            when {
                voice.name?.startsWith("espeak-") == true -> {
                    val voiceName = voice.name!!.removePrefix("espeak-")
                    command.addAll(listOf("-v", voiceName))
                }
                voice.name?.startsWith("espeak-ng-") == true -> {
                    val voiceName = voice.name!!.removePrefix("espeak-ng-")
                    command.addAll(listOf("-v", voiceName))
                }
                else -> {
                    // Use language from voice
                    val lang = voice.primaryLanguage ?: "en-US"
                    command.addAll(listOf("-v", lang))
                }
            }
            
            // Pitch adjustment (espeak uses 0-99 range, default 50)
            pitch?.let { p ->
                val espeakPitch = (p * 50 + 50).coerceIn(0.0, 99.0).toInt()
                command.addAll(listOf("-p", espeakPitch.toString()))
            }
            
            // Rate adjustment (espeak uses words per minute, default ~175)
            rate?.let { r ->
                val espeakRate = (r * 175).coerceIn(50.0, 400.0).toInt()
                command.addAll(listOf("-s", espeakRate.toString()))
            }
            
            command.add(text)
            
            log.info("executing {} command: {}", espeakCommand, command.joinToString(" "))
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            
            currentProcess = process
            isPlaying = true
            isPaused = false
                
            val exitCode = process.waitFor()
            
            currentProcess = null
            isPlaying = false
            
            log.info("{} finished with exit code: {}", espeakCommand, exitCode)
            exitCode == 0
        } catch (e: Exception) {
            log.error("Error with {} TTS", espeakCommand, e)
            currentProcess = null
            isPlaying = false
            false
        }
    }
    
    private suspend fun speakWithFestival(text: String, voice: Voice, pitch: Double?, rate: Double?): Boolean {
        return try {
            log.info("using Festival TTS")
            // Festival uses stdin input
            val process = ProcessBuilder("festival", "--tts")
                .redirectErrorStream(true)
                .start()
            
            currentProcess = process
            isPlaying = true
            isPaused = false
                
            process.outputStream.use { output ->
                output.write(text.toByteArray())
                output.flush()
            }
            
            val exitCode = process.waitFor()
            
            currentProcess = null
            isPlaying = false
            
            log.info("festival finished with exit code: {}", exitCode)
            exitCode == 0
        } catch (e: Exception) {
            log.error("Error with festival TTS", e)
            currentProcess = null
            isPlaying = false
            false
        }
    }
    
    private suspend fun speakWithSay(text: String, voice: Voice, pitch: Double?, rate: Double?): Boolean {
        return try {
            log.info("using macOS say TTS")
            val command = mutableListOf("say")
            
            // Voice selection for macOS
            if (voice.name?.startsWith("say-") == true) {
                val voiceName = voice.name!!.removePrefix("say-")
                command.addAll(listOf("-v", voiceName))
            }
            
            // Rate adjustment (say uses words per minute, default ~175)
            rate?.let { r ->
                val sayRate = (r * 175).coerceIn(50.0, 400.0).toInt()
                command.addAll(listOf("-r", sayRate.toString()))
            }
            
            command.add(text)
            
            log.info("executing say command: {}", command.joinToString(" "))
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            
            currentProcess = process
            isPlaying = true
            isPaused = false
                
            val exitCode = process.waitFor()
            
            currentProcess = null
            isPlaying = false
            
            log.info("say finished with exit code: {}", exitCode)
            exitCode == 0
        } catch (e: Exception) {
            log.error("Error with say TTS", e)
            currentProcess = null
            isPlaying = false
            false
        }
    }

    private suspend fun getConfig(): SpeechServiceConfig? {
        // Get Azure config from repository or env vars
        val koin = GlobalContext.getOrNull()
        val repo = koin?.let { runCatching { it.get<ConfigRepository>() }.getOrNull() }
        val config = repo?.let { 
            runCatching { 
                withContext(Dispatchers.IO) { it.getSpeechConfig() } 
            }.getOrNull() 
        }
        
        if (config != null) {
            log.debug("loaded Azure config from repository (endpoint: ${config.endpoint})")
            return config
        }

        // Fallback to environment variables
        val endpoint = System.getenv("WINGMATE_AZURE_REGION")?.takeIf { it.isNotBlank() }
        val key = System.getenv("WINGMATE_AZURE_KEY")?.takeIf { it.isNotBlank() }
        
        return if (endpoint != null && key != null) {
            log.debug("loaded Azure config from environment variables (endpoint: $endpoint)")
            SpeechServiceConfig(endpoint = endpoint, subscriptionKey = key)
        } else {
            log.warn("no Azure TTS configuration found - neither in repository nor environment variables")
            null
        }
    }

    private suspend fun recordAzureTtsHistory(text: String, voice: Voice, audioFilePath: String, timestamp: Long) {
        runCatching {
            val koin = GlobalContext.getOrNull()
            val repo = koin?.get<io.github.jdreioe.wingmate.domain.SaidTextRepository>()
            repo?.add(
                io.github.jdreioe.wingmate.domain.SaidText(
                    date = timestamp,
                    saidText = text,
                    voiceName = voice.name,
                    pitch = voice.pitch,
                    speed = voice.rate,
                    audioFilePath = audioFilePath,
                    createdAt = timestamp,
                    position = 0,
                    primaryLanguage = voice.selectedLanguage?.takeIf { it.isNotBlank() } ?: voice.primaryLanguage
                )
            )
            log.debug("recorded TTS history entry for voice: ${voice.name}")
        }.onFailure { e ->
            log.warn("failed to record TTS history", e)
        }
    }

    private suspend fun playAudioFile(audioFile: File) {
        try {
            if (!audioFile.exists()) {
                throw RuntimeException("Audio file does not exist: ${audioFile.absolutePath}")
            }
            
            if (audioFile.length() == 0L) {
                throw RuntimeException("Audio file is empty: ${audioFile.absolutePath}")
            }
            
            log.info("attempting to play audio file: ${audioFile.absolutePath} (${audioFile.length()} bytes)")
            
            // Try JLayer first for MP3 playback
            try {
                FileInputStream(audioFile).use { fis ->
                    log.info("trying JLayer MP3 playback")
                    val player = Player(fis)
                    currentPlayer = player
                    isPlaying = true
                    isPaused = false
                    
                    log.info("starting JLayer playback on thread={}", Thread.currentThread().name)
                    player.play()
                    log.info("JLayer playback completed")
                    
                    currentPlayer = null
                    isPlaying = false
                }
                return
            } catch (e: Exception) {
                log.warn("JLayer playback failed, trying system commands", e)
                currentPlayer = null
                isPlaying = false
            }
            
            // Try system audio commands as fallback
            val commands = listOf(
                listOf("ffplay", "-nodisp", "-autoexit", audioFile.absolutePath),
                listOf("mpg123", audioFile.absolutePath),
                listOf("paplay", audioFile.absolutePath),
                listOf("aplay", audioFile.absolutePath)
            )
            
            for (command in commands) {
                if (isCommandAvailable(command[0])) {
                    try {
                        log.info("trying system command: {}", command.joinToString(" "))
                        val process = ProcessBuilder(command)
                            .redirectErrorStream(true)
                            .start()
                        
                        currentProcess = process
                        isPlaying = true
                        isPaused = false
                        
                        val exitCode = process.waitFor()
                        
                        currentProcess = null
                        isPlaying = false
                        
                        if (exitCode == 0) {
                            log.info("system audio player succeeded: {}", command[0])
                            return
                        } else {
                            log.warn("system audio player failed with exit code {}: {}", exitCode, command[0])
                        }
                    } catch (e: Exception) {
                        log.warn("error with system audio player {}", command[0], e)
                        currentProcess = null
                        isPlaying = false
                    }
                }
            }
            
            throw RuntimeException("All audio playback methods failed")
            
        } catch (e: Exception) {
            isPlaying = false
            currentPlayer = null
            currentProcess = null
            log.error("failed to play audio file: ${audioFile.absolutePath}", e)
            throw RuntimeException("Audio playback failed: ${e.message}", e)
        }
    }
}
