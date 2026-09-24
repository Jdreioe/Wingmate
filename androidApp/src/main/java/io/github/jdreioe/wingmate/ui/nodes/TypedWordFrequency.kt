package io.github.jdreioe.wingmate.ui.nodes

import io.github.jdreioe.wingmate.domain.nodes.*
import java.util.Locale

internal val typedWordPattern = Regex("[\\p{L}\\p{N}]+(?:['’\\-][\\p{L}\\p{N}]+)*")
internal fun wordKey(word: String) = word.lowercase(Locale.ROOT)

/** A caret chooses its containing word, or the word immediately before it. */
internal fun wordAtSelection(text: String, start: Int, end: Int): String? {
    val left = minOf(start, end)
    val right = maxOf(start, end)
    return typedWordPattern.findAll(text).firstOrNull {
        if (left == right) left in it.range.first..(it.range.last + 1)
        else left == it.range.first && right == it.range.last + 1
    }?.value
}

/** Counts completed occurrences, not intermediate spellings, deletions or cursor movement. */
internal class TypedWordFrequency(initial: Map<String, Int> = emptyMap(), initialCredit: Pair<Int, String>? = null) {
    var counts: Map<String, Int> = initial
        private set
    var creditedTail: Pair<Int, String>? = initialCredit
        private set

    private fun completed(text: String, credit: Pair<Int, String>?): Map<String, Int> =
        typedWordPattern.findAll(text).filter {
            (it.range.last < text.lastIndex && text[it.range.last + 1] !in "'’-") || credit == (it.range.first to it.value)
        }.groupingBy { wordKey(it.value) }.eachCount()

    fun edit(old: String, new: String) {
        val before = completed(old, creditedTail)
        val after = completed(new, creditedTail)
        val updated = counts.toMutableMap()
        after.forEach { (word, count) ->
            val added = (count - (before[word] ?: 0)).coerceAtLeast(0)
            if (added > 0) updated[word] = ((updated[word] ?: 0).toLong() + added).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        counts = updated
        val last = typedWordPattern.findAll(new).lastOrNull()
        if (last == null || last.range.last != new.lastIndex || creditedTail != (last.range.first to last.value)) creditedTail = null
    }

    fun finishForConversion(text: String, word: String) {
        val last = typedWordPattern.findAll(text).lastOrNull() ?: return
        if (last.range.last != text.lastIndex || wordKey(last.value) != wordKey(word)) return
        val marker = last.range.first to last.value
        if (creditedTail == marker) return
        val key = wordKey(word)
        counts = counts + (key to ((counts[key] ?: 0).toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        creditedTail = marker
    }
}
