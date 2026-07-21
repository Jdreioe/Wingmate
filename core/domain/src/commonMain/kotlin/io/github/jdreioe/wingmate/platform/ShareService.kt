package io.github.jdreioe.wingmate.platform

/**
 * Cross-platform share helper to send audio files to other apps or handlers.
 * Return true if a share/open action was successfully initiated.
 */
interface ShareService {
    fun shareAudio(filePath: String): Boolean
    fun shareText(text: String): Boolean

    /**
     * Share a file with the given name and content bytes.
     * The implementation should write the bytes to a temporary file and open the system share sheet.
     */
    fun shareFile(fileName: String, content: ByteArray): Boolean
}
