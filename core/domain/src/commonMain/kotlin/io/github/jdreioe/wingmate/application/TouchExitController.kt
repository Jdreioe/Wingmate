package io.github.jdreioe.wingmate.application

/**
 * Snapshot of an in-progress Touch Exit (slide-to-correct) gesture (#405).
 *
 * [activeTargetId] is the Button currently under the Communicator's finger while
 * sliding, or `null` when the finger rests over empty space, a keyguard gap, or
 * outside the grid, or when no gesture is active.
 */
data class TouchExitState(
    val isTracking: Boolean = false,
    val activeTargetId: String? = null,
)

sealed interface TouchExitEffect {
    /** Finger moved onto a new Button; callers trigger auditory fishing if enabled. */
    data class Preview(val targetId: String) : TouchExitEffect

    /** Finger lifted while resting on [targetId]; callers activate this Button. */
    data class Activate(val targetId: String) : TouchExitEffect

    /** Gesture ended outside any Button or was cancelled by the system. */
    data object Cancelled : TouchExitEffect
}

/**
 * Shared state machine for Touch Exit (slide-to-correct) selection.
 *
 * Native clients perform hit-testing against their rendered grid and forward
 * pointer lifecycle events (`begin`, `move`, `release`, `cancel`) here. Staying
 * on the same Button does not re-emit [TouchExitEffect.Preview], and lifting
 * outside a Button or cancelling clears highlight without activating anything.
 */
class TouchExitController {
    private var tracking = false
    private var currentTargetId: String? = null

    val state: TouchExitState
        get() = TouchExitState(isTracking = tracking, activeTargetId = currentTargetId)

    /** Starts a Touch Exit gesture at [targetId], or `null` if pressed on empty space. */
    fun begin(targetId: String?): TouchExitEffect? {
        tracking = true
        currentTargetId = targetId
        return targetId?.let(TouchExitEffect::Preview)
    }

    /** Updates the hovered Button as the finger slides across the grid. */
    fun move(targetId: String?): TouchExitEffect? {
        if (!tracking) return null
        if (targetId == currentTargetId) return null
        currentTargetId = targetId
        return targetId?.let(TouchExitEffect::Preview)
    }

    /** Commits [targetId] on finger lift, or cancels safely when [targetId] is `null`. */
    fun release(targetId: String?): TouchExitEffect {
        if (!tracking) return TouchExitEffect.Cancelled
        tracking = false
        currentTargetId = null
        return targetId?.let(TouchExitEffect::Activate) ?: TouchExitEffect.Cancelled
    }

    /** Cancels an active gesture (e.g. system interruption or view dismissal) without activating. */
    fun cancel(): TouchExitEffect? {
        if (!tracking) return null
        tracking = false
        currentTargetId = null
        return TouchExitEffect.Cancelled
    }
}

/**
 * Shared precedence rule for Touch Exit: Edit mode, hold-to-select, and
 * select-on-touch-down all take priority over Touch Exit.
 */
fun touchExitIsActive(
    isEditMode: Boolean,
    holdToSelectMillis: Long,
    selectOnTouchDown: Boolean,
    selectOnTouchExit: Boolean,
): Boolean = !isEditMode && holdToSelectMillis <= 0L && !selectOnTouchDown && selectOnTouchExit

/**
 * Shared rule for horizontal Page swipe: disabled while editing, under select-on-touch-down,
 * when Touch Exit actively owns touch drags on the grid, and (on platforms where hold owns
 * drag gestures) under hold-to-select.
 */
fun pageSwipeIsActive(
    isEditMode: Boolean,
    swipeBetweenPages: Boolean,
    holdToSelectMillis: Long,
    selectOnTouchDown: Boolean,
    selectOnTouchExit: Boolean,
    disableOnHoldToSelect: Boolean = true,
): Boolean {
    if (isEditMode || !swipeBetweenPages || selectOnTouchDown) return false
    if (disableOnHoldToSelect && holdToSelectMillis > 0L) return false
    return !touchExitIsActive(
        isEditMode = isEditMode,
        holdToSelectMillis = holdToSelectMillis,
        selectOnTouchDown = selectOnTouchDown,
        selectOnTouchExit = selectOnTouchExit,
    )
}
