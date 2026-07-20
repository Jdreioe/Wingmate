package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import io.github.jdreioe.wingmate.domain.FileStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AndroidFileStorage(private val context: Context) : FileStorage {
    override suspend fun save(fileName: String, content: String) = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, fileName)
        file.writeText(content)
    }

    override suspend fun load(fileName: String): String? = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, fileName)
        if (file.exists()) {
            file.readText()
        } else {
            null
        }
    }

    override suspend fun exists(fileName: String): Boolean = withContext(Dispatchers.IO) {
        File(context.filesDir, fileName).exists()
    }

    override suspend fun saveBytes(fileName: String, content: ByteArray) = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, fileName)
        file.parentFile?.mkdirs()
        file.writeBytes(content)
    }

    override suspend fun loadBytes(fileName: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, fileName)
        if (file.exists()) file.readBytes() else null
    }
}
