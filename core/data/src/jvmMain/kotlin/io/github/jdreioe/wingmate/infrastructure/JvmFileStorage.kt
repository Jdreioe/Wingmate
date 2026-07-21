package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.FileStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class JvmFileStorage(
    private val rootDir: File = File(System.getProperty("user.home"), ".wingmate/files")
) : FileStorage {
    init {
        rootDir.mkdirs()
    }

    override suspend fun save(fileName: String, content: String) = withContext(Dispatchers.IO) {
        val file = resolve(fileName)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    override suspend fun load(fileName: String): String? = withContext(Dispatchers.IO) {
        val file = resolve(fileName)
        if (file.exists()) file.readText() else null
    }

    override suspend fun saveBytes(fileName: String, content: ByteArray) = withContext(Dispatchers.IO) {
        val file = resolve(fileName)
        file.parentFile?.mkdirs()
        file.writeBytes(content)
    }

    override suspend fun loadBytes(fileName: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = resolve(fileName)
        if (file.exists()) file.readBytes() else null
    }

    override suspend fun exists(fileName: String): Boolean = withContext(Dispatchers.IO) {
        resolve(fileName).exists()
    }

    private fun resolve(fileName: String): File = File(rootDir, fileName)
}
