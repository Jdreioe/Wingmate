package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*

/**
 * JVM implementation of system TTS voice provider
 * Uses system TTS commands like espeak, festival, or say (macOS)
 */
actual class SystemVoiceProvider actual constructor() {
    
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
    
    /**
     * Get available system TTS voices
     */
    actual suspend fun getSystemVoices(): List<Voice> = withContext(Dispatchers.IO) {
        val voices = mutableListOf<Voice>()
        
        // Try espeak or espeak-ng (Linux)
        val espeakCommand = when {
            isCommandAvailable("espeak") -> "espeak"
            isCommandAvailable("espeak-ng") -> "espeak-ng"
            else -> null
        }
        
        if (espeakCommand != null) {
            try {
                val process = ProcessBuilder(espeakCommand, "--voices")
                    .redirectErrorStream(true)
                    .start()
                    
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                
                output.lines().drop(1).forEach { line ->
                    val parts = line.trim().split("\\s+".toRegex())
                    if (parts.size >= 4) {
                        val lang = parts[1]
                        val voiceName = parts[3]
                        voices.add(
                            Voice(
                                name = "$espeakCommand-$voiceName",
                                displayName = "${espeakCommand.capitalize()} $voiceName",
                                primaryLanguage = lang,
                                supportedLanguages = listOf(lang),
                                gender = "Unknown"
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // Fall back to default
            }
        }
        
        // Try festival (Linux)
        if (isCommandAvailable("festival") && voices.isEmpty()) {
            voices.add(
                Voice(
                    name = "festival-default",
                    displayName = "Festival Default",
                    primaryLanguage = "en-US",
                    supportedLanguages = listOf("en-US"),
                    gender = "Unknown"
                )
            )
        }
        
        // Try say (macOS)
        if (isCommandAvailable("say") && voices.isEmpty()) {
            try {
                val process = ProcessBuilder("say", "-v", "?")
                    .redirectErrorStream(true)
                    .start()
                    
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                
                output.lines().forEach { line ->
                    val match = "^([^\\s]+)\\s+([^#]+)#(.*)".toRegex().find(line.trim())
                    if (match != null) {
                        val (voiceName, lang, description) = match.destructured
                        voices.add(
                            Voice(
                                name = "say-$voiceName",
                                displayName = "macOS $voiceName",
                                primaryLanguage = lang.trim(),
                                supportedLanguages = listOf(lang.trim()),
                                gender = if (description.contains("female", true)) "Female" 
                                        else if (description.contains("male", true)) "Male" 
                                        else "Unknown"
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // Fall back to default
            }
        }
        
        // If no system TTS found, return default
        if (voices.isEmpty()) {
            voices.add(getDefaultSystemVoice())
        }
        
        voices
    }
    
    /**
     * Get default system voice
     */
    actual fun getDefaultSystemVoice(): Voice {
        val locale = Locale.getDefault()
        val lang = locale.toLanguageTag()
        
        return when {
            isCommandAvailable("espeak") -> Voice(
                name = "espeak-default",
                displayName = "eSpeak Default",
                primaryLanguage = lang,
                supportedLanguages = listOf(lang),
                gender = "Unknown"
            )
            isCommandAvailable("espeak-ng") -> Voice(
                name = "espeak-ng-default",
                displayName = "eSpeak-NG Default",
                primaryLanguage = lang,
                supportedLanguages = listOf(lang),
                gender = "Unknown"
            )
            isCommandAvailable("festival") -> Voice(
                name = "festival-default",
                displayName = "Festival Default",
                primaryLanguage = "en-US",
                supportedLanguages = listOf("en-US"),
                gender = "Unknown"
            )
            isCommandAvailable("say") -> Voice(
                name = "say-default",
                displayName = "macOS Default",
                primaryLanguage = lang,
                supportedLanguages = listOf(lang),
                gender = "Unknown"
            )
            else -> Voice(
                name = "no-tts",
                displayName = "No TTS Available",
                primaryLanguage = lang,
                supportedLanguages = listOf(lang),
                gender = "Unknown"
            )
        }
    }
}
