package io.github.jdreioe.wingmate

import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.Voice
import kotlinx.coroutines.CancellationException

internal sealed interface StartupLoadState {
    data object Loading : StartupLoadState
    data class Ready(val settings: Settings, val selectedVoice: Voice?) : StartupLoadState
    data object Failed : StartupLoadState
}

internal suspend fun loadStartupState(
    loadSettings: suspend () -> Settings,
    loadSelectedVoice: suspend () -> Voice?,
): StartupLoadState = try {
    StartupLoadState.Ready(
        settings = loadSettings(),
        selectedVoice = loadSelectedVoice(),
    )
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    StartupLoadState.Failed
}

/**
 * Setup is shown until it is finished. Voice pages save the voice immediately, so a
 * selected voice alone only marks an install from before setup was tracked
 * ([Settings.welcomeFlowStarted] unset); those Communicators are never sent back through setup.
 */
internal fun needsWelcome(settings: Settings, hasSelectedVoice: Boolean): Boolean =
    !settings.welcomeFlowCompleted && (settings.welcomeFlowStarted || !hasSelectedVoice)
