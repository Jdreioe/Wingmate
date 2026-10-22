package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement

/**
 * Selects on first touch instead of on release (#110), once per gesture whatever the
 * fingers do next. The rest of the gesture is consumed, so a wrapped control's own
 * click never fires a second time and nothing underneath scrolls. Screen readers keep
 * using the control's click action.
 */
internal fun Modifier.selectOnTouchDown(enabled: Boolean, onSelect: () -> Unit): Modifier =
    if (enabled) this then TouchDownSelectionElement(onSelect) else this

private data class TouchDownSelectionElement(val onSelect: () -> Unit) : ModifierNodeElement<TouchDownSelectionNode>() {
    override fun create() = TouchDownSelectionNode(onSelect)
    override fun update(node: TouchDownSelectionNode) {
        node.onSelect = onSelect
    }
}

private class TouchDownSelectionNode(var onSelect: () -> Unit) : DelegatingNode() {
    init {
        delegate(SuspendingPointerInputModifierNode {
            awaitEachGesture {
                // Initial pass: claim the touch before the wrapped control or a scroll container sees it.
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).consume()
                onSelect()
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        })
    }
}
