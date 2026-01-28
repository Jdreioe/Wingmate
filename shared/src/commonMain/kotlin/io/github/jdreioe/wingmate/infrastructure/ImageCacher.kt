package io.github.jdreioe.wingmate.infrastructure

/**
 * Interface for caching remote images locally.
 */
interface ImageCacher {
    suspend fun getCachedImagePath(url: String): String
}
