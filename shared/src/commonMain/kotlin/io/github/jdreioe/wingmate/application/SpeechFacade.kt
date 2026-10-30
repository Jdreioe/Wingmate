package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.ElevenLabsSpeechConfig
import io.github.jdreioe.wingmate.domain.ElevenLabsSpeechConfigStatus
import io.github.jdreioe.wingmate.infrastructure.ElevenLabsRequestException
import io.github.jdreioe.wingmate.domain.GoogleSpeechConfig
import io.github.jdreioe.wingmate.domain.GoogleSpeechConfigStatus
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.domain.SpeechServiceConfigStatus
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.withPreferredSupportedLanguage
import io.github.jdreioe.wingmate.infrastructure.AzureSpeechEndpoint
import io.github.jdreioe.wingmate.infrastructure.AzureSpeechEndpointResult
import io.github.jdreioe.wingmate.infrastructure.GoogleCloudRequestException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * A feature-scoped native boundary around voice selection and cloud speech
 * configuration. Playback goes through [CommunicationSessionFacade].
 *
 * Keep Koin out of this file: the constructor receives exactly the use cases
 * and services this feature needs. Suspend members throw only [NativeFailure]
 * or coroutine cancellation.
 */
class SpeechFacade(
    private val voiceUseCase: VoiceUseCase,
    private val settingsUseCase: SettingsUseCase,
    private val configRepository: ConfigRepository,
) {
    /** Select a voice and align the app's primary language with its language when it changes. */
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun selectVoiceAndMaybeUpdatePrimary(voice: Voice) = nativeCall {
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

    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun selectedVoice(): Voice? = nativeCall { voiceUseCase.selected() }

    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun listVoices(): List<Voice> = nativeCall { voiceUseCase.listForEngine(settingsUseCase.get().ttsEngine) }

    /** A failed download keeps and returns the cached catalog, so failures here are storage failures. */
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun refreshVoicesFromAzure(): List<Voice> = nativeCall { voiceUseCase.refreshFromAzure() }

    /** A failed download keeps and returns the cached catalog, so failures here are storage failures. */
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun refreshVoicesFromGoogle(): List<Voice> = nativeCall { voiceUseCase.refreshFromGoogle() }

    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun refreshVoicesFromElevenLabs(): List<Voice> = nativeCall { voiceUseCase.refreshFromElevenLabs() }

    /** Update the selected voice's language and align the app's primary language. */
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateSelectedVoiceLanguage(lang: String) = nativeCall {
        val selected = voiceUseCase.selected()
        if (selected != null && selected.selectedLanguage != lang) {
            voiceUseCase.select(selected.copy(selectedLanguage = lang))
        }
        val current = settingsUseCase.get()
        if (lang != current.primaryLanguage) {
            settingsUseCase.update(current.copy(primaryLanguage = lang))
        }
    }

    /**
     * Select the speech engine by [TtsEngine] name. Fails with
     * [NativeFailureKind.Validation] for an unknown name and keeps the current engine.
     */
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateTtsEngineNamed(engine: String) = nativeCall {
        val parsed = TtsEngine.entries.firstOrNull { it.name == engine }
            ?: throw NativeFailure(NativeFailureKind.Validation)
        settingsUseCase.update(settingsUseCase.get().copy(ttsEngine = parsed))
        Unit
    }

    /** Safe for native UI: deliberately never returns the saved subscription key. */
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun getSpeechConfig(): SpeechServiceConfigStatus = nativeCall { configRepository.getSpeechConfigStatus() }

    /**
     * Stores an Azure resource and switches to it. Fails with
     * [NativeFailureKind.Validation] when the endpoint is not an Azure Speech
     * endpoint or the key is blank.
     */
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun saveAzureSpeechConfig(endpoint: String, subscriptionKey: String) = nativeCall {
        if (!isValidAzureSpeechEndpoint(endpoint) || subscriptionKey.isBlank()) {
            throw NativeFailure(NativeFailureKind.Validation)
        }
        configRepository.saveSpeechConfig(SpeechServiceConfig(endpoint.trim(), subscriptionKey.trim()))
        settingsUseCase.update(settingsUseCase.get().copy(ttsEngine = TtsEngine.AZURE_USER_RESOURCE))
        Unit
    }

    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun getGoogleSpeechConfig(): GoogleSpeechConfigStatus =
        nativeCall { configRepository.getGoogleSpeechConfigStatus() }

    /**
     * Securely stores a candidate key only if Google accepts it for voice discovery.
     * A failed replacement restores the previous credential and engine selection.
     *
     * Fails with [NativeFailureKind.Validation] for a blank key, a key Google
     * refuses, or a project without voices; [NativeFailureKind.Network] when
     * Google cannot be reached or asks to retry; [NativeFailureKind.Persistence]
     * when the key or engine cannot be stored.
     */
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun saveValidatedGoogleSpeechConfig(apiKey: String): List<Voice> =
        saveValidatedGoogleSpeechConfig(apiKey) { voiceUseCase.loadGoogleCatalog() }

    /** [downloadVoices] only asks Google; saving the catalog is a separate, local step. */
    internal suspend fun saveValidatedGoogleSpeechConfig(
        apiKey: String,
        downloadVoices: suspend () -> List<Voice>,
    ): List<Voice> = nativeCall {
        val normalizedKey = apiKey.trim()
        if (normalizedKey.isEmpty()) throw NativeFailure(NativeFailureKind.Validation)
        val previousConfig = configRepository.getGoogleSpeechConfig()
        val previousSettings = settingsUseCase.get()
        try {
            configRepository.saveGoogleSpeechConfig(GoogleSpeechConfig(normalizedKey))
            val voices = verifiedGoogleVoices(downloadVoices)
            voiceUseCase.saveGoogleCatalog(voices)
            settingsUseCase.update(previousSettings.copy(ttsEngine = TtsEngine.GOOGLE_CLOUD))
            voices
        } catch (failure: Exception) {
            try {
                withContext(NonCancellable) {
                    if (previousConfig == null) {
                        configRepository.clearGoogleSpeechConfig()
                    } else {
                        configRepository.saveGoogleSpeechConfig(previousConfig)
                    }
                    settingsUseCase.update(previousSettings)
                }
            } catch (_: Exception) {
                // Cancellation wins; otherwise the previous key may not be back, which is a storage failure.
                if (failure !is CancellationException) throw NativeFailure(NativeFailureKind.Persistence)
            }
            throw failure
        }
    }

    /** Only Google's answer decides validity: a refused key or an empty catalog is not a usable key. */
    private suspend fun verifiedGoogleVoices(downloadVoices: suspend () -> List<Voice>): List<Voice> {
        val voices = try {
            downloadVoices()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: GoogleCloudRequestException) {
            throw NativeFailure(
                if (failure.rejectedCredential) NativeFailureKind.Validation else NativeFailureKind.Network,
            )
        } catch (_: Exception) {
            throw NativeFailure(NativeFailureKind.Network)
        }
        if (voices.isEmpty()) throw NativeFailure(NativeFailureKind.Validation)
        return voices
    }

    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun clearGoogleSpeechConfig() = nativeCall { configRepository.clearGoogleSpeechConfig() }

    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun getElevenLabsSpeechConfig(): ElevenLabsSpeechConfigStatus =
        nativeCall { configRepository.getElevenLabsSpeechConfigStatus() }

    /**
     * Securely stores a candidate key only if ElevenLabs accepts it for voice discovery.
     * A failed replacement restores the previous credential and engine selection.
     *
     * Fails with [NativeFailureKind.Validation] for a blank key, a key ElevenLabs
     * refuses, or a project without voices; [NativeFailureKind.Network] when
     * ElevenLabs cannot be reached or asks to retry; [NativeFailureKind.Persistence]
     * when the key or engine cannot be stored.
     */
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun saveValidatedElevenLabsSpeechConfig(apiKey: String): List<Voice> =
        saveValidatedElevenLabsSpeechConfig(apiKey) { voiceUseCase.loadElevenLabsCatalog() }

    /** [downloadVoices] only asks ElevenLabs; saving the catalog is a separate, local step. */
    internal suspend fun saveValidatedElevenLabsSpeechConfig(
        apiKey: String,
        downloadVoices: suspend () -> List<Voice>,
    ): List<Voice> = nativeCall {
        val normalizedKey = apiKey.trim()
        if (normalizedKey.isEmpty()) throw NativeFailure(NativeFailureKind.Validation)
        val previousConfig = configRepository.getElevenLabsSpeechConfig()
        val previousSettings = settingsUseCase.get()
        try {
            configRepository.saveElevenLabsSpeechConfig(ElevenLabsSpeechConfig(normalizedKey))
            val voices = verifiedElevenLabsVoices(downloadVoices)
            voiceUseCase.saveElevenLabsCatalog(voices)
            settingsUseCase.update(previousSettings.copy(ttsEngine = TtsEngine.ELEVENLABS))
            voices
        } catch (failure: Exception) {
            try {
                withContext(NonCancellable) {
                    if (previousConfig == null) {
                        configRepository.clearElevenLabsSpeechConfig()
                    } else {
                        configRepository.saveElevenLabsSpeechConfig(previousConfig)
                    }
                    settingsUseCase.update(previousSettings)
                }
            } catch (_: Exception) {
                // Cancellation wins; otherwise the previous key may not be back, which is a storage failure.
                if (failure !is CancellationException) throw NativeFailure(NativeFailureKind.Persistence)
            }
            throw failure
        }
    }

    /** Only ElevenLabs's answer decides validity: a refused key or an empty catalog is not a usable key. */
    private suspend fun verifiedElevenLabsVoices(downloadVoices: suspend () -> List<Voice>): List<Voice> {
        val voices = try {
            downloadVoices()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: ElevenLabsRequestException) {
            throw NativeFailure(
                if (failure.rejectedCredential) NativeFailureKind.Validation else NativeFailureKind.Network,
            )
        } catch (_: Exception) {
            throw NativeFailure(NativeFailureKind.Network)
        }
        if (voices.isEmpty()) throw NativeFailure(NativeFailureKind.Validation)
        return voices
    }

    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun clearElevenLabsSpeechConfig() = nativeCall { configRepository.clearElevenLabsSpeechConfig() }

    fun isValidAzureSpeechEndpoint(endpoint: String): Boolean =
        AzureSpeechEndpoint.parse(endpoint) is AzureSpeechEndpointResult.Valid
}
