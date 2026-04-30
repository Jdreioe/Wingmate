package io.github.jdreioe.wingmate.platform

interface AudioClipboard {
    /**
     * Copy the audio file at [filePath] to the system clipboard (best-effort on each platform).
     * Returns true if an attempt was made successfully.
     */
    fun copyAudioFile(filePath: String): Boolean
}
