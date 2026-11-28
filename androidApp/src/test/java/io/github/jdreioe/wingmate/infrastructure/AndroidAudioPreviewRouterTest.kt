package io.github.jdreioe.wingmate.infrastructure

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import io.github.jdreioe.wingmate.domain.AudioOutputRoute
import io.github.jdreioe.wingmate.domain.AudioPreviewRoutingPolicy
import io.github.jdreioe.wingmate.domain.AudioRouteDecision
import io.github.jdreioe.wingmate.domain.SpeechOutputRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAudioPreviewRouterTest {
    @Test
    fun `wired headphones and built-in speaker expose dual outputs and route speaker messages cleanly on API 31+`() {
        val backend = FakeAndroidAudioBackend(
            sdkInt = Build.VERSION_CODES.S,
            devices = listOf(
                AndroidAudioOutputDevice(id = 1, type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
                AndroidAudioOutputDevice(id = 2, type = AudioDeviceInfo.TYPE_WIRED_HEADPHONES),
            ),
        )
        val router = AndroidAudioPreviewRouter(backend)

        val capabilities = router.currentCapabilities()
        assertTrue(capabilities.hasHeadphonesOrEarpiece)
        assertTrue(capabilities.hasSpeaker)

        val previewDecision = AudioPreviewRoutingPolicy.resolve(
            role = SpeechOutputRole.AuditoryPreview,
            routePreviewToHeadphones = true,
            capabilities = capabilities,
        )
        assertEquals(AudioOutputRoute.HeadphonesOrEarpiece, previewDecision.route)
        router.prepareRoute(previewDecision)
        assertFalse(router.hasActiveOverride())
        assertEquals(AudioAttributes.USAGE_MEDIA, router.audioAttributesUsageFor(previewDecision))
        assertEquals(1.0f, router.volumeFor(previewDecision), 0.001f)

        val speakerDecision = AudioPreviewRoutingPolicy.resolve(
            role = SpeechOutputRole.PrimaryMessage,
            routePreviewToHeadphones = true,
            capabilities = capabilities,
        )
        assertEquals(AudioOutputRoute.Speaker, speakerDecision.route)
        router.prepareRoute(speakerDecision)
        assertTrue(router.hasActiveOverride())
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, backend.currentMode)
        assertEquals(1, backend.communicationDeviceId)
        assertEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION, router.audioAttributesUsageFor(speakerDecision))

        router.restoreRoute()
        assertFalse(router.hasActiveOverride())
        assertEquals(AudioManager.MODE_NORMAL, backend.currentMode)
        assertEquals(null, backend.communicationDeviceId)
        assertEquals(1, backend.clearCommunicationDeviceCalls)

        // Repeated restore is a safe no-op.
        router.restoreRoute()
        assertEquals(1, backend.clearCommunicationDeviceCalls)
    }

    @Test
    fun `legacy Android API uses speakerphone toggle and restores original mode`() {
        val backend = FakeAndroidAudioBackend(
            sdkInt = Build.VERSION_CODES.R,
            devices = listOf(
                AndroidAudioOutputDevice(id = 10, type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
                AndroidAudioOutputDevice(id = 20, type = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
            ),
        )
        val router = AndroidAudioPreviewRouter(backend)

        router.prepareRoute(AudioRouteDecision(AudioOutputRoute.Speaker))
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, backend.currentMode)
        assertTrue(backend.currentSpeakerphoneOn)

        router.restoreRoute()
        assertEquals(AudioManager.MODE_NORMAL, backend.currentMode)
        assertFalse(backend.currentSpeakerphoneOn)
    }

    @Test
    fun `bluetooth sco earpiece activates communication device for preview and restores cleanly`() {
        val backend = FakeAndroidAudioBackend(
            sdkInt = Build.VERSION_CODES.S,
            devices = listOf(
                AndroidAudioOutputDevice(id = 1, type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
                AndroidAudioOutputDevice(id = 7, type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
            ),
        )
        val router = AndroidAudioPreviewRouter(backend)

        val previewDecision = AudioRouteDecision(AudioOutputRoute.HeadphonesOrEarpiece)
        router.prepareRoute(previewDecision)

        assertTrue(router.hasActiveOverride())
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, backend.currentMode)
        assertEquals(7, backend.communicationDeviceId)
        assertEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION, router.audioAttributesUsageFor(previewDecision))

        router.restoreRoute()
        assertFalse(router.hasActiveOverride())
        assertEquals(AudioManager.MODE_NORMAL, backend.currentMode)
        assertEquals(null, backend.communicationDeviceId)
    }

    @Test
    fun `phone without connected headphones ignores built-in handset receiver and ducks preview cleanly`() {
        val backend = FakeAndroidAudioBackend(
            sdkInt = Build.VERSION_CODES.S,
            devices = listOf(
                AndroidAudioOutputDevice(id = 1, type = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE),
                AndroidAudioOutputDevice(id = 2, type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
            ),
        )
        val router = AndroidAudioPreviewRouter(backend)

        val capabilities = router.currentCapabilities()
        assertFalse(capabilities.hasHeadphonesOrEarpiece)
        assertTrue(capabilities.hasSpeaker)

        val previewDecision = AudioPreviewRoutingPolicy.resolve(
            role = SpeechOutputRole.AuditoryPreview,
            routePreviewToHeadphones = true,
            capabilities = capabilities,
        )
        assertEquals(AudioOutputRoute.SystemDefault, previewDecision.route)
        assertTrue(previewDecision.duckAudio)

        router.prepareRoute(previewDecision)
        assertFalse(router.hasActiveOverride())
        assertEquals(0, backend.modeChanges)
        assertEquals(AndroidAudioPreviewRouter.PREVIEW_DUCKED_VOLUME, router.volumeFor(previewDecision), 0.001f)
    }
}

private class FakeAndroidAudioBackend(
    override val sdkInt: Int,
    private val devices: List<AndroidAudioOutputDevice>,
) : AndroidAudioBackend {
    var currentMode: Int = AudioManager.MODE_NORMAL
    var currentSpeakerphoneOn: Boolean = false
    var communicationDeviceId: Int? = null
    var clearCommunicationDeviceCalls: Int = 0
    var modeChanges: Int = 0

    override fun availableOutputDevices(): List<AndroidAudioOutputDevice> = devices

    override fun getMode(): Int = currentMode

    override fun setMode(mode: Int) {
        currentMode = mode
        modeChanges++
    }

    override fun isSpeakerphoneOn(): Boolean = currentSpeakerphoneOn

    override fun setSpeakerphoneOn(on: Boolean) {
        currentSpeakerphoneOn = on
    }

    override fun setCommunicationDevice(deviceId: Int): Boolean {
        communicationDeviceId = deviceId
        return true
    }

    override fun clearCommunicationDevice() {
        communicationDeviceId = null
        clearCommunicationDeviceCalls++
    }
}
