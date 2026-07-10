package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import io.github.jdreioe.wingmate.domain.FileStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AndroidFileStorage(private val context: Context) : FileStorage {
    override suspend fun save(fileName: String, content: String) = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, fileName)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    override suspend fun load(fileName: String): String? = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, fileName)
        if (file.exists()) file.readText() else null
    }

    override suspend fun exists(fileName: String): Boolean = withContext(Dispatchers.IO) {
        File(context.filesDir, fileName).exists()
    }

    override suspend fun saveBinary(fileName: String, data: ByteArray) = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, fileName)
        file.parentFile?.mkdirs()
        file.writeBytes(data)
    }

    override suspend fun loadBinary(fileName: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, fileName)
        if (file.exists()) file.readBytes() else null
    }

    override suspend fun delete(fileName: String) = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, fileName)
        if (file.isDirectory) file.deleteRecursively() else file.delete()
        Unit
    }

    override suspend fun listFiles(directory: String): List<String> = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, directory)
        if (dir.exists() && dir.isDirectory) {
            dir.list()?.map { "$directory/$it" } ?: emptyList()
        } else emptyList()
    }

    override suspend fun directorySize(path: String): Long = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, path)
        if (dir.exists() && dir.isDirectory) {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else 0L
    }

    override suspend fun fileSize(fileName: String): Long = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, fileName)
        if (file.exists()) file.length() else 0L
    }
}
