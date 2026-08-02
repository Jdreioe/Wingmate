package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.application.BackupMediaAccess
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosBackupMediaAccess : BackupMediaAccess {
    private val root: String by lazy {
        val urls = NSFileManager.defaultManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask)
        (urls.firstOrNull() as? NSURL)?.path.orEmpty()
    }

    override suspend fun read(path: String): ByteArray? = NSData.dataWithContentsOfFile(path.removePrefix("file://"))?.toByteArray()

    override suspend fun restore(archiveName: String, bytes: ByteArray): String {
        val directory = "$root/restored-media"
        NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
        val extension = archiveName.substringAfterLast('.', "bin").take(12)
        val path = "$directory/${platform.Foundation.NSUUID.UUID().UUIDString}.$extension"
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        check(data.writeToFile(path, atomically = true))
        return path
    }

    override suspend fun deleteRestored(path: String) {
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }

    private fun NSData.toByteArray(): ByteArray = ByteArray(length.toInt()).also { result ->
        if (result.isNotEmpty()) result.usePinned { memcpy(it.addressOf(0), bytes, length) }
    }
}
