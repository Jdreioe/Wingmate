package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.application.BackupMediaAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class DesktopBackupMediaAccess : BackupMediaAccess {
    override suspend fun read(path: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { File(path.removePrefix("file://")).takeIf(File::isFile)?.readBytes() }.getOrNull()
    }

    override suspend fun restore(archiveName: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val directory = DesktopPaths.dataDir().resolve("restored-media").toFile().apply { mkdirs() }
        val extension = archiveName.substringAfterLast('.', "bin").take(12)
        File(directory, "${UUID.randomUUID()}.$extension").apply { writeBytes(bytes) }.absolutePath
    }

    override suspend fun deleteRestored(path: String) = withContext(Dispatchers.IO) { File(path).delete(); Unit }
}
