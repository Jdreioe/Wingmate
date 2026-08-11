package io.github.jdreioe.wingmate.platform

/**
 * Platform-specific file picker service for importing files.
 */
interface FilePicker {
    /**
     * Opens a file picker dialog to select a file.
     * @param title Dialog title
     * @param extensions List of allowed extensions (e.g., "obf", "obz")
     * @return The file path if selected, null if cancelled
     */
    suspend fun pickFile(title: String, extensions: List<String>): String?

    /**
     * Read the contents of a file as text.
     * @param path The file path
     * @return The file contents as a string
     */
    suspend fun readFileAsText(path: String): String?
    
    /** Opens a bounded, streaming archive reader. The caller must close it. */
    suspend fun openArchive(path: String): ArchiveReader?

    /** Opens an archive already obtained from a trusted in-app source. */
    suspend fun openArchiveBytes(content: ByteArray): ArchiveReader? = null
}

data class ArchiveEntry(
    val name: String,
    val uncompressedSize: Long,
    val compressedSize: Long,
    val isDirectory: Boolean = false,
    val isEncrypted: Boolean = false
)

/** Platform archive access without exposing JVM streams to common code. */
interface ArchiveReader {
    suspend fun entries(): List<ArchiveEntry>

    /**
     * Reads one entry in bounded chunks. Implementations must stop before delivering
     * more than [maxBytes] and throw [ArchiveReadException] when the limit is exceeded.
     */
    suspend fun readEntry(
        name: String,
        maxBytes: Long,
        onChunk: suspend (ByteArray) -> Unit
    )

    suspend fun close()
}

enum class ArchiveReadError {
    ENTRY_NOT_FOUND,
    ENTRY_TOO_LARGE,
    MALFORMED_ARCHIVE,
    IO_ERROR
}

class ArchiveReadException(
    val error: ArchiveReadError,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

suspend fun ArchiveReader.readEntryBytes(name: String, maxBytes: Long): ByteArray {
    val chunks = mutableListOf<ByteArray>()
    var size = 0
    readEntry(name, maxBytes) { chunk ->
        size += chunk.size
        chunks += chunk
    }
    val result = ByteArray(size)
    var offset = 0
    chunks.forEach { chunk ->
        chunk.copyInto(result, offset)
        offset += chunk.size
    }
    return result
}
