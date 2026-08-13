package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.loggingClassName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.datetime.Clock
import platform.Foundation.NSUserDefaults

class IosSaidTextRepository : SaidTextRepository {
    private val defaults by lazy { NSUserDefaults.standardUserDefaults() }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val storageKey = "said_text_list_v1"

    override suspend fun add(item: SaidText): SaidText = withContext(Dispatchers.Default) {
    val now = Clock.System.now().toEpochMilliseconds()
        val existing = loadAll().toMutableList()
        val nextPos = (existing.maxOfOrNull { it.position ?: 0 } ?: 0) + 1
        val enriched = item.copy(
            id = item.id ?: nextPos,
            date = item.date ?: now,
            createdAt = item.createdAt ?: now,
            position = item.position ?: nextPos,
        )
        existing.add(enriched)
        saveAll(existing)
        OperationalLogger.debug("speech_history.save", "succeeded", count = existing.size)
        enriched
    }

    override suspend fun list(): List<SaidText> = withContext(Dispatchers.Default) { loadAll() }

    override suspend fun deleteAll() = withContext(Dispatchers.Default) {
        saveAll(emptyList())
    }

    override suspend fun addAll(items: List<SaidText>) = withContext(Dispatchers.Default) {
        saveAll(items)
    }

    private fun loadAll(): List<SaidText> {
        val text = defaults.stringForKey(storageKey) ?: return emptyList()
        return try {
            json.decodeFromString(ListSerializer(SaidText.serializer()), text)
        } catch (t: Throwable) {
            OperationalLogger.warn(
                operation = "speech_history.load",
                outcome = "failed",
                exceptionClass = t.loggingClassName(),
            )
            emptyList()
        }
    }

    private fun saveAll(list: List<SaidText>) {
        try {
            val text = json.encodeToString(ListSerializer(SaidText.serializer()), list)
            defaults.setObject(text, storageKey)
            defaults.synchronize()
        } catch (t: Throwable) {
            OperationalLogger.warn(
                operation = "speech_history.save",
                outcome = "failed",
                exceptionClass = t.loggingClassName(),
            )
        }
    }
}
