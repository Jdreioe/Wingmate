package io.github.jdreioe.wingmate.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioPreviewRoutingPolicyTest {
    private val dualOutputs = AudioOutputCapabilities(hasHeadphonesOrEarpiece = true, hasSpeaker = true)
    private val speakerOnly = AudioOutputCapabilities(hasHeadphonesOrEarpiece = false, hasSpeaker = true)
    private val headphonesOnly = AudioOutputCapabilities(hasHeadphonesOrEarpiece = true, hasSpeaker = false)
    private val noOutputs = AudioOutputCapabilities(hasHeadphonesOrEarpiece = false, hasSpeaker = false)

    @Test
    fun disabledSettingLeavesRoutingUntouchedForBothRoles() {
        for (capabilities in listOf(dualOutputs, speakerOnly, headphonesOnly, noOutputs)) {
            val preview = AudioPreviewRoutingPolicy.resolve(
                role = SpeechOutputRole.AuditoryPreview,
                routePreviewToHeadphones = false,
                capabilities = capabilities,
            )
            val primary = AudioPreviewRoutingPolicy.resolve(
                role = SpeechOutputRole.PrimaryMessage,
                routePreviewToHeadphones = false,
                capabilities = capabilities,
            )

            assertEquals(AudioOutputRoute.SystemDefault, preview.route)
            assertFalse(preview.duckAudio)
            assertFalse(preview.requiresRouteOverride)
            assertEquals(AudioOutputRoute.SystemDefault, primary.route)
            assertFalse(primary.duckAudio)
            assertFalse(primary.requiresRouteOverride)
        }
    }

    @Test
    fun enabledSettingWithDualOutputsRoutesPreviewsPrivatelyAndMessagesToSpeaker() {
        val preview = AudioPreviewRoutingPolicy.resolve(
            role = SpeechOutputRole.AuditoryPreview,
            routePreviewToHeadphones = true,
            capabilities = dualOutputs,
        )
        val primary = AudioPreviewRoutingPolicy.resolve(
            role = SpeechOutputRole.PrimaryMessage,
            routePreviewToHeadphones = true,
            capabilities = dualOutputs,
        )

        assertEquals(AudioOutputRoute.HeadphonesOrEarpiece, preview.route)
        assertFalse(preview.duckAudio)
        assertTrue(preview.requiresRouteOverride)

        assertEquals(AudioOutputRoute.Speaker, primary.route)
        assertFalse(primary.duckAudio)
        assertTrue(primary.requiresRouteOverride)
    }

    @Test
    fun enabledSettingWithSpeakerOnlyFallsBackCleanlyAndDucksPreviewOnly() {
        val preview = AudioPreviewRoutingPolicy.resolve(
            role = SpeechOutputRole.AuditoryPreview,
            routePreviewToHeadphones = true,
            capabilities = speakerOnly,
        )
        val primary = AudioPreviewRoutingPolicy.resolve(
            role = SpeechOutputRole.PrimaryMessage,
            routePreviewToHeadphones = true,
            capabilities = speakerOnly,
        )

        assertEquals(AudioOutputRoute.SystemDefault, preview.route)
        assertTrue(preview.duckAudio)
        assertFalse(preview.requiresRouteOverride)

        assertEquals(AudioOutputRoute.SystemDefault, primary.route)
        assertFalse(primary.duckAudio)
        assertFalse(primary.requiresRouteOverride)
    }

    @Test
    fun enabledSettingWithHeadphonesOnlyOrNoOutputsFallsBackCleanlyWithoutRouteOverride() {
        for (capabilities in listOf(headphonesOnly, noOutputs)) {
            val preview = AudioPreviewRoutingPolicy.resolve(
                role = SpeechOutputRole.AuditoryPreview,
                routePreviewToHeadphones = true,
                capabilities = capabilities,
            )
            val primary = AudioPreviewRoutingPolicy.resolve(
                role = SpeechOutputRole.PrimaryMessage,
                routePreviewToHeadphones = true,
                capabilities = capabilities,
            )

            assertEquals(AudioOutputRoute.SystemDefault, preview.route)
            assertTrue(preview.duckAudio)
            assertFalse(preview.requiresRouteOverride)

            assertEquals(AudioOutputRoute.SystemDefault, primary.route)
            assertFalse(primary.duckAudio)
            assertFalse(primary.requiresRouteOverride)
        }
    }
}
