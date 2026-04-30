package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice as AndroidVoice
import io.github.jdreioe.wingmate.domain.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
    actual suspend fun getSystemVoices(): List<Voice> = withContext(Dispatchers.Main) {
        val context = getContext()
        val bootstrapTts = createInitializedTts(context) ?: return@withContext listOf(getDefaultSystemVoice())

        try {
            val engineInfos = runCatching { bootstrapTts.engines }.getOrDefault(emptyList())
            val defaultEnginePackage = runCatching { bootstrapTts.defaultEngine }.getOrNull()
            val orderedEngineInfos = engineInfos.sortedWith(compareByDescending<TextToSpeech.EngineInfo> {
                it.name == defaultEnginePackage
            }.thenBy { it.label?.toString().orEmpty() })

            val discoveredVoices = mutableListOf<Voice>()
            for (engineInfo in orderedEngineInfos) {
                val engineTts = createInitializedTts(context, engineInfo.name) ?: continue
                try {
                    val engineVoices = runCatching { engineTts.voices }.getOrDefault(emptySet())

                    if (engineVoices.isNotEmpty()) {
                        discoveredVoices += engineVoices
                            .sortedBy { it.name.lowercase(Locale.US) }
                            .map { androidVoice -> androidVoice.toWingmateVoice(engineInfo) }
                    } else {
                        // Some engines expose only a default voice; include it so the engine remains selectable.
                        val defaultVoice = runCatching { engineTts.defaultVoice }.getOrNull()
                        if (defaultVoice != null) {
                            discoveredVoices += defaultVoice.toWingmateVoice(engineInfo)
                        }
                    }
                } finally {
                    engineTts.shutdown()
                }
            }

            discoveredVoices
                .distinctBy { it.name }
                .ifEmpty { listOf(getDefaultSystemVoice()) }
        } finally {
            bootstrapTts.shutdown()
        }
    }

    private suspend fun createInitializedTts(
        context: Context,
        enginePackage: String? = null,
    ): TextToSpeech? {
        return suspendCancellableCoroutine { continuation ->
            var tempTts: TextToSpeech? = null

            val listener = TextToSpeech.OnInitListener { status ->
                if (!continuation.isActive) {
                    tempTts?.shutdown()
                    return@OnInitListener
                }

                if (status == TextToSpeech.SUCCESS) {
                    continuation.resume(tempTts)
                } else {
                    tempTts?.shutdown()
                    continuation.resume(null)
                }
            }

            tempTts = if (enginePackage.isNullOrBlank()) {
                TextToSpeech(context, listener)
            } else {
                TextToSpeech(context, listener, enginePackage)
            }

            continuation.invokeOnCancellation {
                tempTts?.shutdown()
            }
        }
    }

    private fun AndroidVoice.toWingmateVoice(engineInfo: TextToSpeech.EngineInfo): Voice {
        val languageTag = locale?.toLanguageTag().orEmpty()
        val engineLabel = engineInfo.label?.toString()?.takeIf { it.isNotBlank() } ?: engineInfo.name
        return Voice(
            name = AndroidTtsVoiceId.encode(engineInfo.name, name),
            displayName = "$engineLabel - $name",
            primaryLanguage = languageTag.ifBlank { Locale.getDefault().toLanguageTag() },
            supportedLanguages = listOf(languageTag.ifBlank { Locale.getDefault().toLanguageTag() }),
            gender = inferGender(name, features)
        )
    }

    private fun inferGender(voiceName: String, featureSet: Set<String>?): String {
        val lowerName = voiceName.lowercase(Locale.US)
        val lowerFeatures = featureSet.orEmpty().joinToString(" ").lowercase(Locale.US)
        return when {
            "female" in lowerName || "female" in lowerFeatures -> "Female"
            "male" in lowerName || "male" in lowerFeatures -> "Male"
            else -> "Unknown"
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
