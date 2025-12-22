package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import io.github.jdreioe.wingmate.domain.FileStorage
import java.io.File
import java.util.UUID

class AndroidFileStorage(private val context: Context) : FileStorage {
    override suspend fun saveFile(name: String, data: ByteArray): String {
        val dir = File(context.filesDir, "audio_cache")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, name)
        file.writeBytes(data)
        return file.absolutePath
    }

    override suspend fun getFile(path: String): ByteArray? {
        val file = File(path)
        return if (file.exists()) file.readBytes() else null
    }

    override suspend fun exists(path: String): Boolean {
        return File(path).exists()
    }
}
