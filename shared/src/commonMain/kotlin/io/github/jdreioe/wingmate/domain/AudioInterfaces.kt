package io.github.jdreioe.wingmate.domain

interface AudioPlayer {
    suspend fun playFile(path: String)
    suspend fun playSystemTts(text: String, voice: Voice?)
    suspend fun stop()
    suspend fun pause()
    suspend fun resume()
    fun isPlaying(): Boolean
}

interface FileStorage {
    suspend fun saveFile(name: String, data: ByteArray): String
    suspend fun getFile(path: String): ByteArray?
    suspend fun exists(path: String): Boolean
}
