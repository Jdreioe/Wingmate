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
}
