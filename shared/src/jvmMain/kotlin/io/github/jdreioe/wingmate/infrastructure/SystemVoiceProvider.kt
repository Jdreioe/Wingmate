package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.Voice
import java.util.*

/**
 * JVM implementation of system TTS voice provider
 * Note: JVM doesn't have built-in TTS, so this is a stub implementation
 */
actual class SystemVoiceProvider actual constructor() {
    
    /**
     * Get available system TTS voices (empty on JVM)
     */
    actual suspend fun getSystemVoices(): List<Voice> {
        return emptyList() // JVM doesn't have built-in TTS
    }
    
    /**
     * Get default system voice (stub for JVM)
     */
    actual fun getDefaultSystemVoice(): Voice {
        val locale = Locale.getDefault()
        return Voice(
            name = "jvm-stub",
            displayName = "System TTS not available on JVM",
            primaryLanguage = locale.toLanguageTag(),
            supportedLanguages = listOf(locale.toLanguageTag()),
            gender = "Unknown"
        )
    }
}
