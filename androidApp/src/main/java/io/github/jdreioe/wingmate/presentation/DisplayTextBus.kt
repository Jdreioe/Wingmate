package io.github.jdreioe.wingmate.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object DisplayTextBus {
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text

    fun set(value: String) {
        _text.value = value
    }
}
