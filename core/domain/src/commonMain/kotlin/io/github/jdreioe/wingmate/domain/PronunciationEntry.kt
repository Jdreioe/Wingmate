package io.github.jdreioe.wingmate.domain

import kotlinx.serialization.Serializable

@Serializable
data class PronunciationEntry(
    val word: String,
    val phoneme: String,
    /** One of: text (easy alias), ipa, x-sampa, sapi, ups */
    val alphabet: String = "text"
)

/**
 * Replaces dictionary words with their spoken alias, matching whole words
 * case-insensitively and preferring longer words so "car" cannot rewrite
 * "carpet". Only `text` aliases apply; the phonetic alphabets need an SSML
 * `<phoneme>` tag and stay with the cloud engines.
 */
fun applySpokenAliases(text: String, dictionary: List<PronunciationEntry>): String {
    val aliases = dictionary
        .filter { it.alphabet == "text" && it.word.isNotBlank() && it.phoneme.isNotBlank() }
        .sortedByDescending { it.word.length }
    if (aliases.isEmpty()) return text
    // One pass over the text, so an alias can never be rewritten by a later
    // entry: substituting "carpet" as "car pet" must not then hit "car".
    // Alternation is ordered longest word first, so "carpet" wins the position.
    val words = Regex(
        aliases.joinToString("|") { "\\b${Regex.escape(it.word)}\\b" },
        RegexOption.IGNORE_CASE,
    )
    return words.replace(text) { match ->
        aliases.first { it.word.equals(match.value, ignoreCase = true) }.phoneme
    }
}
