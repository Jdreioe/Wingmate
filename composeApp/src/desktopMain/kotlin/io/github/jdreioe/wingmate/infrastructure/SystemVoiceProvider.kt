package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.Voice
import java.util.*

/**
 * Desktop implementation of system TTS voice provider
 * Note: Desktop doesn't have built-in TTS, so this is a stub implementation
 */
actual class SystemVoiceProvider actual constructor() {
    
    /**
     * Get available system TTS voices (empty on desktop)
     */
    actual suspend fun getSystemVoices(): List<Voice> {
        return emptyList() // Desktop doesn't have built-in TTS
    }
    
    /**
     * Get default system voice (stub for desktop)
     */
    actual fun getDefaultSystemVoice(): Voice {
        val locale = Locale.getDefault()
        return Voice(
            name = "desktop-stub",
            displayName = "System TTS not available on desktop",
            primaryLanguage = locale.toLanguageTag(),
            supportedLanguages = listOf(locale.toLanguageTag()),
            gender = "Unknown"
        )
    }
}
