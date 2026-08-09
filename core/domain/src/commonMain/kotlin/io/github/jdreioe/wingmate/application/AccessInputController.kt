package io.github.jdreioe.wingmate.application

/** A native communication target identified without moving its action into shared UI code. */
data class AccessInputState(
    val isPaused: Boolean = false,
    val currentTargetId: String? = null,
    val dwellProgress: Float = 0f,
)

sealed interface AccessInputEffect {
    data class Activate(val targetId: String) : AccessInputEffect
    data class PauseChanged(val isPaused: Boolean) : AccessInputEffect
}

/**
 * Shared policy for pointer/focus selection, dwell timing, and rest mode.
 *
 * Native clients only translate platform events and execute [AccessInputEffect.Activate].
 * Timestamps are supplied by callers so the policy is deterministic and testable.
 */
class AccessInputController {
    private var hoveredTargetId: String? = null
    private var focusedTargetId: String? = null
    private val pressedKeys = mutableSetOf<String>()
    private var paused = false
    private var dwellTargetId: String? = null
    private var dwellStartedAtMillis: Long? = null
    private var dwellConsumedTargetId: String? = null
    private var progress = 0f

    val state: AccessInputState
        get() = AccessInputState(paused, currentTarget(), progress)

    fun targetEntered(targetId: String, nowMillis: Long) {
        val previous = currentTarget()
        hoveredTargetId = targetId
        if (currentTarget() != previous) restartDwell(nowMillis)
    }

    fun targetExited(targetId: String, nowMillis: Long) {
        if (hoveredTargetId == targetId) {
            val previous = currentTarget()
            hoveredTargetId = null
            if (currentTarget() != previous) restartDwell(nowMillis)
        }
    }

    fun targetFocused(targetId: String, nowMillis: Long) {
        val previous = currentTarget()
        focusedTargetId = targetId
        if (currentTarget() != previous) restartDwell(nowMillis)
    }

    fun targetBlurred(targetId: String, nowMillis: Long) {
        if (focusedTargetId == targetId) {
            val previous = currentTarget()
            focusedTargetId = null
            if (currentTarget() != previous) restartDwell(nowMillis)
        }
    }

    fun keyDown(
        key: String,
        selectBinding: String,
        restModeBinding: String,
        nowMillis: Long,
    ): AccessInputEffect? {
        val normalized = normalizeKeyBinding(key)
        if (normalized.isEmpty() || !pressedKeys.add(normalized)) return null
        if (normalized == normalizeKeyBinding(restModeBinding) && restModeBinding.isNotBlank()) {
            return setPaused(!paused, nowMillis)
        }
        if (!paused && normalized == normalizeKeyBinding(selectBinding) && selectBinding.isNotBlank()) {
            return currentTarget()?.let { target ->
                dwellConsumedTargetId = target
                progress = 0f
                AccessInputEffect.Activate(target)
            }
        }
        return null
    }

    fun keyUp(key: String) {
        pressedKeys.remove(normalizeKeyBinding(key))
    }

    fun togglePaused(nowMillis: Long): AccessInputEffect.PauseChanged = setPaused(!paused, nowMillis)

    fun setPaused(value: Boolean, nowMillis: Long): AccessInputEffect.PauseChanged {
        paused = value
        restartDwell(nowMillis)
        return AccessInputEffect.PauseChanged(value)
    }

    fun tick(nowMillis: Long, dwellMillis: Long): AccessInputEffect.Activate? {
        val target = currentTarget()
        if (paused || dwellMillis <= 0 || target == null || dwellConsumedTargetId == target) {
            progress = 0f
            return null
        }
        if (dwellTargetId != target || dwellStartedAtMillis == null) restartDwell(nowMillis)
        val elapsed = nowMillis - (dwellStartedAtMillis ?: nowMillis)
        progress = (elapsed.toFloat() / dwellMillis).coerceIn(0f, 1f)
        if (elapsed < dwellMillis) return null
        dwellConsumedTargetId = target
        progress = 0f
        return AccessInputEffect.Activate(target)
    }

    fun clearTransientInput(nowMillis: Long) {
        hoveredTargetId = null
        focusedTargetId = null
        pressedKeys.clear()
        restartDwell(nowMillis)
    }

    private fun currentTarget(): String? = hoveredTargetId ?: focusedTargetId

    private fun restartDwell(nowMillis: Long) {
        val target = currentTarget()
        dwellTargetId = target
        dwellStartedAtMillis = if (!paused && target != null) nowMillis else null
        dwellConsumedTargetId = null
        progress = 0f
    }
}

fun normalizeKeyBinding(value: String): String = when (if (value == " ") "space" else value.trim().lowercase()) {
    "space", "spacebar" -> "Space"
    "enter", "return" -> "Enter"
    else -> value.trim().uppercase()
}
