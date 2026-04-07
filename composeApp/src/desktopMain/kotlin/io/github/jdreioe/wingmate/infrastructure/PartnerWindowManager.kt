package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.application.SettingsStateManager
import io.github.jdreioe.wingmate.presentation.DisplayTextBus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.slf4j.LoggerFactory

/**
 * Manages the partner window display lifecycle.
 *
 * Listens to [DisplayTextBus] for text changes and mirrors them to the
 * physical partner display when [Settings.partnerWindowEnabled] is true
 * and an FTDI device is connected.
 *
 * The manager also exposes [deviceConnected] as a StateFlow so the UI
 * can conditionally show/hide the settings toggle.
 */
class PartnerWindowManager(
    private val settingsStateManager: SettingsStateManager
) {
    private val log = LoggerFactory.getLogger("PartnerWindowManager")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var driver: PartnerWindowDriver? = null
    private var mirroringJob: Job? = null

    /** Whether an FTDI device is currently detected on the USB bus. */
    private val _deviceConnected = MutableStateFlow(false)
    val deviceConnected: StateFlow<Boolean> = _deviceConnected.asStateFlow()

    /** Whether the driver is currently active and mirroring text. */
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /**
     * Start the manager. Checks device presence, subscribes to settings
     * changes, and begins/stops mirroring accordingly.
     */
    fun start() {
        log.info("PartnerWindowManager starting")

        // Periodically poll for device presence (USB hot-plug detection)
        scope.launch {
            while (isActive) {
                val connected = try {
                    PartnerWindowDriver.isDeviceConnected()
                } catch (e: Exception) {
                    log.debug("Device detection failed: {}", e.message)
                    false
                }
                if (_deviceConnected.value != connected) {
                    _deviceConnected.value = connected
                    log.info("FTDI device connected: {}", connected)
                }
                delay(3000) // Poll every 3 seconds
            }
        }

        // React to setting changes + device presence
        scope.launch {
            combine(
                settingsStateManager.settings,
                _deviceConnected
            ) { settings, connected ->
                Pair(settings.partnerWindowEnabled, connected)
            }.distinctUntilChanged().collect { (enabled, connected) ->
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
        log.info("PartnerWindowManager stopping")
        stopMirroring()
        scope.cancel()
    }

    private fun startMirroring() {
        if (_active.value) return // Already mirroring
        log.info("Starting partner window mirroring")

        try {
            val d = PartnerWindowDriver()
            d.open()
            d.init()
            driver = d
            _active.value = true

            // Subscribe to text changes with debouncing
            mirroringJob = scope.launch {
                DisplayTextBus.text
                    .debounce(100) // 100ms debounce to avoid overwhelming SPI bus
                    .collect { text ->
                        try {
                            val displayText = text.ifEmpty { " " } // EVE needs at least 1 char
                            driver?.displayText(displayText)
                        } catch (e: Exception) {
                            log.error("Failed to update partner display: {}", e.message)
                            // If the device was disconnected, stop mirroring
                            stopMirroring()
                        }
                    }
            }

            log.info("Partner window mirroring active")
        } catch (e: Exception) {
            log.error("Failed to start partner window: {}", e.message)
            _active.value = false
            driver = null
        }
    }

    private fun stopMirroring() {
        if (!_active.value && driver == null) return
        log.info("Stopping partner window mirroring")

        mirroringJob?.cancel()
        mirroringJob = null

        try {
            driver?.shutdown()
        } catch (e: Exception) {
            log.warn("Error shutting down display: {}", e.message)
        }
        try {
            driver?.close()
        } catch (e: Exception) {
            log.warn("Error closing driver: {}", e.message)
        }

        driver = null
        _active.value = false
    }

    companion object {
        /**
         * Singleton instance. Initialized from DesktopDi.
         */
        @Volatile
        var instance: PartnerWindowManager? = null
            private set

        fun initialize(settingsStateManager: SettingsStateManager): PartnerWindowManager {
            return PartnerWindowManager(settingsStateManager).also {
                instance = it
            }
        }
    }
}
