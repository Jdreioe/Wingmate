package io.github.jdreioe.wingmate.kde

import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.domain.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.core.context.GlobalContext

/**
 * Thin read-model over the shared [SettingsUseCase] (single source of truth,
 * same as iOS). Keeps a StateFlow facade for the HTTP bridge and Rust UI while
 * routing every read/write through shared logic.
 */
class SettingsManager {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val settingsUseCase: SettingsUseCase by lazy {
        GlobalContext.get().get()
    }

    private val _settings = MutableStateFlow<Settings?>(null)
    val settings: StateFlow<Settings?> = _settings.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        scope.launch {
            _settings.value = runCatching { settingsUseCase.get() }.getOrNull() ?: Settings()
        }
    }

    fun updateSettings(newSettings: Settings) {
        scope.launch {
            runCatching { settingsUseCase.update(newSettings) }
            _settings.value = newSettings
        }
    }

    fun updateLanguage(language: String) {
        _settings.value?.let { current ->
            updateSettings(current.copy(language = language))
        }
    }

    fun updateVoice(voice: String) {
        _settings.value?.let { current ->
            updateSettings(current.copy(voice = voice))
        }
    }

    fun updateSpeechRate(rate: Float) {
        _settings.value?.let { current ->
            updateSettings(current.copy(speechRate = rate))
        }
    }

    fun cleanup() {
        scope.cancel()
    }
}
