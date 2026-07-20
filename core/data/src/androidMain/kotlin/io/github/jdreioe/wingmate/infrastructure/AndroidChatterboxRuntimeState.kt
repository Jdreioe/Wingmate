package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.chatterbox.ChatterboxRuntimeStatus
import io.github.jdreioe.wingmate.domain.chatterbox.ChatterboxStatusProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidChatterboxRuntimeState : ChatterboxStatusProvider {
    private val mutableStatus = MutableStateFlow<ChatterboxRuntimeStatus>(ChatterboxRuntimeStatus.NotInstalled)
    override val status: StateFlow<ChatterboxRuntimeStatus> = mutableStatus.asStateFlow()
    private var releaseAction: (() -> Unit)? = null

    fun update(value: ChatterboxRuntimeStatus) {
        mutableStatus.value = value
    }

    fun bindRelease(action: () -> Unit) {
        releaseAction = action
    }

    override fun release() {
        releaseAction?.invoke()
    }
}
