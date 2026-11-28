package io.github.jdreioe.wingmate.domain

/** Classification of a speech request for audio-output routing (#406). */
enum class SpeechOutputRole {
    /** Spoken Messages, single-Button vocalizations, recordings, and all non-preview speech. */
    PrimaryMessage,

    /** Auditory fishing preview cues (hover / Touch Exit explore). Never recorded in History. */
    AuditoryPreview,
}

/** Snapshot of currently available audio outputs on the device. */
data class AudioOutputCapabilities(
    val hasHeadphonesOrEarpiece: Boolean,
    val hasSpeaker: Boolean,
)

/** Resolved output target for a speech request. */
enum class AudioOutputRoute {
    /** Leave system/default audio routing untouched. */
    SystemDefault,

    /** Explicitly route primary Messages to the built-in speaker while headphones/earpiece are connected. */
    Speaker,

    /** Route auditory fishing previews privately to connected headphones or an earpiece. */
    HeadphonesOrEarpiece,
}

/** Decision returned by [AudioPreviewRoutingPolicy.resolve]. */
data class AudioRouteDecision(
    val route: AudioOutputRoute,
    /**
     * True on single-output fallback when private headphone previews were requested but no
     * secondary output is connected, so the platform can gently duck/soften preview playback
     * without failing or dropping speech.
     */
    val duckAudio: Boolean = false,
    /**
     * True when the platform must actively override routing for this utterance and restore it
     * once the utterance finishes, cancels, or fails.
     */
    val requiresRouteOverride: Boolean = route != AudioOutputRoute.SystemDefault,
) {
    companion object {
        val SystemDefault: AudioRouteDecision = AudioRouteDecision(
            route = AudioOutputRoute.SystemDefault,
            duckAudio = false,
            requiresRouteOverride = false,
        )
    }
}

/**
 * Pure policy for routing auditory fishing preview prompts versus primary spoken Messages (#406).
 *
 * - When [routePreviewToHeadphones] is false, all speech uses [AudioOutputRoute.SystemDefault]
 *   with no route overrides and no ducking.
 * - When [routePreviewToHeadphones] is true and both headphones/earpiece and speaker are available:
 *   - [SpeechOutputRole.AuditoryPreview] routes privately to [AudioOutputRoute.HeadphonesOrEarpiece].
 *   - [SpeechOutputRole.PrimaryMessage] routes to [AudioOutputRoute.Speaker].
 * - When [routePreviewToHeadphones] is true and only one (or neither) output is available:
 *   - Both roles fall back cleanly to [AudioOutputRoute.SystemDefault] so speech never fails or drops.
 *   - [SpeechOutputRole.AuditoryPreview] requests [AudioRouteDecision.duckAudio] = true for clean
 *     single-output ducking.
 */
object AudioPreviewRoutingPolicy {
    fun resolve(
        role: SpeechOutputRole,
        routePreviewToHeadphones: Boolean,
        capabilities: AudioOutputCapabilities,
    ): AudioRouteDecision {
        if (!routePreviewToHeadphones) {
            return AudioRouteDecision.SystemDefault
        }

        val hasDualOutputs = capabilities.hasHeadphonesOrEarpiece && capabilities.hasSpeaker
        if (!hasDualOutputs) {
            return AudioRouteDecision(
                route = AudioOutputRoute.SystemDefault,
                duckAudio = role == SpeechOutputRole.AuditoryPreview,
                requiresRouteOverride = false,
            )
        }

        return when (role) {
            SpeechOutputRole.AuditoryPreview -> AudioRouteDecision(
                route = AudioOutputRoute.HeadphonesOrEarpiece,
                duckAudio = false,
                requiresRouteOverride = true,
            )
            SpeechOutputRole.PrimaryMessage -> AudioRouteDecision(
                route = AudioOutputRoute.Speaker,
                duckAudio = false,
                requiresRouteOverride = true,
            )
        }
    }
}

/**
 * Platform seam for querying audio output capabilities and applying/restoring per-utterance
 * audio routing overrides.
 */
interface SpeechAudioRouter {
    fun currentCapabilities(): AudioOutputCapabilities
    fun prepareRoute(decision: AudioRouteDecision)
    fun restoreRoute()
}

object NoopSpeechAudioRouter : SpeechAudioRouter {
    override fun currentCapabilities(): AudioOutputCapabilities =
        AudioOutputCapabilities(hasHeadphonesOrEarpiece = false, hasSpeaker = true)

    override fun prepareRoute(decision: AudioRouteDecision) = Unit
    override fun restoreRoute() = Unit
}
