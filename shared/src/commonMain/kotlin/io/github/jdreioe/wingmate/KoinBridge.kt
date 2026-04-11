package io.github.jdreioe.wingmate

import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.application.bloc.PhraseListStore
import io.github.jdreioe.wingmate.di.appModule
import io.github.jdreioe.wingmate.initKoin
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import kotlin.time.Clock
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

class KoinBridge : KoinComponent {
    fun phraseListStore(): PhraseListStore = get()
    // Safe variant to avoid throwing across Swift bridge
    fun phraseListStoreOrNull(): PhraseListStore? = try { get<PhraseListStore>() } catch (_: Throwable) { null }

    // --- Sharing helpers ---
    fun shareAudio(path: String) {
        try {
            get<io.github.jdreioe.wingmate.platform.ShareService>().shareAudio(path)
        } catch (_: Throwable) {}
    }

    fun copyAudio(path: String) {
        try {
            get<io.github.jdreioe.wingmate.platform.AudioClipboard>().copyAudioFile(path)
        } catch (_: Throwable) {}
    }

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

    suspend fun updateSecondaryLanguage(lang: String) {
        val settingsUseCase: SettingsUseCase = get()
        val current = settingsUseCase.get()
        if (lang != current.secondaryLanguage) {
            settingsUseCase.update(current.copy(secondaryLanguage = lang))
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

    suspend fun getSettings(): Settings = get<SettingsUseCase>().get()

    suspend fun updateScanningEnabled(enabled: Boolean) {
        val settingsUseCase: SettingsUseCase = get()
        val current = settingsUseCase.get()
        if (current.scanningEnabled != enabled) {
            settingsUseCase.update(current.copy(scanningEnabled = enabled))
        }
    }

    suspend fun updateScanPlaybackAreaEnabled(enabled: Boolean) {
        val settingsUseCase: SettingsUseCase = get()
        val current = settingsUseCase.get()
        if (current.scanPlaybackAreaEnabled != enabled) {
            settingsUseCase.update(current.copy(scanPlaybackAreaEnabled = enabled))
        }
    }

    suspend fun updateScanInputFieldEnabled(enabled: Boolean) {
        val settingsUseCase: SettingsUseCase = get()
        val current = settingsUseCase.get()
        if (current.scanInputFieldEnabled != enabled) {
            settingsUseCase.update(current.copy(scanInputFieldEnabled = enabled))
        }
    }

    suspend fun updateScanPhraseGridEnabled(enabled: Boolean) {
        val settingsUseCase: SettingsUseCase = get()
        val current = settingsUseCase.get()
        if (current.scanPhraseGridEnabled != enabled) {
            settingsUseCase.update(current.copy(scanPhraseGridEnabled = enabled))
        }
    }

    suspend fun updateScanCategoryItemsEnabled(enabled: Boolean) {
        val settingsUseCase: SettingsUseCase = get()
        val current = settingsUseCase.get()
        if (current.scanCategoryItemsEnabled != enabled) {
            settingsUseCase.update(current.copy(scanCategoryItemsEnabled = enabled))
        }
    }

    suspend fun updateScanTopBarEnabled(enabled: Boolean) {
        val settingsUseCase: SettingsUseCase = get()
        val current = settingsUseCase.get()
        if (current.scanTopBarEnabled != enabled) {
            settingsUseCase.update(current.copy(scanTopBarEnabled = enabled))
        }
    }

    suspend fun updateScanPhraseGridOrder(order: String) {
        val normalized = when (order.lowercase()) {
            "column-major" -> "column-major"
            "linear" -> "linear"
            else -> "row-major"
        }
        val settingsUseCase: SettingsUseCase = get()
        val current = settingsUseCase.get()
        if (current.scanPhraseGridOrder != normalized) {
            settingsUseCase.update(current.copy(scanPhraseGridOrder = normalized))
        }
    }

    suspend fun updateScanDwellTimeSeconds(seconds: Float) {
        val clamped = seconds.coerceIn(0.3f, 2.0f)
        val settingsUseCase: SettingsUseCase = get()
        val current = settingsUseCase.get()
        if (current.scanDwellTimeSeconds != clamped) {
            settingsUseCase.update(current.copy(scanDwellTimeSeconds = clamped))
        }
    }

    suspend fun updateScanAutoAdvanceSeconds(seconds: Float) {
        val clamped = seconds.coerceIn(0.5f, 3.0f)
        val settingsUseCase: SettingsUseCase = get()
        val current = settingsUseCase.get()
        if (current.scanAutoAdvanceSeconds != clamped) {
            settingsUseCase.update(current.copy(scanAutoAdvanceSeconds = clamped))
        }
    }

    suspend fun saveSpeechConfig(config: SpeechServiceConfig) {
        get<ConfigRepository>().saveSpeechConfig(config)
    }

    // Swift-friendly bridge to update phrase recording path
    fun updatePhraseRecording(phraseId: String, recordingPath: String?) {
        try {
            phraseListStore().accept(PhraseListStore.Intent.UpdatePhraseRecording(id = phraseId, recordingPath = recordingPath))
        } catch (t: Throwable) {
            logger.warn(t) { "updatePhraseRecording() failed; swallowing to avoid Swift bridge crash" }
        }
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

    // --- History helpers ---
    // Returns the list of said items mapped as Phrase objects for easy Swift UI rendering
    suspend fun listHistoryAsPhrases(): List<Phrase> {
        return try {
            val said = get<SaidTextRepository>().list()
            val now = Clock.System.now().toEpochMilliseconds()
            said.map { s ->
                Phrase(
                    id = "history-" + (s.id?.toString() ?: (s.createdAt ?: s.date ?: now).toString()),
                    text = s.saidText ?: "",
                    name = null,
                    backgroundColor = "#00000000",
                    parentId = null,
                    createdAt = (s.createdAt ?: s.date ?: now),
                    recordingPath = s.audioFilePath
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    // --- Prediction Helpers ---
    // Bridge to TextPredictionService
    suspend fun predict(context: String, maxWords: Int, maxLetters: Int): io.github.jdreioe.wingmate.domain.PredictionResult {
        return try {
            get<io.github.jdreioe.wingmate.domain.TextPredictionService>().predict(context, maxWords, maxWords)
        } catch (_: Throwable) {
            io.github.jdreioe.wingmate.domain.PredictionResult()
        }
    }

    suspend fun trainPredictionModel() {
        try {
            val service = get<io.github.jdreioe.wingmate.domain.TextPredictionService>()
            val repo = get<io.github.jdreioe.wingmate.domain.SaidTextRepository>()
            val list = repo.list()
            
            // If it's the n-gram service, we can try to load base dict first
            if (service is io.github.jdreioe.wingmate.infrastructure.SimpleNGramPredictionService) {
                // Determine primary language
                val settings = get<SettingsUseCase>().get()
                val lang = settings.primaryLanguage
                
                // Try to load dict
                 try {
                    val loader = get<io.github.jdreioe.wingmate.infrastructure.DictionaryLoader>()
                    val dict = loader.loadDictionary(lang)
                    if (dict.isNotEmpty()) {
                        service.setBaseLanguage(dict)
                        // Train history on top without clearing
                        service.train(list, false)
                        return
                    }
                } catch (_: Throwable) {}
                 // Fallback: train just history (clearing old)
                service.train(list, true)
            } else {
                service.train(list)
            }
        } catch (t: Throwable) {
            logger.warn(t) { "trainPredictionModel() failed" }
        }
    }

    suspend fun learnPhrase(text: String) {
        try {
            val service = get<io.github.jdreioe.wingmate.domain.TextPredictionService>()
            if (service is io.github.jdreioe.wingmate.infrastructure.SimpleNGramPredictionService) {
                service.learnPhrase(text)
            }
        } catch (_: Throwable) {}
    }

    // --- Pronunciation Dictionary Helpers ---
    suspend fun listPronunciations(): List<io.github.jdreioe.wingmate.domain.PronunciationEntry> {
        return try {
            get<io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository>().getAll()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun addPronunciation(word: String, phoneme: String, alphabet: String) {
        try {
            get<io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository>().add(
                io.github.jdreioe.wingmate.domain.PronunciationEntry(word, phoneme, alphabet)
            )
        } catch (_: Throwable) {}
    }

    suspend fun deletePronunciation(word: String) {
        try {
            get<io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository>().delete(word)
        } catch (_: Throwable) {}
    }
}

private val logger = KotlinLogging.logger {}

