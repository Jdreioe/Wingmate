package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.infrastructure.ImageCacher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.net.HttpURLConnection
import java.security.MessageDigest

class JvmImageCacher : ImageCacher {
    private val arasaacDir: File by lazy {
        File(System.getProperty("user.home"), ".wingmate/arasaac-symbols").also { it.mkdirs() }
    }
    private val cacheDir: File by lazy {
        val userHome = System.getProperty("user.home")
        val cache = File(userHome, ".wingmate/cache/images")
        if (!cache.exists()) cache.mkdirs()
        cache
    }

    override suspend fun cacheArasaacSymbol(id: Long): Boolean = withContext(Dispatchers.IO) {
        val target = File(arasaacDir, "$id.png")
        if (target.exists() && target.length() > 0) return@withContext true
        val temporary = File(arasaacDir, "$id.part")
        try {
            val connection = URL("https://api.arasaac.org/api/pictograms/$id?download=false&resolution=500")
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            if (connection.responseCode !in 200..299) return@withContext false
            connection.inputStream.use { input -> temporary.outputStream().use(input::copyTo) }
            if (temporary.length() == 0L) return@withContext false
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            true
        } catch (_: Throwable) {
            temporary.delete()
            false
        }
    }

    override suspend fun cachedArasaacSymbolCount(): Int = withContext(Dispatchers.IO) {
        arasaacDir.listFiles { file -> file.isFile && file.extension == "png" }?.size ?: 0
    }

    override suspend fun getCachedImagePath(url: String): String = withContext(Dispatchers.IO) {
        if (url.startsWith("file:") || url.startsWith("/") || !url.startsWith("http")) {
            return@withContext url
        }

        arasaacIdFrom(url)?.let { id ->
            val offlineFile = File(arasaacDir, "$id.png")
            if (offlineFile.exists() && offlineFile.length() > 0) {
                return@withContext "file://${offlineFile.absolutePath}"
            }
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

    private fun arasaacIdFrom(url: String): Long? =
        Regex("api\\.arasaac\\.org/(?:api|v1)/pictograms/(\\d+)")
            .find(url)?.groupValues?.getOrNull(1)?.toLongOrNull()
}
