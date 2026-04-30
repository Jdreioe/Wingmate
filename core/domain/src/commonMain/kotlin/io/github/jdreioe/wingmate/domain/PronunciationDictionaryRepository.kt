package io.github.jdreioe.wingmate.domain

interface PronunciationDictionaryRepository {
    suspend fun getAll(): List<PronunciationEntry>
    suspend fun add(entry: PronunciationEntry)
    suspend fun delete(word: String)
    suspend fun clear()
}
