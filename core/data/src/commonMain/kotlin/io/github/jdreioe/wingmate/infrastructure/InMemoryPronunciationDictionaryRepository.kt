package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.PronunciationEntry
import io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository

class InMemoryPronunciationDictionaryRepository : PronunciationDictionaryRepository {
    private val entries = mutableMapOf<String, PronunciationEntry>()

    override suspend fun getAll(): List<PronunciationEntry> {
        return entries.values.toList().sortedBy { it.word }
    }

    override suspend fun add(entry: PronunciationEntry) {
        entries[entry.word.lowercase()] = entry
    }

    override suspend fun delete(word: String) {
        entries.remove(word.lowercase())
    }

    override suspend fun clear() {
        entries.clear()
    }
}
