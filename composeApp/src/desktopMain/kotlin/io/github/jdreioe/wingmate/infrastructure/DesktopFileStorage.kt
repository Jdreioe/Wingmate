package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.FileStorage
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

class DesktopFileStorage : FileStorage {
    override suspend fun saveFile(name: String, data: ByteArray): String {
        val userHome = System.getProperty("user.home")
        val dir = Paths.get(userHome, ".wingmate", "cache")
        if (!Files.exists(dir)) Files.createDirectories(dir)
        val file = dir.resolve(name)
        Files.write(file, data)
        return file.toAbsolutePath().toString()
    }

    override suspend fun getFile(path: String): ByteArray? {
        val file = File(path)
        return if (file.exists()) file.readBytes() else null
    }

    override suspend fun exists(path: String): Boolean {
        return File(path).exists()
    }
}
