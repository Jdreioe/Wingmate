package io.github.jdreioe.wingmate.domain

data class PronunciationEntry(
    val word: String,
    val phoneme: String,
    val alphabet: String = "ipa" // ipa, x-sampa, sapi, ups
)
