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
    
    /**
     * Reads all entries from a ZIP file.
     * @param path The file path
     * @return Map of entry name to content bytes, or null if failed
     */
    suspend fun readZipEntries(path: String): Map<String, ByteArray>?
}
