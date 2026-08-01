package io.github.jdreioe.wingmate.domain

/**
 * Platform-agnostic interface for reading and writing files to app-private storage.
 */
interface FileStorage {
    /**
     * Saves text content to a file with the given name.
     * Overwrites if exists.
     */
    suspend fun save(fileName: String, content: String)

    /**
     * Reads text content from a file with the given name.
     * Returns null if file does not exist.
     */
    suspend fun load(fileName: String): String?

    /**
     * Saves binary content to a file with the given name.
     * Overwrites if exists. Parent directories are created as needed.
     */
    suspend fun saveBytes(fileName: String, content: ByteArray)

    /**
     * Writes chunks without requiring the caller to hold the complete file. Platform
     * storage implementations override this to stream directly to disk.
     */
    suspend fun saveStream(
        fileName: String,
        producer: suspend (emit: suspend (ByteArray) -> Unit) -> Unit
    ) {
        val chunks = mutableListOf<ByteArray>()
        var size = 0
        producer { chunk ->
            size += chunk.size
            chunks += chunk.copyOf()
        }
        val bytes = ByteArray(size)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(bytes, offset)
            offset += chunk.size
        }
        saveBytes(fileName, bytes)
    }

    /**
     * Reads binary content from a file with the given name.
     * Returns null if the file does not exist.
     */
    suspend fun loadBytes(fileName: String): ByteArray?

    /**
     * Checks if a file exists.
     */
    suspend fun exists(fileName: String): Boolean

    /** Removes one app-private file. Missing files are treated as success. */
    suspend fun delete(fileName: String)
}
