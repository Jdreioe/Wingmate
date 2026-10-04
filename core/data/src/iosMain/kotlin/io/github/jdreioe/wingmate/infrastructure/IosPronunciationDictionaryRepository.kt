package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository
import io.github.jdreioe.wingmate.domain.PronunciationEntry
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class IosPronunciationDictionaryRepository : PronunciationDictionaryRepository {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val serializer = ListSerializer(PronunciationEntry.serializer())
    private val store = IosPreferencesJsonStore(
        key = "pronunciation_dictionary_v1",
        encode = { json.encodeToString(serializer, it) },
        decode = { json.decodeFromString(serializer, it) },
    )

    override suspend fun getAll(): List<PronunciationEntry> = store.read(::emptyList)

    override suspend fun add(entry: PronunciationEntry) {
        store.update(::emptyList) { existing ->
            existing.filterNot { it.word.equals(entry.word, ignoreCase = true) } + entry
        }
    }

    override suspend fun delete(word: String) {
        store.update(::emptyList) { existing ->
            existing.filterNot { it.word.equals(word, ignoreCase = true) }
        }
    }

    override suspend fun clear() {
        store.replace(emptyList(), ::emptyList)
    }
}
