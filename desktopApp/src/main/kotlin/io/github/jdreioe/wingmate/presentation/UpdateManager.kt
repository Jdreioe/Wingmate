package io.github.jdreioe.wingmate.presentation

import io.github.jdreioe.wingmate.domain.*
import io.github.jdreioe.wingmate.application.SettingsUseCase
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

class UpdateManager(
    private val updateService: UpdateService,
    private val settingsUseCase: SettingsUseCase
) {
    
    private val log = LoggerFactory.getLogger("UpdateManager")
    
    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo = _updateInfo.asStateFlow()
    
    private val _updateStatus = MutableStateFlow(UpdateStatus.UP_TO_DATE)
    val updateStatus = _updateStatus.asStateFlow()
    
    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog = _showUpdateDialog.asStateFlow()
    
    private val _showUpdateNotification = MutableStateFlow(false)
    val showUpdateNotification = _showUpdateNotification.asStateFlow()
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()
    
    private var periodicCheckJob: Job? = null
    
    init {
        startPeriodicUpdateCheck()
    }
    
    fun checkForUpdates() {
        GlobalScope.launch {
            try {
                _updateStatus.value = UpdateStatus.CHECKING
                val updateInfo = updateService.checkForUpdates()
                
                if (updateInfo != null) {
                    _updateInfo.value = updateInfo
                    _updateStatus.value = UpdateStatus.AVAILABLE
                    _showUpdateNotification.value = true
                    log.info("Update available: ${updateInfo.version.version}")
                } else {
                    _updateStatus.value = UpdateStatus.UP_TO_DATE
                    _showUpdateNotification.value = false
                    log.info("No updates available")
                }
                
                // Update last check time
                updateLastCheckTime()
                
            } catch (e: Exception) {
                _updateStatus.value = UpdateStatus.ERROR
                _errorMessage.value = "Failed to check for updates: ${e.message}"
                log.error("Failed to check for updates", e)
            }
        }
    }
    
    fun installUpdate(updateInfo: UpdateInfo) {
        GlobalScope.launch {
            try {
                _updateStatus.value = UpdateStatus.DOWNLOADING
                
                // Download the update
                val downloadResult = updateService.downloadUpdate(updateInfo)
                if (downloadResult.isFailure) {
                    _updateStatus.value = UpdateStatus.ERROR
                    _errorMessage.value = "Failed to download update: ${downloadResult.exceptionOrNull()?.message}"
                    return@launch
                }
                
                val downloadPath = downloadResult.getOrThrow()
                _updateStatus.value = UpdateStatus.DOWNLOADED
                
                // Give user a moment to see download completed
                delay(1000)
                
                // Install the update
                _updateStatus.value = UpdateStatus.INSTALLING
                val installResult = updateService.installUpdate(downloadPath)
                
                if (installResult.isFailure) {
                    _updateStatus.value = UpdateStatus.ERROR
                    _errorMessage.value = "Failed to install update: ${installResult.exceptionOrNull()?.message}"
                } else {
                    // Installation successful - app should restart automatically
                    log.info("Update installed successfully")
                }
                
            } catch (e: Exception) {
                _updateStatus.value = UpdateStatus.ERROR
                _errorMessage.value = "Update failed: ${e.message}"
                log.error("Update failed", e)
            }
        }
    }
    
    fun showUpdateDetails() {
        _showUpdateDialog.value = true
        _showUpdateNotification.value = false
    }
    
    fun hideUpdateDialog() {
        _showUpdateDialog.value = false
    }
    
    fun dismissUpdateNotification() {
        _showUpdateNotification.value = false
    }
    
    fun clearError() {
        _errorMessage.value = null
    }
    
    fun cleanup() {
        periodicCheckJob?.cancel()
    }
    
    private fun startPeriodicUpdateCheck() {
        periodicCheckJob = GlobalScope.launch {
            while (true) {
                try {
                    val settings = settingsUseCase.get()
                    if (settings.autoUpdateEnabled) {
                        val timeSinceLastCheck = System.currentTimeMillis() - settings.lastUpdateCheck
                        if (timeSinceLastCheck >= settings.checkUpdateInterval) {
                            checkForUpdates()
                        }
                    }
                    
                    // Check every hour
                    delay(60 * 60 * 1000L)
                } catch (e: Exception) {
                    log.error("Error in periodic update check", e)
                    delay(60 * 60 * 1000L) // Wait an hour before retrying
                }
            }
        }
    }
    
    private suspend fun updateLastCheckTime() {
        try {
            val currentSettings = settingsUseCase.get()
            val updatedSettings = currentSettings.copy(
                lastUpdateCheck = System.currentTimeMillis()
            )
            settingsUseCase.update(updatedSettings)
        } catch (e: Exception) {
            log.warn("Failed to update last check time", e)
        }
    }
    
    fun getCurrentVersion(): String = updateService.getCurrentVersion().version
}