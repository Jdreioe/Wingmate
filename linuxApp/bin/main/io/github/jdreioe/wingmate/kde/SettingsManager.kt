package io.github.jdreioe.wingmate.kde

import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.core.context.GlobalContext

/**
 * Manages application settings.
 */
class SettingsManager {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private val settingsRepository: SettingsRepository by lazy {
        GlobalContext.get().get()
    }
    
    private val _settings = MutableStateFlow<Settings?>(null)
    val settings: StateFlow<Settings?> = _settings.asStateFlow()
    
    init {
        loadSettings()
    }
    
    private fun loadSettings() {
        scope.launch {
            println("[PERSISTENCE] SettingsManager: Starting to load settings...")
            val loadedSettings = runCatching {
                settingsRepository.get()
            }.getOrNull() ?: Settings()
            println("[PERSISTENCE] SettingsManager: Loaded settings: $loadedSettings")
            _settings.value = loadedSettings
        }
    }
    
    fun updateSettings(newSettings: Settings) {
        scope.launch {
            println("[PERSISTENCE] SettingsManager: Updating settings...")
            settingsRepository.update(newSettings)
            _settings.value = newSettings
            println("[PERSISTENCE] SettingsManager: Settings updated.")
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
