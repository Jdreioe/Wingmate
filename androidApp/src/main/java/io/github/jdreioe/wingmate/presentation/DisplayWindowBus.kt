package io.github.jdreioe.wingmate.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object DisplayWindowBus {
    private val _show = MutableStateFlow(false)
    val show: StateFlow<Boolean> = _show

    fun open() { _show.value = true }
    fun close() { _show.value = false }
}
