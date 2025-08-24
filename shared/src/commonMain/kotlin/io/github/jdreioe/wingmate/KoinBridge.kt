package io.github.jdreioe.wingmate

import io.github.jdreioe.wingmate.application.bloc.PhraseListStore
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.di.appModule
import io.github.jdreioe.wingmate.initKoin
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.domain.Voice
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

class KoinBridge : KoinComponent {
    fun phraseListStore(): PhraseListStore = get()
    // Safe variant to avoid throwing across Swift bridge
    fun phraseListStoreOrNull(): PhraseListStore? = try { get<PhraseListStore>() } catch (_: Throwable) { null }

    // --- Simple bridging helpers for Swift UI ---
    suspend fun speak(text: String) {
        try {
            get<SpeechService>().speak(text)
        } catch (t: Throwable) {
            logger.warn(t) { "speak() failed; swallowing to avoid Swift bridge crash" }
        }
    }

    suspend fun pause() {
        try {
            get<SpeechService>().pause()
        } catch (t: Throwable) {
            logger.warn(t) { "pause() failed; swallowing to avoid Swift bridge crash" }
        }
    }

    suspend fun stop() {
        try {
            get<SpeechService>().stop()
        } catch (t: Throwable) {
            logger.warn(t) { "stop() failed; swallowing to avoid Swift bridge crash" }
        }
    }

    suspend fun selectVoiceAndMaybeUpdatePrimary(voice: Voice) {
        val voiceUseCase: VoiceUseCase = get()
    try { println("DEBUG: KoinBridge.selectVoiceAndMaybeUpdatePrimary() called for '\${voice.name}' selectedLang='\${voice.selectedLanguage}'") } catch (_: Throwable) {}
        voiceUseCase.select(voice)

        // Optionally align Settings.primaryLanguage with selected voice
        val settingsUseCase: SettingsUseCase = get()
        val current = settingsUseCase.get()
        val candidate = when {
            !voice.selectedLanguage.isNullOrEmpty() -> voice.selectedLanguage!!
            !voice.primaryLanguage.isNullOrEmpty() -> voice.primaryLanguage!!
            else -> current.primaryLanguage
        }
        if (candidate != current.primaryLanguage) {
            settingsUseCase.update(current.copy(primaryLanguage = candidate))
        }
    }

    suspend fun updatePrimaryLanguage(lang: String) {
        val settingsUseCase: SettingsUseCase = get()
        val current = settingsUseCase.get()
        if (lang != current.primaryLanguage) {
            settingsUseCase.update(current.copy(primaryLanguage = lang))
        }
    }

    // Update both the selected voice's selectedLanguage and the app Settings.primaryLanguage
    suspend fun updateSelectedVoiceLanguage(lang: String) {
        val voiceUseCase: VoiceUseCase = get()
        val settingsUseCase: SettingsUseCase = get()

        // Update selected voice, if any
        val selected = voiceUseCase.selected()
        if (selected != null && selected.selectedLanguage != lang) {
            voiceUseCase.select(selected.copy(selectedLanguage = lang))
        }
        // Align settings primary language
        val current = settingsUseCase.get()
        if (lang != current.primaryLanguage) {
            settingsUseCase.update(current.copy(primaryLanguage = lang))
        }
    }

    suspend fun selectedVoice(): Voice? = get<VoiceUseCase>().selected()

    // Debug helper: return the runtime class name of the bound VoiceRepository
    fun debugVoiceRepositoryName(): String = try { get<io.github.jdreioe.wingmate.domain.VoiceRepository>()::class.simpleName ?: "unknown" } catch (_: Throwable) { "error" }

    suspend fun listVoices(): List<Voice> = get<VoiceUseCase>().list()

    suspend fun refreshVoicesFromAzure(): List<Voice> = get<VoiceUseCase>().refreshFromAzure()

    suspend fun getSpeechConfig(): SpeechServiceConfig? = get<ConfigRepository>().getSpeechConfig()

    suspend fun saveSpeechConfig(config: SpeechServiceConfig) {
        get<ConfigRepository>().saveSpeechConfig(config)
    }

    companion object {
        private var started: Boolean = false
    fun start() {
            if (started) return
            try {
                initKoin(appModule)
            } catch (_: Throwable) {
                // If already started, ignore
            } finally {
                started = true
            }
        }
    }
}

private val logger = KotlinLogging.logger {}

