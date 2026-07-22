package io.github.jdreioe.wingmate.domain

/**
 * A platform-neutral, half-open text range. Kotlin and NSString both count UTF-16
 * code units, so these offsets can safely cross the Kotlin/Swift boundary.
 */
data class TextSpan(
    val start: Int,
    val endExclusive: Int,
) {
    val length: Int get() = (endExclusive - start).coerceAtLeast(0)
}

data class TextEditResult(
    val text: String,
    val cursor: Int,
)

/** Shared text-editing rules used by the native Android and iOS presentation layers. */
object TextEditingPolicy {
    fun normalize(span: TextSpan, textLength: Int): TextSpan {
        val first = span.start.coerceIn(0, textLength)
        val second = span.endExclusive.coerceIn(0, textLength)
        return TextSpan(minOf(first, second), maxOf(first, second))
    }

    fun merge(spans: List<TextSpan>, textLength: Int): List<TextSpan> {
        val sorted = spans
            .map { normalize(it, textLength) }
            .filter { it.length > 0 }
            .sortedBy { it.start }
        if (sorted.isEmpty()) return emptyList()

        val merged = mutableListOf<TextSpan>()
        var current = sorted.first()
        for (candidate in sorted.drop(1)) {
            if (candidate.start <= current.endExclusive) {
                current = TextSpan(current.start, maxOf(current.endExclusive, candidate.endExclusive))
            } else {
                merged += current
                current = candidate
            }
        }
        merged += current
        return merged
    }

    fun isFullyCovered(selection: TextSpan, spans: List<TextSpan>, textLength: Int): Boolean {
        val target = normalize(selection, textLength)
        if (target.length == 0) return false

        var cursor = target.start
        for (span in merge(spans, textLength)) {
            if (span.endExclusive <= cursor) continue
            if (span.start > cursor) return false
            cursor = minOf(target.endExclusive, span.endExclusive)
            if (cursor == target.endExclusive) return true
        }
        return false
    }

    fun toggle(spans: List<TextSpan>, selection: TextSpan, textLength: Int): List<TextSpan> {
        val target = normalize(selection, textLength)
        if (target.length == 0) return merge(spans, textLength)
        val cleaned = merge(spans, textLength)
        if (!isFullyCovered(target, cleaned, textLength)) return merge(cleaned + target, textLength)

        return merge(cleaned.flatMap { span ->
            if (target.endExclusive <= span.start || target.start >= span.endExclusive) {
                listOf(span)
            } else {
                buildList {
                    if (target.start > span.start) add(TextSpan(span.start, target.start))
                    if (target.endExclusive < span.endExclusive) add(TextSpan(target.endExclusive, span.endExclusive))
                }
            }
        }, textLength)
    }

    /** Keeps marked spans aligned when a UI reports the complete text before and after an edit. */
    fun adjustAfterEdit(oldText: String, newText: String, spans: List<TextSpan>): List<TextSpan> {
        if (spans.isEmpty()) return emptyList()
        if (oldText == newText) return merge(spans, newText.length)

        val prefix = commonPrefixLength(oldText, newText)
        val suffix = commonSuffixLength(oldText, newText, prefix)
        val oldChangedEnd = oldText.length - suffix
        val newChangedEnd = newText.length - suffix
        val delta = newChangedEnd - oldChangedEnd

        val updated = spans.sortedBy { it.start }.flatMap { span ->
            when {
                span.endExclusive <= prefix -> listOf(span)
                span.start >= oldChangedEnd -> listOf(TextSpan(span.start + delta, span.endExclusive + delta))
                else -> buildList {
                    if (span.start < prefix) add(TextSpan(span.start, prefix))
                    if (span.endExclusive > oldChangedEnd) {
                        add(TextSpan(oldChangedEnd + delta, span.endExclusive + delta))
                    }
                }
            }
        }
        return merge(updated, newText.length)
    }

    /** Keeps marked spans aligned when a native editor reports an explicit replacement range. */
    fun adjustForReplacement(
        textLength: Int,
        edit: TextSpan,
        replacementLength: Int,
        spans: List<TextSpan>,
    ): List<TextSpan> {
        val target = normalize(edit, textLength)
        val safeReplacementLength = replacementLength.coerceAtLeast(0)
        val delta = safeReplacementLength - target.length
        val updated = merge(spans, textLength).flatMap { span ->
            when {
                target.endExclusive <= span.start ->
                    listOf(TextSpan(span.start + delta, span.endExclusive + delta))
                target.start >= span.endExclusive -> listOf(span)
                target.start <= span.start && target.endExclusive >= span.endExclusive -> emptyList()
                target.start <= span.start -> {
                    val remaining = span.endExclusive - target.endExclusive
                    if (remaining > 0) {
                        val start = target.start + safeReplacementLength
                        listOf(TextSpan(start, start + remaining))
                    } else emptyList()
                }
                target.endExclusive >= span.endExclusive ->
                    listOf(TextSpan(span.start, target.start)).filter { it.length > 0 }
                else -> listOf(TextSpan(span.start, span.endExclusive + delta)).filter { it.length > 0 }
            }
        }
        return merge(updated, (textLength + delta).coerceAtLeast(0))
    }

    fun completeWord(text: String, cursor: Int, suggestion: String): TextEditResult {
        val position = cursor.coerceIn(0, text.length)
        val wordStart = text.lastIndexOf(' ', position - 1) + 1
        val partialWord = text.substring(wordStart, position)
        val completesPartial = partialWord.isNotEmpty() &&
            suggestion.startsWith(partialWord, ignoreCase = true)

        val insertionStart = if (completesPartial) wordStart else position
        var replacementEnd = position
        while (replacementEnd < text.length && text[replacementEnd] == ' ') replacementEnd++
        val prefix = if (!completesPartial && position > 0 && text[position - 1] != ' ') " " else ""
        val inserted = prefix + suggestion + " "
        val result = text.replaceRange(insertionStart, replacementEnd, inserted)
        return TextEditResult(result, insertionStart + inserted.length)
    }

    fun insert(text: String, cursor: Int, value: String): TextEditResult {
        val position = cursor.coerceIn(0, text.length)
        return TextEditResult(text.replaceRange(position, position, value), position + value.length)
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val limit = minOf(a.length, b.length)
        var index = 0
        while (index < limit && a[index] == b[index]) index++
        return index
    }

    private fun commonSuffixLength(a: String, b: String, prefix: Int): Int {
        val limit = minOf(a.length - prefix, b.length - prefix)
        var count = 0
        while (count < limit && a[a.length - 1 - count] == b[b.length - 1 - count]) count++
        return count
    }
}
