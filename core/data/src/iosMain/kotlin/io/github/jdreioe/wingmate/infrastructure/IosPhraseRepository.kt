package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.PhraseRepository
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.time.Clock

class IosPhraseRepository : PhraseRepository {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val serializer = ListSerializer(Phrase.serializer())
    private val store = IosPreferencesJsonStore(
        key = "phrases_v1",
        encode = { json.encodeToString(serializer, it) },
        decode = { json.decodeFromString(serializer, it) },
    )

    override suspend fun getAll(): List<Phrase> = store.read(::emptyList)

    override suspend fun add(phrase: Phrase): Phrase {
        val p = phrase.copy(
            id = phrase.id.ifBlank { Random.nextInt().toString() },
            createdAt = if (phrase.createdAt == 0L) Clock.System.now().toEpochMilliseconds() else phrase.createdAt
        )
        store.update(::emptyList) { it + p }
        return p
    }

    override suspend fun update(phrase: Phrase): Phrase {
        store.update(::emptyList) { existing ->
            existing.map { if (it.id == phrase.id) phrase else it }
        }
        return phrase
    }

    override suspend fun delete(id: String) {
        store.update(::emptyList) { it.filterNot { phrase -> phrase.id == id } }
    }

    override suspend fun move(fromIndex: Int, toIndex: Int) {
        store.update(::emptyList) { existing ->
            if (fromIndex !in existing.indices) return@update existing
            existing.toMutableList().apply {
                val item = removeAt(fromIndex)
                add(toIndex.coerceIn(0, size), item)
            }
        }
    }
}
