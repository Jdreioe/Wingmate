package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.PersistenceError
import io.github.jdreioe.wingmate.domain.PersistenceException
import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.time.Clock

/**
 * Speech history as one JSON [SaidText] per line, so saving an utterance appends
 * a line instead of rewriting the whole history.
 *
 * A crash mid-append can leave an unparseable last line; it is dropped and the
 * file is rewritten cleanly on the next write. Any other unreadable line fails
 * the read instead of silently losing history.
 */
internal class JsonLinesSaidTextRepository(
    private val fileSystem: FileSystem,
    private val file: Path,
) : SaidTextRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var loaded: List<SaidText>? = null
    private var tornLastLine = false

    override suspend fun add(item: SaidText): SaidText = locked {
        val existing = load()
        val now = Clock.System.now().toEpochMilliseconds()
        val nextPosition = (existing.maxOfOrNull { it.position ?: 0 } ?: 0) + 1
        val stored = item.copy(
            id = item.id ?: nextPosition,
            date = item.date ?: now,
            createdAt = item.createdAt ?: now,
            position = item.position ?: nextPosition,
        )
        append(existing, listOf(stored))
        stored
    }

    override suspend fun list(): List<SaidText> = locked { load() }

    override suspend fun deleteAll() = locked {
        write { fileSystem.delete(file, mustExist = false) }
        loaded = emptyList()
        tornLastLine = false
    }

    override suspend fun addAll(items: List<SaidText>) = locked {
        if (items.isNotEmpty()) append(load(), items)
        Unit
    }

    /** Replaces the whole history in one atomic write. */
    suspend fun replaceAll(items: List<SaidText>) = locked { rewrite(items) }

    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.Default) { mutex.withLock { block() } }

    private fun load(): List<SaidText> {
        loaded?.let { return it }
        val text = try {
            if (!fileSystem.exists(file)) "" else fileSystem.read(file) { readUtf8() }
        } catch (error: IOException) {
            throw PersistenceException(PersistenceError.Io, error)
        }
        // Every complete write ends with a newline, so a missing one means the last append was cut short.
        tornLastLine = text.isNotEmpty() && !text.endsWith('\n')
        val lines = text.split('\n')
        val items = lines.mapIndexedNotNull { index, line ->
            if (line.isBlank()) return@mapIndexedNotNull null
            try {
                json.decodeFromString(SaidText.serializer(), line)
            } catch (error: SerializationException) {
                if (!tornLastLine || index != lines.lastIndex) {
                    throw PersistenceException(PersistenceError.CorruptOrUnsupported, error)
                }
                null
            }
        }
        return items.also { loaded = it }
    }

    private fun append(existing: List<SaidText>, items: List<SaidText>) {
        if (tornLastLine) return rewrite(existing + items)
        write {
            file.parent?.let(fileSystem::createDirectories)
            fileSystem.appendingSink(file).buffer().use { sink -> items.forEach { sink.writeUtf8(encode(it)) } }
        }
        loaded = existing + items
    }

    private fun rewrite(items: List<SaidText>) {
        val temporary = "$file.tmp".toPath()
        write {
            file.parent?.let(fileSystem::createDirectories)
            fileSystem.write(temporary) { items.forEach { writeUtf8(encode(it)) } }
            fileSystem.atomicMove(temporary, file)
        }
        loaded = items
        tornLastLine = false
    }

    private fun encode(item: SaidText) = json.encodeToString(SaidText.serializer(), item) + "\n"

    private inline fun write(block: () -> Unit) = try {
        block()
    } catch (error: IOException) {
        throw PersistenceException(PersistenceError.Io, error)
    }
}
