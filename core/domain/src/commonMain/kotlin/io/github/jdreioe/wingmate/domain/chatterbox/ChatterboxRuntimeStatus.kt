package io.github.jdreioe.wingmate.domain.chatterbox

import kotlinx.coroutines.flow.StateFlow

sealed interface ChatterboxRuntimeStatus {
    data object NotInstalled : ChatterboxRuntimeStatus
    data class Downloading(val progress: Float) : ChatterboxRuntimeStatus
    data object Verifying : ChatterboxRuntimeStatus
    data object Loading : ChatterboxRuntimeStatus
    data class Ready(val modelId: String, val voiceProfileId: String?) : ChatterboxRuntimeStatus
    data object Speaking : ChatterboxRuntimeStatus
    data class Error(val message: String, val fallbackUsed: Boolean) : ChatterboxRuntimeStatus
}

interface ChatterboxStatusProvider {
    val status: StateFlow<ChatterboxRuntimeStatus>
    fun release() = Unit
}
