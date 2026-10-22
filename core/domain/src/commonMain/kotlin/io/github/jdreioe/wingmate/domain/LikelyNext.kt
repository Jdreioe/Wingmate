package io.github.jdreioe.wingmate.domain

/** Messages spoken further apart than this are not treated as one conversation. */
const val LIKELY_NEXT_MAX_GAP_MILLIS = 10 * 60 * 1000L

/**
 * Ranks the Messages most likely to be spoken after the last one in [history]
 * (experimental Likely next Page, #213).
 *
 * Messages that followed the last spoken Message within [LIKELY_NEXT_MAX_GAP_MILLIS]
 * rank first by how often they did; the remaining slots go to the most frequent
 * Messages overall. Ties go to the more recently spoken Message. Messages match
 * by trimmed, case-folded text, and each result is that Message's latest History
 * entry. The last spoken Message itself and entries hidden from History are
 * never suggested.
 */
fun likelyNextMessages(history: List<SaidText>, limit: Int): List<SaidText> {
    val spoken = history
        .filter { it.visibleInHistory && !it.saidText.isNullOrBlank() }
        .sortedBy { it.spokenAt }
    val last = spoken.lastOrNull() ?: return emptyList()
    val lastKey = last.messageKey

    val latest = spoken.associateBy { it.messageKey }
    val frequency = spoken.groupingBy { it.messageKey }.eachCount()
    val transitions = spoken.zipWithNext()
        .filter { (before, after) -> before.messageKey == lastKey && after.spokenAt - before.spokenAt <= LIKELY_NEXT_MAX_GAP_MILLIS }
        .groupingBy { (_, after) -> after.messageKey }
        .eachCount()

    val byRecency = compareByDescending<String> { latest.getValue(it).spokenAt }
    val followers = transitions.keys.sortedWith(compareByDescending<String> { transitions.getValue(it) }.then(byRecency))
    val frequent = frequency.keys.sortedWith(compareByDescending<String> { frequency.getValue(it) }.then(byRecency))
    return (followers + frequent)
        .distinct()
        .filter { it != lastKey }
        .take(limit)
        .map(latest::getValue)
}

private val SaidText.spokenAt: Long get() = date ?: createdAt ?: 0L

private val SaidText.messageKey: String get() = saidText.orEmpty().trim().lowercase()
