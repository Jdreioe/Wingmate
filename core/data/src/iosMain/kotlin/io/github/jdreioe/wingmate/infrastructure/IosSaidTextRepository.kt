package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import io.github.jdreioe.wingmate.domain.OperationalLogger
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.datetime.Clock

class IosSaidTextRepository : SaidTextRepository {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val serializer = ListSerializer(SaidText.serializer())
    private val store = IosPreferencesJsonStore(
        key = "said_text_list_v1",
        encode = { json.encodeToString(serializer, it) },
        decode = { json.decodeFromString(serializer, it) },
    )

    override suspend fun add(item: SaidText): SaidText {
        var stored = item
        val updated = store.update(::emptyList) { existing ->
            val now = Clock.System.now().toEpochMilliseconds()
            val nextPos = (existing.maxOfOrNull { it.position ?: 0 } ?: 0) + 1
            stored = item.copy(
                id = item.id ?: nextPos,
                date = item.date ?: now,
                createdAt = item.createdAt ?: now,
                position = item.position ?: nextPos,
            )
            existing + stored
        }
        OperationalLogger.debug("speech_history.save", "succeeded", count = updated.size)
        return stored
    }

    override suspend fun list(): List<SaidText> = store.read(::emptyList)

    override suspend fun deleteAll() {
        store.replace(emptyList(), ::emptyList)
    }

    override suspend fun addAll(items: List<SaidText>) {
        store.replace(items, ::emptyList)
    }
}
