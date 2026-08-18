package io.github.jdreioe.flow

/**
 * Popup lifecycle for Flow.
 *
 * Transitions: HIDDEN → PREPARING → PLAYING ⇄ PAUSED → HIDDEN.
 * There is deliberately no READY state.
 */
sealed interface FlowState {
    data object Hidden : FlowState
    data object Preparing : FlowState
    data object Playing : FlowState
    data object Paused : FlowState
}