package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.Voice
import kotlinx.cinterop.*
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier

/**
 * iOS implementation of system TTS voice provider using AVSpeechSynthesis
 */
@OptIn(ExperimentalForeignApi::class)
actual class SystemVoiceProvider actual constructor() {
    
    /**
     * Get available system TTS voices from AVSpeechSynthesis
     */
    actual suspend fun getSystemVoices(): List<Voice> {
        val voices = AVSpeechSynthesisVoice.speechVoices()
        return voices.mapNotNull { voiceObj ->
            val voice = voiceObj as? AVSpeechSynthesisVoice ?: return@mapNotNull null
            try {
                Voice(
                    name = voice.identifier,
                    displayName = voice.name,
                    primaryLanguage = voice.language,
                    supportedLanguages = listOf(voice.language),
                    gender = "Unknown" // iOS doesn't expose gender info reliably
                )
            } catch (e: Exception) {
                null
            }
        }
    }
    
    /**
     * Get default system voice based on current locale
     */
    actual fun getDefaultSystemVoice(): Voice {
        val currentLocale = NSLocale.currentLocale
        val languageTag = currentLocale.localeIdentifier
        
        // Try to get the default voice for current language
        val defaultVoice = AVSpeechSynthesisVoice.voiceWithLanguage(languageTag)
        
        return if (defaultVoice != null) {
            Voice(
                name = defaultVoice.identifier,
                displayName = defaultVoice.name,
                primaryLanguage = defaultVoice.language,
                supportedLanguages = listOf(defaultVoice.language),
                gender = "Unknown"
            )
        } else {
            // Fallback voice - try with just language code
            val fallbackLanguage = languageTag.substringBefore("_").substringBefore("-")
            val fallbackVoice = AVSpeechSynthesisVoice.voiceWithLanguage(fallbackLanguage)
            
            if (fallbackVoice != null) {
                Voice(
                    name = fallbackVoice.identifier,
                    displayName = fallbackVoice.name,
                    primaryLanguage = fallbackVoice.language,
                    supportedLanguages = listOf(fallbackVoice.language),
                    gender = "Unknown"
                )
            } else {
                // Final fallback voice
                Voice(
                    name = "system-default",
                    displayName = "System Default",
                    primaryLanguage = "en-US",
                    supportedLanguages = listOf("en-US"),
                    gender = "Unknown"
                )
            }
        }
    }
}
