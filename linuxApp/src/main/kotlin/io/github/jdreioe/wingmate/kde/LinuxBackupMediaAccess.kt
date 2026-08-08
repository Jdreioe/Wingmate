package io.github.jdreioe.wingmate.kde

import io.github.jdreioe.wingmate.application.BackupMediaAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Reads/writes backup media (recordings, symbol images) on disk for the Linux client. */
class LinuxBackupMediaAccess : BackupMediaAccess {
    private val directory: File by lazy {
        File(System.getProperty("user.home"), ".config/wingmate/media").apply { mkdirs() }
    }

    override suspend fun read(path: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { File(path.removePrefix("file://")).takeIf(File::isFile)?.readBytes() }.getOrNull()
    }

    override suspend fun restore(archiveName: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val extension = archiveName.substringAfterLast('.', "bin").take(12)
        File(directory, "${UUID.randomUUID()}.$extension").apply { writeBytes(bytes) }.absolutePath
    }

    override suspend fun deleteRestored(path: String) = withContext(Dispatchers.IO) { File(path).delete(); Unit }
}
