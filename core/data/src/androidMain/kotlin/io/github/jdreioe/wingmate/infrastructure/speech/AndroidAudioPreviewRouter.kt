package io.github.jdreioe.wingmate.infrastructure

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import io.github.jdreioe.wingmate.domain.AudioOutputCapabilities
import io.github.jdreioe.wingmate.domain.AudioOutputRoute
import io.github.jdreioe.wingmate.domain.AudioRouteDecision
import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.SpeechAudioRouter
import io.github.jdreioe.wingmate.domain.loggingClassName

/** Lightweight value representation of an Android output device for pure unit testing. */
data class AndroidAudioOutputDevice(
    val id: Int,
    val type: Int,
)

/** Thin platform seam over [AudioManager] so routing behavior can be unit-tested without a device. */
interface AndroidAudioBackend {
    val sdkInt: Int
    fun availableOutputDevices(): List<AndroidAudioOutputDevice>
    fun getMode(): Int
    fun setMode(mode: Int)
    fun isSpeakerphoneOn(): Boolean
    fun setSpeakerphoneOn(on: Boolean)
    fun setCommunicationDevice(deviceId: Int): Boolean
    fun clearCommunicationDevice()
    fun findPlatformDevice(deviceId: Int): AudioDeviceInfo? = null
}

private class PlatformAndroidAudioBackend(
    private val context: Context,
) : AndroidAudioBackend {
    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override val sdkInt: Int get() = Build.VERSION.SDK_INT

    override fun availableOutputDevices(): List<AndroidAudioOutputDevice> =
        runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .map { AndroidAudioOutputDevice(id = it.id, type = it.type) }
        }.getOrDefault(emptyList())

    override fun getMode(): Int = runCatching { audioManager.mode }.getOrDefault(AudioManager.MODE_NORMAL)

    override fun setMode(mode: Int) {
        audioManager.mode = mode
    }

    @Suppress("DEPRECATION")
    override fun isSpeakerphoneOn(): Boolean =
        runCatching { audioManager.isSpeakerphoneOn }.getOrDefault(false)

    @Suppress("DEPRECATION")
    override fun setSpeakerphoneOn(on: Boolean) {
        audioManager.isSpeakerphoneOn = on
    }

    override fun setCommunicationDevice(deviceId: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val device = audioManager.availableCommunicationDevices.firstOrNull { it.id == deviceId }
            ?: audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == deviceId }
            ?: return false
        return audioManager.setCommunicationDevice(device)
    }

    override fun clearCommunicationDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
    }

    override fun findPlatformDevice(deviceId: Int): AudioDeviceInfo? =
        runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == deviceId }
        }.getOrNull()
}

/**
 * Android [SpeechAudioRouter] backed by [AudioManager] and [AudioDeviceInfo] (#406).
 *
 * Connected headphones, USB headsets, hearing aids, and Bluetooth earpieces/headsets are treated
 * as private preview outputs (the phone's built-in handset receiver is excluded so unconnected
 * phones fall back cleanly to the speaker). Any temporary [AudioManager] mode or communication
 * device override is tracked and restored deterministically by [restoreRoute].
 */
class AndroidAudioPreviewRouter(
    private val backend: AndroidAudioBackend,
) : SpeechAudioRouter {
    constructor(context: Context) : this(PlatformAndroidAudioBackend(context))

    private val lock = Any()
    private var overrideActive = false
    private var savedMode: Int = AudioManager.MODE_NORMAL
    private var savedSpeakerphoneOn: Boolean = false

    override fun currentCapabilities(): AudioOutputCapabilities {
        val devices = runCatching { backend.availableOutputDevices() }.getOrDefault(emptyList())
        return AudioOutputCapabilities(
            hasHeadphonesOrEarpiece = devices.any { isConnectedPrivateOutput(it.type) },
            hasSpeaker = devices.any { isBuiltInSpeaker(it.type) },
        )
    }

    override fun prepareRoute(decision: AudioRouteDecision) {
        synchronized(lock) {
            when (decision.route) {
                AudioOutputRoute.SystemDefault -> {
                    if (overrideActive) restoreRouteLocked()
                }
                AudioOutputRoute.Speaker -> {
                    applySpeakerOverrideLocked()
                }
                AudioOutputRoute.HeadphonesOrEarpiece -> {
                    val privateDevice = selectPreferredPrivateDevice(
                        runCatching { backend.availableOutputDevices() }.getOrDefault(emptyList())
                    )
                    if (privateDevice != null && privateDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        applyScoPrivateOverrideLocked(privateDevice.id)
                    } else if (overrideActive) {
                        // Wired, USB, A2DP, BLE headset, and hearing-aid outputs use standard media routing.
                        restoreRouteLocked()
                    }
                }
            }
        }
    }

    override fun restoreRoute() {
        synchronized(lock) {
            if (!overrideActive) return
            restoreRouteLocked()
        }
    }

    fun hasActiveOverride(): Boolean = synchronized(lock) { overrideActive }

    /**
     * Whether [decision] requires [AudioAttributes.USAGE_VOICE_COMMUNICATION] so Android honors
     * the active communication-device / speakerphone override on both TTS and MediaPlayer.
     */
    fun usesCommunicationStream(decision: AudioRouteDecision): Boolean = when (decision.route) {
        AudioOutputRoute.Speaker -> true
        AudioOutputRoute.HeadphonesOrEarpiece -> {
            val devices = runCatching { backend.availableOutputDevices() }.getOrDefault(emptyList())
            selectPreferredPrivateDevice(devices)?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        AudioOutputRoute.SystemDefault -> false
    }

    fun audioAttributesUsageFor(decision: AudioRouteDecision): Int =
        if (usesCommunicationStream(decision)) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION
        } else {
            AudioAttributes.USAGE_MEDIA
        }

    fun audioAttributesFor(decision: AudioRouteDecision): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(audioAttributesUsageFor(decision))
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    fun preferredOutputDeviceFor(decision: AudioRouteDecision): AudioDeviceInfo? {
        if (!decision.requiresRouteOverride) return null
        val devices = runCatching { backend.availableOutputDevices() }.getOrDefault(emptyList())
        val target = when (decision.route) {
            AudioOutputRoute.Speaker -> selectBuiltInSpeaker(devices)
            AudioOutputRoute.HeadphonesOrEarpiece -> selectPreferredPrivateDevice(devices)
            AudioOutputRoute.SystemDefault -> null
        } ?: return null
        return backend.findPlatformDevice(target.id)
    }

    fun volumeFor(decision: AudioRouteDecision): Float =
        if (decision.duckAudio) PREVIEW_DUCKED_VOLUME else 1.0f

    private fun captureBaselineIfNeededLocked() {
        if (overrideActive) return
        savedMode = backend.getMode()
        savedSpeakerphoneOn = backend.isSpeakerphoneOn()
        overrideActive = true
    }

    private fun applySpeakerOverrideLocked() {
        val devices = runCatching { backend.availableOutputDevices() }.getOrDefault(emptyList())
        val speaker = selectBuiltInSpeaker(devices)
        captureBaselineIfNeededLocked()
        runCatching {
            backend.setMode(AudioManager.MODE_IN_COMMUNICATION)
            if (backend.sdkInt >= Build.VERSION_CODES.S) {
                val routed = speaker != null && backend.setCommunicationDevice(speaker.id)
                if (!routed) {
                    backend.setSpeakerphoneOn(true)
                }
            } else {
                backend.setSpeakerphoneOn(true)
            }
        }.onFailure { error ->
            OperationalLogger.warn("speech.audio_route", "speaker_override_failed", exceptionClass = error.loggingClassName())
        }
    }

    private fun applyScoPrivateOverrideLocked(deviceId: Int) {
        captureBaselineIfNeededLocked()
        runCatching {
            backend.setMode(AudioManager.MODE_IN_COMMUNICATION)
            if (backend.sdkInt >= Build.VERSION_CODES.S) {
                backend.setCommunicationDevice(deviceId)
            } else {
                backend.setSpeakerphoneOn(false)
            }
        }.onFailure { error ->
            OperationalLogger.warn("speech.audio_route", "private_override_failed", exceptionClass = error.loggingClassName())
        }
    }

    private fun restoreRouteLocked() {
        overrideActive = false
        runCatching {
            if (backend.sdkInt >= Build.VERSION_CODES.S) {
                backend.clearCommunicationDevice()
            } else {
                backend.setSpeakerphoneOn(savedSpeakerphoneOn)
            }
            backend.setMode(savedMode)
        }.onFailure { error ->
            OperationalLogger.warn("speech.audio_route", "restore_failed", exceptionClass = error.loggingClassName())
        }
    }

    companion object {
        const val PREVIEW_DUCKED_VOLUME: Float = 0.55f

        @SuppressLint("InlinedApi")
        internal fun isConnectedPrivateOutput(type: Int): Boolean = when (type) {
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            -> true
            else -> false
        }

        @SuppressLint("InlinedApi")
        internal fun isBuiltInSpeaker(type: Int): Boolean = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
            -> true
            else -> false
        }

        @SuppressLint("InlinedApi")
        internal fun selectBuiltInSpeaker(devices: List<AndroidAudioOutputDevice>): AndroidAudioOutputDevice? =
            devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE }

        @SuppressLint("InlinedApi")
        internal fun selectPreferredPrivateDevice(devices: List<AndroidAudioOutputDevice>): AndroidAudioOutputDevice? {
            val priority = listOf(
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_HEARING_AID,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            )
            for (deviceType in priority) {
                devices.firstOrNull { it.type == deviceType }?.let { return it }
            }
            return null
        }
    }
}
