package io.github.jdreioe.wingmate.domain

interface AacLogger {
    fun logButtonClick(label: String, boardId: String? = null, phraseId: String? = null)
    fun logSentenceSpeak(sentence: String)
    fun setEnabled(enabled: Boolean)
}
