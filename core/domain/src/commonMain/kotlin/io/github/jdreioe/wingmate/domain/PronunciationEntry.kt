package io.github.jdreioe.wingmate.domain

import kotlinx.serialization.Serializable

@Serializable
data class PronunciationEntry(
    val word: String,
    val phoneme: String,
    /** One of: text (easy alias), ipa, x-sampa, sapi, ups */
    val alphabet: String = "text"
)
