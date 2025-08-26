package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.domain.ConfigRepository
import org.koin.core.context.GlobalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

class DesktopSpeechService : SpeechService {
    private val log = LoggerFactory.getLogger("DesktopSpeechService")

    override suspend fun speak(text: String, voice: Voice?, pitch: Double?, rate: Double?) {
        // Check user preference for TTS engine first
        val koin = GlobalContext.getOrNull()
        val settingsRepo = koin?.let { runCatching { it.get<io.github.jdreioe.wingmate.domain.SettingsRepository>() }.getOrNull() }
        val uiSettings = settingsRepo?.let { runCatching { it.get() }.getOrNull() }
        
        // If user prefers system TTS, show error since desktop doesn't support it
        if (uiSettings?.useSystemTts == true) {
            log.warn("System TTS not available on desktop platform")
            throw RuntimeException("System TTS is not available on desktop. Please use Azure TTS instead.")
        }
        
        // Use Azure TTS for desktop
        val configRepo = koin?.let { runCatching { it.get<ConfigRepository>() }.getOrNull() }
        val cfg = configRepo?.let { runCatching { it.getSpeechConfig() }.getOrNull() }
        
        if (cfg == null) {
            throw RuntimeException("Azure Speech configuration not available. Please configure Azure TTS settings.")
        }
        
        val v = voice ?: Voice(name = "en-US-JennyNeural", primaryLanguage = "en-US")
        if (log.isDebugEnabled) {
            val name = v.name
            val primaryLanguage = v.primaryLanguage
            log.debug("Using voice: $name with $primaryLanguage")
        }
        
        // Run synthesis on IO thread
        withContext(Dispatchers.IO) {
            log.info("speak() called on thread={}", Thread.currentThread().name)
            
            try {
                log.debug("Starting audio synthesis for '{}'", text)
                
                // For now, this is a stub implementation
                // In a real implementation, you would:
                // 1. Make HTTP requests to Azure Speech Services REST API
                // 2. Or integrate with Azure Speech SDK for JVM (if available)
                // 3. Play the returned audio data
                
                log.debug("Desktop Azure TTS synthesis (stub): voice={}, text={}", v.name, text)
                log.info("Note: This is a stub implementation. Real Azure TTS integration needed for desktop.")
                
            } catch (e: Exception) {
                log.error("Error during speech synthesis", e)
                throw e
            }
        }
    }

    override suspend fun pause() {
        log.debug("pause() - stub implementation")
        // Stub implementation
    }

    override suspend fun stop() {
        log.debug("stop() - stub implementation")
        // Stub implementation
    }
}
