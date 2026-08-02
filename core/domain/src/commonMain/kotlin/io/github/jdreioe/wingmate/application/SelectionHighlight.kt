package io.github.jdreioe.wingmate.application

/**
 * Time-bounded visual selection highlight (issue #120).
 *
 * A selected communication target receives immediate visible feedback for a
 * configured duration. The highlight records the target and the moment it was
 * activated; rapid re-activation simply moves the window forward from the newest
 * activation, so overlays never go stale early or outlive their target.
 */
class SelectionHighlight {
    private var buttonId: String? = null
    private var activatedAtMillis: Long = 0L
    var generation: Long = 0L
        private set

    /** Record a new selection of [targetId] at [nowMillis]. */
    fun activate(targetId: String, nowMillis: Long) {
        buttonId = targetId
        activatedAtMillis = nowMillis
        generation += 1
    }

    /** Immediately end the highlight. */
    fun clear() {
        buttonId = null
        generation += 1
    }

    /**
     * The currently highlighted target id, or `null` when the highlight has expired,
     * been cleared, or is disabled by a non-positive [durationMillis].
     */
    fun highlightedTarget(nowMillis: Long, durationMillis: Long): String? {
        if (durationMillis <= 0) return null
        val id = buttonId ?: return null
        return if (nowMillis - activatedAtMillis < durationMillis) id else null
    }
}
