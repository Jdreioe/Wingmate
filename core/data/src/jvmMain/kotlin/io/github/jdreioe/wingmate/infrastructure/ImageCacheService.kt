package io.github.jdreioe.wingmate.infrastructure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.security.MessageDigest

/**
 * Service to cache remote images locally.
 * Prevents mass API usage by downloading images once and serving from local file system.
 */
object ImageCacheService {
    // Basic file system helper - expected to be present or we use java.io directly for MVP
    private val cacheDir: File by lazy {
        val userHome = System.getProperty("user.home")
        val cache = File(userHome, ".wingmate/cache/images")
        if (!cache.exists()) cache.mkdirs()
        cache
    }

    /**
     * returns a local file path (file://...) for a given URL.
     * If URL is remote, downloads and caches it first.
     */
    suspend fun getCachedImagePath(url: String): String = withContext(Dispatchers.IO) {
        if (url.startsWith("file:") || url.startsWith("/") || !url.startsWith("http")) {
            return@withContext url
        }

        val fileName = hashString(url) + "." + (url.substringAfterLast('.', "png").take(3))
        val cacheFile = File(cacheDir, fileName)

        if (cacheFile.exists()) {
            return@withContext "file://${cacheFile.absolutePath}"
        }

        return@withContext try {
            val bytes = URL(url).readBytes()
            cacheFile.writeBytes(bytes)
            "file://${cacheFile.absolutePath}"
        } catch (e: Exception) {
            e.printStackTrace()
            url // Fallback to original URL on failure
        }
    }

    private fun hashString(input: String): String {
        return MessageDigest.getInstance("MD5")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
