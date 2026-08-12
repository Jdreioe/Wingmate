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
