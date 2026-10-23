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
        return buttonId.takeIf { remainingMillis(nowMillis, durationMillis) > 0 }
    }

    /** Time left from the latest activation, including a changed duration setting. */
    fun remainingMillis(nowMillis: Long, durationMillis: Long): Long =
        if (buttonId == null || durationMillis <= 0) 0
        else (durationMillis - (nowMillis - activatedAtMillis).coerceAtLeast(0)).coerceAtLeast(0)
}
