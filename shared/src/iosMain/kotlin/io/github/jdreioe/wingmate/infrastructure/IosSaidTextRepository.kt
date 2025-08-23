package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.datetime.Clock
import platform.Foundation.NSUserDefaults

private val saidLogger = KotlinLogging.logger {}

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
        saidLogger.debug { "Saved SaidText item id=${enriched.id} voice=${enriched.voiceName} lang=${enriched.primaryLanguage} path=${enriched.audioFilePath}" }
        enriched
    }

    override suspend fun list(): List<SaidText> = withContext(Dispatchers.Default) { loadAll() }

    private fun loadAll(): List<SaidText> {
        val text = defaults.stringForKey(storageKey) ?: return emptyList()
        return try {
            json.decodeFromString(ListSerializer(SaidText.serializer()), text)
        } catch (t: Throwable) {
            saidLogger.warn(t) { "Failed to decode SaidText list; returning empty" }
            emptyList()
        }
    }

    private fun saveAll(list: List<SaidText>) {
        try {
            val text = json.encodeToString(ListSerializer(SaidText.serializer()), list)
            defaults.setObject(text, storageKey)
            defaults.synchronize()
        } catch (t: Throwable) {
            saidLogger.warn(t) { "Failed to save SaidText list" }
        }
    }
}
