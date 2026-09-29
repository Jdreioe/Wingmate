package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.GoogleSpeechConfig
import io.github.jdreioe.wingmate.domain.GoogleSpeechConfigStatus
import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.domain.SpeechServiceConfigStatus
import io.github.jdreioe.wingmate.domain.SpeechTextProcessor
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.withPreferredSupportedLanguage
import io.github.jdreioe.wingmate.infrastructure.AzureSpeechEndpoint
import io.github.jdreioe.wingmate.infrastructure.AzureSpeechEndpointResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * A feature-scoped native boundary around speech playback, voice selection,
 * and Azure speech configuration.
 *
 * Keep Koin out of this file: the constructor receives exactly the use cases
 * and services this feature needs. Failures propagate as suspend exceptions
 * (including coroutine cancellation) instead of being swallowed or converted.
 */
class SpeechFacade(
    private val speechService: SpeechService,
    private val voiceUseCase: VoiceUseCase,
    private val settingsUseCase: SettingsUseCase,
    private val configRepository: ConfigRepository,
) {
    /** Split text into speech segments honoring shorthand SSML pauses and language tags. */
    fun processSpeechText(text: String): List<SpeechSegment> = SpeechTextProcessor.processText(text)

    suspend fun speak(text: String) {
        speechService.speak(text)
    }

    suspend fun speakBoardSentence(text: String, cacheAudio: Boolean) {
        speechService.speakWithCachePolicy(text = text, cacheAudio = cacheAudio)
    }

    suspend fun pause() {
        speechService.pause()
    }

    suspend fun stop() {
        speechService.stop()
    }

    /** Select a voice and align the app's primary language with its language when it changes. */
    suspend fun selectVoiceAndMaybeUpdatePrimary(voice: Voice) {
        val current = settingsUseCase.get()
        val selectedVoice = voice.withPreferredSupportedLanguage(current.primaryLanguage)
        voiceUseCase.select(selectedVoice)
        val candidate = selectedVoice.selectedLanguage
            .takeIf { it.isNotEmpty() }
            ?: selectedVoice.primaryLanguage?.takeIf { it.isNotEmpty() }
            ?: current.primaryLanguage
        if (candidate != current.primaryLanguage) {
            settingsUseCase.update(current.copy(primaryLanguage = candidate))
        }
    }

    suspend fun selectedVoice(): Voice? = voiceUseCase.selected()

    suspend fun listVoices(): List<Voice> = voiceUseCase.listForEngine(settingsUseCase.get().ttsEngine)

    suspend fun refreshVoicesFromAzure(): List<Voice> = voiceUseCase.refreshFromAzure()

    suspend fun refreshVoicesFromGoogle(): List<Voice> = voiceUseCase.refreshFromGoogle()

    /** Update the selected voice's language and align the app's primary language. */
    suspend fun updateSelectedVoiceLanguage(lang: String) {
        val selected = voiceUseCase.selected()
        if (selected != null && selected.selectedLanguage != lang) {
            voiceUseCase.select(selected.copy(selectedLanguage = lang))
        }
        val current = settingsUseCase.get()
        if (lang != current.primaryLanguage) {
            settingsUseCase.update(current.copy(primaryLanguage = lang))
        }
    }

    /** Select the speech engine by [TtsEngine] name; unknown names fall back to system TTS. */
    suspend fun updateTtsEngineNamed(engine: String) {
        val parsed = runCatching { TtsEngine.valueOf(engine) }.getOrDefault(TtsEngine.SYSTEM)
        settingsUseCase.update(settingsUseCase.get().copy(ttsEngine = parsed))
    }

    /** Safe for native UI: deliberately never returns the saved subscription key. */
    suspend fun getSpeechConfig(): SpeechServiceConfigStatus = configRepository.getSpeechConfigStatus()

    suspend fun saveAzureSpeechConfig(endpoint: String, subscriptionKey: String) {
        configRepository.saveSpeechConfig(SpeechServiceConfig(endpoint.trim(), subscriptionKey.trim()))
        settingsUseCase.update(settingsUseCase.get().copy(ttsEngine = TtsEngine.AZURE_USER_RESOURCE))
    }

    suspend fun getGoogleSpeechConfig(): GoogleSpeechConfigStatus =
        configRepository.getGoogleSpeechConfigStatus()

    /**
     * Securely stores a candidate key only if Google accepts it for voice discovery.
     * A failed replacement restores the previous credential and engine selection.
     */
    suspend fun saveValidatedGoogleSpeechConfig(apiKey: String): List<Voice> =
        saveValidatedGoogleSpeechConfig(apiKey) { voiceUseCase.refreshFromGoogle() }

    internal suspend fun saveValidatedGoogleSpeechConfig(
        apiKey: String,
        refreshVoices: suspend () -> List<Voice>,
    ): List<Voice> {
        val normalizedKey = apiKey.trim()
        require(normalizedKey.isNotEmpty()) { "Google Cloud API key is required" }
        val previousConfig = configRepository.getGoogleSpeechConfig()
        val previousSettings = settingsUseCase.get()
        configRepository.saveGoogleSpeechConfig(GoogleSpeechConfig(normalizedKey))
        return try {
            val voices = refreshVoices()
            check(voices.isNotEmpty()) {
                "Google Cloud could not load voices. Check the API key, billing, restrictions, and network connection."
            }
            settingsUseCase.update(previousSettings.copy(ttsEngine = TtsEngine.GOOGLE_CLOUD))
            voices
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                if (previousConfig == null) {
                    configRepository.clearGoogleSpeechConfig()
                } else {
                    configRepository.saveGoogleSpeechConfig(previousConfig)
                }
                settingsUseCase.update(previousSettings)
            }
            throw failure
        }
    }

    suspend fun clearGoogleSpeechConfig() {
        configRepository.clearGoogleSpeechConfig()
    }

    fun isValidAzureSpeechEndpoint(endpoint: String): Boolean =
        AzureSpeechEndpoint.parse(endpoint) is AzureSpeechEndpointResult.Valid
}
