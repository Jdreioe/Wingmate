package io.github.jdreioe.wingmate.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import org.apache.commons.compress.archivers.zip.ZipFile

class JvmZipArchiveReader(private val file: File) : ArchiveReader {
    private val zip = try {
        ZipFile.builder().setFile(file).get()
    } catch (error: Throwable) {
        throw ArchiveReadException(ArchiveReadError.MALFORMED_ARCHIVE, "Malformed ZIP archive", error)
    }

    override suspend fun entries(): List<ArchiveEntry> = withContext(Dispatchers.IO) {
        val result = mutableListOf<ArchiveEntry>()
        val enumeration = zip.entries
        while (enumeration.hasMoreElements()) {
            val entry = enumeration.nextElement()
            result += ArchiveEntry(
                name = entry.name,
                uncompressedSize = entry.size,
                compressedSize = entry.compressedSize,
                isDirectory = entry.isDirectory,
                isEncrypted = entry.generalPurposeBit.usesEncryption()
            )
        }
        result
    }

    override suspend fun readEntry(
        name: String,
        maxBytes: Long,
        onChunk: suspend (ByteArray) -> Unit
    ) = withContext(Dispatchers.IO) {
        val entry = zip.getEntry(name)
            ?: throw ArchiveReadException(ArchiveReadError.ENTRY_NOT_FOUND, "Archive entry not found: $name")
        if (entry.size > maxBytes) {
            throw ArchiveReadException(ArchiveReadError.ENTRY_TOO_LARGE, "Archive entry exceeds its size limit: $name")
        }
        try {
            zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) {
                        throw ArchiveReadException(ArchiveReadError.ENTRY_TOO_LARGE, "Archive entry exceeds its size limit: $name")
                    }
                    onChunk(buffer.copyOf(count))
                }
            }
        } catch (error: ArchiveReadException) {
            throw error
        } catch (error: Throwable) {
            throw ArchiveReadException(ArchiveReadError.IO_ERROR, "Could not read archive entry: $name", error)
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) { zip.close() }
}
