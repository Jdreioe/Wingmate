package io.github.jdreioe.wingmate.kde

import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Linux speech service using system TTS (espeak-ng, festival, or similar).
 */
class LinuxSpeechService : SpeechService {
    private var currentProcess: Process? = null
    private var _isPaused = false
    
    override suspend fun speak(text: String, voice: Voice?, pitch: Double?, rate: Double?) {
        withContext(Dispatchers.IO) {
            // Stop any current speech
            stop()
            
            // Try different TTS engines in order of preference
            val ttsCommand = findTtsCommand()
            if (ttsCommand != null) {
                val args = buildCommandArgs(ttsCommand, text, voice, pitch, rate)
                println("[SPEECH] Executing TTS command: $args")
                currentProcess = ProcessBuilder(args)
                    .redirectErrorStream(true)
                    .start()
                
                // Read and print any output/errors from the process
                val reader = currentProcess?.inputStream?.bufferedReader()
                if (reader != null) {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        println("[SPEECH] TTS Output: $line")
                    }
                }
                
                currentProcess?.waitFor()
                println("[SPEECH] TTS command finished.")
            } else {
                println("[SPEECH] No TTS engine found. Install espeak-ng, festival, or piper.")
            }
        }
    }
    
    override suspend fun speakSegments(segments: List<SpeechSegment>, voice: Voice?, pitch: Double?, rate: Double?) {
        // Combine all segments and speak
        val combinedText = segments.joinToString(" ") { it.text }
        speak(combinedText, voice, pitch, rate)
    }
    
    override suspend fun pause() {
        _isPaused = true
        // espeak-ng doesn't support pause, so we just stop
        currentProcess?.let {
            if (it.isAlive) {
                Runtime.getRuntime().exec(arrayOf("kill", "-STOP", it.pid().toString()))
            }
        }
    }
    
    override suspend fun stop() {
        _isPaused = false
        currentProcess?.let {
            if (it.isAlive) {
                it.destroyForcibly()
            }
        }
        currentProcess = null
    }
    
    override suspend fun resume() {
        if (_isPaused) {
            _isPaused = false
            currentProcess?.let {
                if (it.isAlive) {
                    Runtime.getRuntime().exec(arrayOf("kill", "-CONT", it.pid().toString()))
                }
            }
        }
    }
    
    override fun isPlaying(): Boolean {
        return currentProcess?.isAlive == true && !_isPaused
    }
    
    override fun isPaused(): Boolean {
        return _isPaused
    }
    
    private fun findTtsCommand(): String? {
        // Check which TTS engines are available - prioritize piper for better quality
        
        // First check common piper installation locations
        val piperPaths = listOf(
            "${System.getProperty("user.home")}/.local/bin/piper/piper",
            "${System.getProperty("user.home")}/.local/bin/piper",
            "/usr/bin/piper",
            "/usr/local/bin/piper"
        )
        
        for (path in piperPaths) {
            val file = File(path)
            if (file.exists() && file.canExecute()) {
                return path
            }
        }
        
        // Fall back to checking PATH for other TTS engines
        val engines = listOf("piper", "espeak-ng", "espeak", "festival")
        
        for (engine in engines) {
            try {
                val process = ProcessBuilder("which", engine)
                    .redirectErrorStream(true)
                    .start()
                val result = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                if (result.isNotEmpty() && File(result).exists()) {
                    return result
                }
            } catch (e: Exception) {
                continue
            }
        }
        return null
    }
    
    private fun buildCommandArgs(
        engine: String,
        text: String,
        voice: Voice?,
        pitch: Double?,
        rate: Double?
    ): List<String> {
        return when (engine) {
            "espeak-ng", "espeak" -> {
                mutableListOf<String>().apply {
                    add(engine)
                    
                    // Voice/language - use selectedLanguage or primaryLanguage from Voice
                    val langCode = voice?.selectedLanguage?.takeIf { it.isNotEmpty() } 
                        ?: voice?.primaryLanguage 
                        ?: "en"
                    val lang = langCode.split("-").firstOrNull() ?: "en"
                    add("-v")
                    add(lang)
                    
                    // Speed (default is 175 wpm, rate is a multiplier)
                    rate?.let {
                        val wpm = (175 * it).toInt().coerceIn(80, 450)
                        add("-s")
                        add(wpm.toString())
                    }
                    
                    // Pitch (default is 50, range 0-99)
                    pitch?.let {
                        val p = (50 * it).toInt().coerceIn(0, 99)
                        add("-p")
                        add(p.toString())
                    }
                    
                    add(text)
                }
            }
            else -> {
                if (engine.contains("piper")) {
                    // Piper uses stdin for text input and pipes to aplay for playback
                    // Find available piper voice model
                    val voiceModel = findPiperVoiceModel(voice)
                    // Use the engine path (which might be a full path)
                    listOf("bash", "-c", "echo '${text.replace("'", "'\"'\"'")}' | \"$engine\" --model $voiceModel --output_raw | aplay -r 22050 -f S16_LE -t raw -")
                } else {
                    listOf(engine, text)
                }
            }
        }
    }
    
    /**
     * Find an available piper voice model based on language.
     * Looks in common piper model directories.
     */
    private fun findPiperVoiceModel(voice: Voice?): String {
        val langCode = voice?.selectedLanguage?.takeIf { it.isNotEmpty() } 
            ?: voice?.primaryLanguage 
            ?: "en_US"
        
        // Common piper model directories
        val modelDirs = listOf(
            "/usr/share/piper-voices",
            "/usr/local/share/piper-voices",
            "${System.getProperty("user.home")}/.local/share/piper-voices",
            "${System.getProperty("user.home")}/.config/piper/voices"
        )
        
        // Language-specific model patterns (prioritized)
        val langPatterns = listOf(
            langCode.replace("-", "_"),  // en_US, nb_NO, etc.
            langCode.split("-").firstOrNull() ?: "en"  // en, nb, etc.
        )
        
        for (dir in modelDirs) {
            val dirFile = File(dir)
            if (dirFile.exists() && dirFile.isDirectory) {
                // Look for .onnx model files matching language
                for (pattern in langPatterns) {
                    val models = dirFile.listFiles { _, name -> 
                        name.startsWith(pattern) && name.endsWith(".onnx")
                    }
                    if (!models.isNullOrEmpty()) {
                        return models.first().absolutePath
                    }
                }
            }
        }
        
        // Default fallback - user should have at least one model installed
        return "en_US-lessac-medium"
    }
}
