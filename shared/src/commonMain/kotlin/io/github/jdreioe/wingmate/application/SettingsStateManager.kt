package io.github.jdreioe.wingmate.application

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Reactive settings state manager that provides live updates to UI components
 * when settings change, enabling hot reload of UI settings without app restart.
 */
class SettingsStateManager(
    private val settingsRepository: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    
    init {
        // Load initial settings
        scope.launch {
            try {
                val initialSettings = settingsRepository.get()
                _settings.value = initialSettings
            } catch (e: Exception) {
                // Fall back to default settings if loading fails
                _settings.value = Settings()
            }
        }
    }
    
    /**
     * Update settings and notify all observers immediately
     */
    suspend fun updateSettings(settings: Settings): Settings {
        return try {
            val updatedSettings = settingsRepository.update(settings)
            _settings.value = updatedSettings
            updatedSettings
        } catch (e: Exception) {
            // If update fails, keep current state
            throw e
        }
    }
    
    /**
     * Update settings using a transform function
     */
    suspend fun updateSettings(transform: (Settings) -> Settings): Settings {
        val currentSettings = _settings.value
        val newSettings = transform(currentSettings)
        return updateSettings(newSettings)
    }
    
    /**
     * Get current settings value synchronously
     */
    fun getCurrentSettings(): Settings = _settings.value
    
    /**
     * Reload settings from repository (useful for external changes)
     */
    suspend fun reloadSettings() {
        try {
            val reloadedSettings = settingsRepository.get()
            _settings.value = reloadedSettings
        } catch (e: Exception) {
            // Keep current settings if reload fails
        }
    }
}