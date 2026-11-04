package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement

/**
 * Runs [onSecondaryClick] on a right click (mouse or trackpad), the pointer equivalent of a
 * long press: a long press with a mouse is slow and easy to miss. The click is consumed in the
 * Initial pass, so the wrapped control's own click, touch-down selection and scrolling never
 * see it. Place it before (outside) the control's click modifiers.
 */
internal fun Modifier.onSecondaryClick(enabled: Boolean, onSecondaryClick: () -> Unit): Modifier =
    if (enabled) this then SecondaryClickElement(onSecondaryClick) else this

/** Whether the press that started the current gesture is a right click. */
internal fun AwaitPointerEventScope.isSecondaryPress(): Boolean = currentEvent.buttons.isSecondaryPressed

private data class SecondaryClickElement(val onSecondaryClick: () -> Unit) : ModifierNodeElement<SecondaryClickNode>() {
    override fun create() = SecondaryClickNode(onSecondaryClick)
    override fun update(node: SecondaryClickNode) {
        node.onSecondaryClick = onSecondaryClick
    }
}

private class SecondaryClickNode(var onSecondaryClick: () -> Unit) : DelegatingNode() {
    init {
        delegate(SuspendingPointerInputModifierNode {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (!isSecondaryPress()) return@awaitEachGesture
                down.consume()
                onSecondaryClick()
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        })
    }
}
