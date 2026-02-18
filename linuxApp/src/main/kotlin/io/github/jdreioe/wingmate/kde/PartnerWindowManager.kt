package io.github.jdreioe.wingmate.kde

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Manages the partner window display lifecycle for the Linux/KDE app.
 *
 * Polls USB for FTDI device presence, reacts to [SettingsManager] changes,
 * and mirrors text to the EVE display when enabled and connected.
 */
class PartnerWindowManager(
    private val settingsManager: SettingsManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var driver: PartnerWindowDriver? = null
    private var mirroringJob: Job? = null

    /** Whether an FTDI device is currently detected on the USB bus. */
    private val _deviceConnected = MutableStateFlow(false)
    val deviceConnected: StateFlow<Boolean> = _deviceConnected.asStateFlow()

    /** Whether the driver is currently active and mirroring text. */
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** Current text to display, updated from the QML frontend. */
    private val _displayText = MutableStateFlow("")

    /**
     * Update the text shown on the partner display.
     * Call this from the API endpoint when the user types.
     */
    fun updateText(text: String) {
        _displayText.value = text
    }

    /**
     * Start the manager. Polls for device presence and reacts to settings.
     */
    fun start() {
        println("[PartnerWindow] Manager starting")

        // Poll for device presence (USB hot-plug detection)
        scope.launch {
            while (isActive) {
                val connected = try {
                    PartnerWindowDriver.isDeviceConnected()
                } catch (e: Exception) {
                    false
                }
                if (_deviceConnected.value != connected) {
                    _deviceConnected.value = connected
                    println("[PartnerWindow] FTDI device connected: $connected")
                }
                delay(3000)
            }
        }

        // React to setting changes + device presence
        scope.launch {
            combine(
                settingsManager.settings.map { it?.partnerWindowEnabled ?: false },
                _deviceConnected
            ) { enabled, connected ->
                Pair(enabled, connected)
            }.distinctUntilChanged().collect { (enabled, connected) ->
                println("[PartnerWindow] State changed: enabled=$enabled, connected=$connected")
                if (enabled && connected) {
                    startMirroring()
                } else {
                    stopMirroring()
                }
            }
        }
    }

    /**
     * Stop the manager and release all resources.
     */
    fun stop() {
        println("[PartnerWindow] Manager stopping")
        stopMirroring()
        scope.cancel()
    }

    private fun startMirroring() {
        if (_active.value) return
        println("[PartnerWindow] Starting mirroring")

        try {
            val d = PartnerWindowDriver()
            d.open()
            d.init()
            driver = d
            _active.value = true

            // Subscribe to text changes with debouncing
            mirroringJob = scope.launch {
                _displayText
                    .debounce(100) // 100ms debounce to avoid overwhelming SPI bus
                    .collect { text ->
                        try {
                            val displayText = text.ifEmpty { " " } // EVE needs at least 1 char
                            driver?.displayText(displayText)
                        } catch (e: Exception) {
                            println("[PartnerWindow] Failed to update display: ${e.message}")
                            stopMirroring()
                        }
                    }
            }

            println("[PartnerWindow] Mirroring active")
        } catch (e: Exception) {
            println("[PartnerWindow] Failed to start: ${e.message}")
            e.printStackTrace()
            _active.value = false
            driver = null
        }
    }

    private fun stopMirroring() {
        if (!_active.value && driver == null) return
        println("[PartnerWindow] Stopping mirroring")

        mirroringJob?.cancel()
        mirroringJob = null

        try {
            driver?.shutdown()
        } catch (e: Exception) {
            println("[PartnerWindow] Error shutting down display: ${e.message}")
        }
        try {
            driver?.close()
        } catch (e: Exception) {
            println("[PartnerWindow] Error closing driver: ${e.message}")
        }

        driver = null
        _active.value = false
    }
}
