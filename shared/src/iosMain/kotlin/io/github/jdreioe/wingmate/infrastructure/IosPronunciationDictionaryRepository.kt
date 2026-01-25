package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository
import io.github.jdreioe.wingmate.domain.PronunciationEntry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

private val logger = KotlinLogging.logger {}

class IosPronunciationDictionaryRepository : PronunciationDictionaryRepository {
    private val defaults by lazy { NSUserDefaults.standardUserDefaults() }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val storageKey = "pronunciation_dictionary_v1"

    override suspend fun getAll(): List<PronunciationEntry> = withContext(Dispatchers.Default) {
        loadAll()
    }

    override suspend fun add(entry: PronunciationEntry) {
        withContext(Dispatchers.Default) {
            val list = loadAll().toMutableList()
            // Remove existing for same word to overwrite
            list.removeAll { it.word.equals(entry.word, ignoreCase = true) }
            list.add(entry)
            saveAll(list)
        }
    }

    override suspend fun delete(word: String) {
        withContext(Dispatchers.Default) {
            val list = loadAll().toMutableList()
            list.removeAll { it.word.equals(word, ignoreCase = true) }
            saveAll(list)
        }
    }

    private fun loadAll(): List<PronunciationEntry> {
        val text = defaults.stringForKey(storageKey) ?: return emptyList()
        return try {
            json.decodeFromString(ListSerializer(PronunciationEntry.serializer()), text)
        } catch (t: Throwable) {
            logger.warn(t) { "Failed to decode PronunciationEntry list" }
            emptyList()
        }
    }

    private fun saveAll(list: List<PronunciationEntry>) {
        try {
            val text = json.encodeToString(ListSerializer(PronunciationEntry.serializer()), list)
            defaults.setObject(text, storageKey)
            // defaults.synchronize() is deprecated and often unnecessary, but can call if needed
        } catch (t: Throwable) {
            logger.warn(t) { "Failed to save PronunciationEntry list" }
        }
    }
}
