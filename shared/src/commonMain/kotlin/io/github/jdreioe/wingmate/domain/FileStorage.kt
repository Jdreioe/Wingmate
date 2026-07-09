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
     * Checks if a file exists.
     */
    suspend fun exists(fileName: String): Boolean

    /**
     * Saves binary data to a file.
     * Overwrites if exists.
     */
    suspend fun saveBinary(fileName: String, data: ByteArray)

    /**
     * Reads binary data from a file.
     * Returns null if file does not exist.
     */
    suspend fun loadBinary(fileName: String): ByteArray?

    /**
     * Deletes a file or directory recursively.
     */
    suspend fun delete(fileName: String)

    /**
     * Lists all files in a directory.
     * Returns empty list if directory does not exist.
     */
    suspend fun listFiles(directory: String): List<String>

    /**
     * Calculates the total size of a directory in bytes.
     * Returns 0 if directory does not exist.
     */
    suspend fun directorySize(path: String): Long

    /**
     * Gets the size of a single file in bytes.
     * Returns 0 if file does not exist.
     */
    suspend fun fileSize(fileName: String): Long
}
