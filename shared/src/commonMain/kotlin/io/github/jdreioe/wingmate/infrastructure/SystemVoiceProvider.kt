package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.Voice

/**
 * Platform-specific system voice provider
 */
expect class SystemVoiceProvider() {
    suspend fun getSystemVoices(): List<Voice>
    fun getDefaultSystemVoice(): Voice
}
