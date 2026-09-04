package io.github.jdreioe.wingmate.desktop

import io.github.jdreioe.wingmate.platform.ArchiveEntry
import io.github.jdreioe.wingmate.platform.ArchiveReadError
import io.github.jdreioe.wingmate.platform.ArchiveReadException
import io.github.jdreioe.wingmate.platform.ArchiveReader
import io.github.jdreioe.wingmate.platform.FilePicker
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.openZip

internal class DesktopFileAccess(private val fileSystem: FileSystem = FileSystem.SYSTEM) : FilePicker {
    override suspend fun pickFile(title: String, extensions: List<String>): String? = null
    override suspend fun readFileAsText(path: String): String? = runCatching {
        fileSystem.read(path.toPath()) { readUtf8() }
    }.getOrNull()
    override suspend fun openArchive(path: String): ArchiveReader? = runCatching {
        OkioArchiveReader(fileSystem.openZip(path.toPath()))
    }.getOrNull()
}

private class OkioArchiveReader(private val zip: FileSystem) : ArchiveReader {
    override suspend fun entries(): List<ArchiveEntry> = zip.listRecursively("/".toPath()).map { path ->
        val metadata = zip.metadata(path)
        ArchiveEntry(
            name = path.toString().removePrefix("/"),
            uncompressedSize = if (metadata.isDirectory) 0 else metadata.size ?: -1,
            compressedSize = metadata.extras.filterValues { it is Long }.values.firstOrNull() as? Long ?: -1,
            isDirectory = metadata.isDirectory,
        )
    }.toList()

    override suspend fun readEntry(name: String, maxBytes: Long, onChunk: suspend (ByteArray) -> Unit) {
        val path = ("/" + name.trimStart('/')).toPath()
        if (!zip.exists(path)) throw ArchiveReadException(ArchiveReadError.ENTRY_NOT_FOUND, "Missing archive entry '$name'")
        zip.read(path) {
            val buffer = okio.Buffer()
            var total = 0L
            while (true) {
                val read = read(buffer, 64 * 1024)
                if (read == -1L) break
                total += read
                if (total > maxBytes) throw ArchiveReadException(ArchiveReadError.ENTRY_TOO_LARGE, "Archive entry '$name' is too large")
                onChunk(buffer.readByteArray(read))
            }
        }
    }
    override suspend fun close() = Unit
}
