package io.github.jdreioe.wingmate.domain

// Turns a text field's before/after text into one ReplaceRange by trimming the common
// prefix and suffix, so UIs that only report full text never compute diffs themselves.

fun Message.Companion.fromTextDiff(
    currentText: String,
    newText: String,
    mathMode: Boolean = false,
): CommunicationAction.ReplaceRange {
    val prefixLength = commonPrefixLength(currentText, newText)
    val suffixLength = commonSuffixLength(currentText, newText, prefixLength)
    return CommunicationAction.ReplaceRange(
        range = TextSpan(prefixLength, currentText.length - suffixLength),
        replacement = newText.substring(prefixLength, newText.length - suffixLength),
        mathMode = mathMode,
    )
}

private fun commonPrefixLength(first: String, second: String): Int {
    val limit = minOf(first.length, second.length)
    var index = 0
    while (index < limit && first[index] == second[index]) index++
    return index
}

private fun commonSuffixLength(first: String, second: String, prefixLength: Int): Int {
    val limit = minOf(first.length - prefixLength, second.length - prefixLength)
    var length = 0
    while (length < limit && first[first.lastIndex - length] == second[second.lastIndex - length]) length++
    return length
}
