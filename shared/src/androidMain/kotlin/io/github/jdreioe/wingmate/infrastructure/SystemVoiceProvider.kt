package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice as AndroidVoice
import io.github.jdreioe.wingmate.domain.Voice
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.*
import kotlin.coroutines.resume

/**
 * Android implementation of system TTS voice provider
 */
actual class SystemVoiceProvider actual constructor() {
    
    private fun getContext(): Context {
        // Get context from Koin or other DI system
        val koin = org.koin.core.context.GlobalContext.getOrNull()
        return koin?.get<Context>() ?: throw IllegalStateException("Context not available")
    }
    
    /**
     * Get available system TTS voices
     */
    actual suspend fun getSystemVoices(): List<Voice> {
        return suspendCancellableCoroutine { continuation ->
            val context = getContext()
            // Initialize TTS to get available voices
            var tempTts: TextToSpeech? = null
            tempTts = TextToSpeech(context) { status: Int ->
                if (status == TextToSpeech.SUCCESS) {
                    try {
                        val voices = tempTts?.voices ?: emptySet<AndroidVoice>()
                        val voiceList = voices.map { androidVoice: AndroidVoice ->
                            Voice(
                                name = androidVoice.name,
                                displayName = androidVoice.name,
                                primaryLanguage = androidVoice.locale.toLanguageTag(),
                                supportedLanguages = listOf(androidVoice.locale.toLanguageTag()),
                                gender = when {
                                    androidVoice.name.contains("female", ignoreCase = true) -> "Female"
                                    androidVoice.name.contains("male", ignoreCase = true) -> "Male" 
                                    else -> "Unknown"
                                }
                            )
                        }
                        continuation.resume(voiceList)
                    } catch (e: Exception) {
                        continuation.resume(emptyList())
                    } finally {
                        tempTts?.shutdown()
                    }
                } else {
                    continuation.resume(emptyList())
                    tempTts?.shutdown()
                }
            }
            
            continuation.invokeOnCancellation {
                tempTts?.shutdown()
            }
        }
    }
    
    /**
     * Get default system voice based on current locale
     */
    actual fun getDefaultSystemVoice(): Voice {
        val locale = Locale.getDefault()
        return Voice(
            name = "system-default",
            displayName = "System Default (${locale.displayLanguage})",
            primaryLanguage = locale.toLanguageTag(),
            supportedLanguages = listOf(locale.toLanguageTag()),
            gender = "Unknown"
        )
    }
}
