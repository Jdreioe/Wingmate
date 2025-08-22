package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.PhraseRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults
import kotlin.random.Random
import kotlinx.datetime.Clock

class IosPhraseRepository : PhraseRepository {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val prefs by lazy { NSUserDefaults.standardUserDefaults() }
    private val key = "phrases_v1"

    private suspend fun loadAll(): MutableList<Phrase> = withContext(Dispatchers.Default) {
        val text = prefs.stringForKey(key) ?: return@withContext mutableListOf()
        runCatching { json.decodeFromString(ListSerializer(Phrase.serializer()), text) }
            .getOrNull()?.toMutableList() ?: mutableListOf()
    }

    private suspend fun saveAll(list: List<Phrase>) = withContext(Dispatchers.Default) {
        val text = json.encodeToString(ListSerializer(Phrase.serializer()), list)
        prefs.setObject(text, forKey = key)
        prefs.synchronize()
        Unit
    }

    override suspend fun getAll(): List<Phrase> = loadAll()

    override suspend fun add(phrase: Phrase): Phrase {
        val list = loadAll()
        val p = phrase.copy(
            id = phrase.id.ifBlank { Random.nextInt().toString() },
            createdAt = if (phrase.createdAt == 0L) Clock.System.now().toEpochMilliseconds() else phrase.createdAt
        )
        list.add(p)
        saveAll(list)
        return p
    }

    override suspend fun update(phrase: Phrase): Phrase {
        val list = loadAll()
        val idx = list.indexOfFirst { it.id == phrase.id }
        if (idx >= 0) {
            list[idx] = phrase
            saveAll(list)
        }
        return phrase
    }

    override suspend fun delete(id: String) {
        val list = loadAll()
        val newList = list.filterNot { it.id == id }
        saveAll(newList)
        Unit
    }

    override suspend fun move(fromIndex: Int, toIndex: Int) {
        val list = loadAll()
        if (fromIndex < 0 || fromIndex >= list.size) return
        val item = list.removeAt(fromIndex)
        val insertIndex = toIndex.coerceIn(0, list.size)
        list.add(insertIndex, item)
        saveAll(list)
        Unit
    }
}
