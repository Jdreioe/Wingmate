package io.github.jdreioe.wingmate.domain

/**
 * Plays short board-button sound bites (OBF `sound_id` media).
 */
interface SoundPlayer {
    /**
     * Play raw audio bytes. Implementations should stop any prior playback.
     * @return true when playback was started
     */
    suspend fun playBytes(bytes: ByteArray, contentType: String? = null): Boolean

    /** Stop any in-progress playback. */
    suspend fun stop() {}
}

/** No-op player used when a platform has not registered a real implementation. */
class NoopSoundPlayer : SoundPlayer {
    override suspend fun playBytes(bytes: ByteArray, contentType: String?): Boolean = false
}
