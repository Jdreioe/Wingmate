package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import io.github.jdreioe.wingmate.infrastructure.ImageCacher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.security.MessageDigest

class AndroidImageCacher(private val context: Context) : ImageCacher {
    override suspend fun getCachedImagePath(url: String): String = withContext(Dispatchers.IO) {
        if (url.startsWith("file:") || url.startsWith("/") || !url.startsWith("http")) {
            return@withContext url
        }

        val fileName = hashString(url) + "." + (url.substringAfterLast('.', "png").take(3))
        val cacheDir = File(context.cacheDir, "images")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        
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
            url 
        }
    }

    private fun hashString(input: String): String {
        return MessageDigest.getInstance("MD5")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
