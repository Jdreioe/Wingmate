package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/**
 * Speech history in a private Application Support file. History saved by older
 * versions as one NSUserDefaults blob is moved there on first use.
 */
class IosSaidTextRepository : SaidTextRepository {
    private val file = JsonLinesSaidTextRepository(FileSystem.SYSTEM, "${applicationSupportDirectory()}/wingmate/said_text.jsonl".toPath())
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val serializer = ListSerializer(SaidText.serializer())
    private val legacy = IosPreferencesJsonStore(
        key = "said_text_list_v1",
        encode = { json.encodeToString(serializer, it) },
        decode = { json.decodeFromString(serializer, it) },
    )
    private val migration = Mutex()
    private var migrated = false

    override suspend fun add(item: SaidText): SaidText = store().add(item)

    override suspend fun list(): List<SaidText> = store().list()

    override suspend fun deleteAll() = store().deleteAll()

    override suspend fun addAll(items: List<SaidText>) = store().addAll(items)

    private suspend fun store(): JsonLinesSaidTextRepository = migration.withLock {
        if (!migrated) {
            // Replace, not append: if the key removal failed last time, the copy is simply redone.
            legacy.readNullable()?.let { history ->
                file.replaceAll(history)
                legacy.remove()
            }
            migrated = true
        }
        file
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun applicationSupportDirectory(): String {
    val urls = NSFileManager.defaultManager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask)
    return checkNotNull((urls.firstOrNull() as? NSURL)?.path) { "Application Support is unavailable" }
}
