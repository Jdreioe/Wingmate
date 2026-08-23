package io.github.jdreioe.wingmate.application

/**
 * Per-target activation debounce (issue #118).
 *
 * Accidental rapid repeated activations can append or speak the same target multiple
 * times. This guard records the last time each target was activated and rejects
 * repeated activations of the *same* target inside the configured window, while
 * leaving different targets fully responsive.
 *
 * A [debounceMillis] of zero (or less) disables the guard entirely and keeps no state.
 * Timestamps are supplied by the caller so tests can drive deterministic timing.
 */
class SelectionDebouncer {
    private val lastActivationAtMillis = HashMap<String, Long>()

    /**
     * Returns `true` when an activation of [targetId] at [nowMillis] should proceed.
     *
     * Repeated hits on the same target inside [debounceMillis] return `false` and do
     * not refresh the recorded timestamp, so the window is measured from the first
     * accepted activation. Different targets always remain responsive.
     */
    fun tryActivate(targetId: String, nowMillis: Long, debounceMillis: Long): Boolean {
        if (debounceMillis <= 0) {
            lastActivationAtMillis.clear()
            return true
        }
        val last = lastActivationAtMillis[targetId]
        if (last != null && nowMillis - last < debounceMillis) {
            return false
        }
        lastActivationAtMillis[targetId] = nowMillis
        return true
    }
}
