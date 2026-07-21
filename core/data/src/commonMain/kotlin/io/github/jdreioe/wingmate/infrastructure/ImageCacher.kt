package io.github.jdreioe.wingmate.infrastructure

/**
 * Interface for caching remote images locally.
 */
interface ImageCacher {
    suspend fun getCachedImagePath(url: String): String

    /** Persist one ARASAAC pictogram for offline use. Existing files are skipped. */
    suspend fun cacheArasaacSymbol(id: Long): Boolean = false

    /** Number of ARASAAC pictograms held in persistent offline storage. */
    suspend fun cachedArasaacSymbolCount(): Int = 0
}
