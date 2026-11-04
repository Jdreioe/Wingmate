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
 * Setup is shown only to people who have never finished it. Installs from before
 * [Settings.welcomeFlowCompleted] decided routing are recognised by their selected voice,
 * so an existing Communicator is never sent back through setup.
 */
internal fun needsWelcome(settings: Settings, hasSelectedVoice: Boolean): Boolean =
    !settings.welcomeFlowCompleted && !hasSelectedVoice
