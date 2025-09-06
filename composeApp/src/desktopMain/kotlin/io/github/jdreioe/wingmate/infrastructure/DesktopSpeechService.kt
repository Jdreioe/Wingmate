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
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import javax.sound.sampled.*
import java.awt.Desktop
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

class DesktopSpeechService : SpeechService {
    private val client = HttpClient(OkHttp) {}
    private val log = LoggerFactory.getLogger("DesktopSpeechService")
    
    // Track current playback state
    private var currentPlayer: Player? = null
    private var currentProcess: Process? = null
    private var isPlaying = false
    private var isPaused = false
    private val stopRequested = AtomicBoolean(false)
    private val virtualSinkName = "wingmate_vmic"
    private val virtualSinkDesc = "Wingmate Virtual Mic"

    init {
        log.info("Enhanced DesktopSpeechService initialized with Azure TTS and System TTS support")
    }

    override suspend fun speak(text: String, voice: Voice?, pitch: Double?, rate: Double?) {
        log.info("speak() called with text='{}', voice={}, pitch={}, rate={}", text.take(50), voice?.name, pitch, rate)
        log.info("speak() called on thread={}", Thread.currentThread().name)
    // Reset stop flag for this utterance
    stopRequested.set(false)

        // Prevent overlap: if something is currently playing, stop it first
        if (isPlaying || currentPlayer != null || currentProcess != null) {
            log.info("speak() detected existing playback; invoking stop() before starting new utterance")
            try { stop() } catch (t: Throwable) { log.warn("error stopping previous playback", t) }
            // After stop, ensure state reflects idle
            currentPlayer = null
            currentProcess = null
            isPlaying = false
            isPaused = false
        }
        
        // Check if user prefers system TTS
        val koin = GlobalContext.getOrNull()
        val settingsRepo = koin?.let { runCatching { it.get<io.github.jdreioe.wingmate.domain.SettingsRepository>() }.getOrNull() }
    val uiSettings = settingsRepo?.let { runCatching { runBlocking { it.get() } }.getOrNull() }
        
        if (uiSettings?.useSystemTts == true) {
            // Use system TTS
            withContext(Dispatchers.IO) {
                speakWithSystemTts(text, voice, pitch, rate)
            }
            return
        }
        
        // Try to reuse cached Azure audio if available to save API calls
        if (maybePlayFromCache(text, voice, pitch, rate, uiSettings?.primaryLanguage)) {
            return
        }

    // Use Azure TTS (enhanced implementation)
        withContext(Dispatchers.IO) {
            speakWithAzureTts(text, voice, pitch, rate)
        }
    }

    private suspend fun maybePlayFromCache(
        text: String,
        voice: Voice?,
        pitch: Double?,
        rate: Double?,
        uiPrimaryLanguage: String?
    ): Boolean {
        return runCatching {
            val koin = GlobalContext.getOrNull()
            val repo = koin?.get<io.github.jdreioe.wingmate.domain.SaidTextRepository>()
            if (repo == null) return false

            // Determine effective voice/lang similar to Azure path
            val baseVoice = voice ?: Voice(name = "en-US-JennyNeural", primaryLanguage = "en-US")
            val enhancedVoice = baseVoice.copy(
                pitch = pitch ?: baseVoice.pitch,
                rate = rate ?: baseVoice.rate
            )
            val effectiveLang = when {
                !enhancedVoice.selectedLanguage.isNullOrBlank() -> enhancedVoice.selectedLanguage
                !uiPrimaryLanguage.isNullOrBlank() -> uiPrimaryLanguage
                !enhancedVoice.primaryLanguage.isNullOrBlank() -> enhancedVoice.primaryLanguage
                else -> "en-US"
            }
            val vForMatch = enhancedVoice.copy(primaryLanguage = effectiveLang)

            val list = runCatching { repo.list() }.getOrNull().orEmpty()
            val targetPitch = vForMatch.pitch
            val targetRate = vForMatch.rate

            val candidate = list.asSequence()
                .filter { it.saidText == text }
                .filter { !it.audioFilePath.isNullOrBlank() }
                .filter { it.voiceName == vForMatch.name }
                .filter { (it.primaryLanguage ?: "") == (vForMatch.primaryLanguage ?: "") }
                .filter { (it.pitch == null && targetPitch == null) || (it.pitch != null && targetPitch != null && it.pitch == targetPitch) }
                .filter { (it.speed == null && targetRate == null) || (it.speed != null && targetRate != null && it.speed == targetRate) }
                .sortedByDescending { it.date ?: it.createdAt ?: 0L }
                .firstOrNull()

            if (candidate != null) {
                val path = candidate.audioFilePath!!
                val file = File(path)
                if (file.exists() && file.length() > 0L) {
                    log.info("reusing cached audio from history: {}", file.absolutePath)
                    // Record a new history entry referencing the same audio file for this play
                    val now = System.currentTimeMillis()
                    runCatching {
                        repo.add(
                            io.github.jdreioe.wingmate.domain.SaidText(
                                date = now,
                                saidText = text,
                                voiceName = vForMatch.name,
                                pitch = vForMatch.pitch,
                                speed = vForMatch.rate,
                                audioFilePath = file.absolutePath,
                                createdAt = now,
                                position = 0,
                                primaryLanguage = vForMatch.selectedLanguage?.takeIf { it.isNotBlank() } ?: vForMatch.primaryLanguage
                            )
                        )
                    }.onFailure { t -> log.warn("Failed to append cached-play history", t) }

                    // Play the cached file
                    withContext(Dispatchers.IO) { playAudioFile(file) }
                    return true
                } else {
                    log.info("cached history file missing or empty; will synthesize via API: {}", path)
                }
            }
            false
        }.getOrElse { e ->
            log.warn("cache reuse attempt failed; falling back to API", e)
            false
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
    val uiSettings = settingsRepo?.let { runCatching { runBlocking { it.get() } }.getOrNull() }
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
            if (stopRequested.get()) {
                log.info("stop requested during synthesis; skipping playback")
                return
            }
            
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
            if (stopRequested.get()) {
                log.info("stop requested before playback; skipping")
                return
            }
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
        if (stopRequested.get()) {
            log.info("stop requested before system TTS launch; skipping")
            return
        }
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
    stopRequested.set(true)
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
            
            if (stopRequested.get()) {
                log.info("stop requested before play; aborting")
                return
            }

            // Determine whether to route audio to virtual mic
            val routeToVirtual = shouldRouteToVirtualMic()
            val sinkArg = if (routeToVirtual) {
                ensureVirtualMic()
                // For ffplay with PulseAudio: use -f pulse -i default or a named sink via PULSE_SINK env var
                // We'll set PULSE_SINK to our sink name when launching ffplay
                virtualSinkName
            } else null

            // Try JLayer first for MP3 playback (only for normal speakers; JLayer can't target specific sink)
            try {
                if (stopRequested.get()) {
                    log.info("stop requested before JLayer start; aborting")
                    return
                }
                if (!routeToVirtual) {
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
                }
            } catch (e: Exception) {
                log.warn("JLayer playback failed, trying system commands", e)
                currentPlayer = null
                isPlaying = false
            }
            
            // Try system audio commands as fallback
            val commands = listOf(
                // Use ffplay with pulse output explicitly. PULSE_SINK env will target our sink.
                listOf("ffplay", "-f", "pulse", "-nodisp", "-autoexit", audioFile.absolutePath),
                listOf("mpg123", audioFile.absolutePath),
                listOf("paplay", audioFile.absolutePath),
                listOf("aplay", audioFile.absolutePath)
            )
            
            for (command in commands) {
                if (isCommandAvailable(command[0])) {
                    try {
                        if (stopRequested.get()) {
                            log.info("stop requested before system player start; aborting")
                            return
                        }
                        log.info("trying system command: {}", command.joinToString(" "))
                        val pb = ProcessBuilder(command)
                        if (sinkArg != null) {
                            // Route to our null sink by setting PULSE_SINK env var; apps can then pick the monitor or remap mic.
                            val env = pb.environment()
                            env["PULSE_SINK"] = sinkArg
                            log.info("routing playback to virtual sink: {}", sinkArg)
                        }
                        val process = pb
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

    private fun shouldRouteToVirtualMic(): Boolean {
        return runCatching {
            val koin = GlobalContext.getOrNull()
            val settingsRepo = koin?.let { it.get<io.github.jdreioe.wingmate.domain.SettingsRepository>() }
            val settings = runCatching { runBlocking { settingsRepo?.get() } }.getOrNull()
            settings?.virtualMicEnabled == true
        }.getOrNull() == true
    }

    private fun ensureVirtualMic() {
        // Only attempt on Linux with PulseAudio or PipeWire's Pulse server
        val os = System.getProperty("os.name").lowercase()
        if (!os.contains("linux")) return
        if (!isCommandAvailable("pactl")) {
            log.warn("pactl not found; cannot create virtual mic sink")
            return
        }

        try {
            // Detect whether the Pulse server is PipeWire-backed
            val info = ProcessBuilder("pactl", "info").redirectErrorStream(true).start()
            val infoOut = info.inputStream.bufferedReader().readText()
            val isPipeWire = infoOut.contains("PipeWire", ignoreCase = true)
            log.info("Pulse server: {}", infoOut.lineSequence().firstOrNull { it.contains("Server Name", true) } ?: "<unknown>")

            // Check if sink already exists
            val listSinks = ProcessBuilder("pactl", "list", "short", "sinks")
                .redirectErrorStream(true)
                .start()
            val sinksOut = listSinks.inputStream.bufferedReader().readText()
            if (!sinksOut.lines().any { it.contains(virtualSinkName) }) {
                // Create null sink; also set a readable monitor name via source_properties
                log.info("creating PulseAudio null sink: {}", virtualSinkName)
                val createSink = ProcessBuilder(
                    "pactl", "load-module", "module-null-sink",
                    "sink_name=$virtualSinkName",
                    "sink_properties=device.description=$virtualSinkDesc",
                    "source_properties=device.description=${virtualSinkDesc} (Monitor)"
                ).redirectErrorStream(true).start()
                createSink.waitFor()
                log.info(
                    "created null sink (exit={}): {}",
                    createSink.exitValue(),
                    createSink.inputStream.bufferedReader().readText()
                )
            }

            // Create a dedicated microphone source that maps to the sink's monitor, so apps see it as a mic
            val remapSourceName = "${virtualSinkName}_mic"
            val listSources = ProcessBuilder("pactl", "list", "short", "sources")
                .redirectErrorStream(true)
                .start()
            val sourcesOut = listSources.inputStream.bufferedReader().readText()
            if (!sourcesOut.lines().any { it.contains(remapSourceName) }) {
                if (isPipeWire) {
                    // Prefer module-virtual-source under PipeWire to avoid remap-source crashes
                    log.info("creating virtual-source mic (PipeWire): {} -> {}.monitor", remapSourceName, virtualSinkName)
                    val createVsrc = ProcessBuilder(
                        "pactl", "load-module", "module-virtual-source",
                        "source_name=$remapSourceName",
                        "master=${virtualSinkName}.monitor",
                        "source_properties=device.description=$virtualSinkDesc"
                    ).redirectErrorStream(true).start()
                    createVsrc.waitFor()
                    val vsrcOut = createVsrc.inputStream.bufferedReader().readText()
                    if (createVsrc.exitValue() == 0) {
                        log.info("created virtual-source mic (exit={}): {}", createVsrc.exitValue(), vsrcOut)
                    } else {
                        log.warn("failed to create virtual-source mic (exit={}): {} — falling back to monitor-only", createVsrc.exitValue(), vsrcOut)
                    }
                } else {
                    // PulseAudio classic: remap-source is fine
                    log.info("creating remap-source as microphone: {} -> {}.monitor", remapSourceName, virtualSinkName)
                    val createSource = ProcessBuilder(
                        "pactl", "load-module", "module-remap-source",
                        "source_name=$remapSourceName",
                        "master=${virtualSinkName}.monitor",
                        "source_properties=device.description=$virtualSinkDesc"
                    ).redirectErrorStream(true).start()
                    createSource.waitFor()
                    log.info(
                        "created remap-source mic (exit={}): {}",
                        createSource.exitValue(),
                        createSource.inputStream.bufferedReader().readText()
                    )
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to ensure virtual mic sink/source — using monitor-only fallback", e)
        }
    }
}
