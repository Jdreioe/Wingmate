package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.FileStorage
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
class IosFileStorage : FileStorage {
    private val root: String by lazy {
        val urls = NSFileManager.defaultManager.URLsForDirectory(
            directory = NSDocumentDirectory,
            inDomains = NSUserDomainMask
        )
        (urls.firstOrNull() as? platform.Foundation.NSURL)?.path ?: ""
    }

    override suspend fun save(fileName: String, content: String) {
        val path = resolve(fileName)
        ensureParent(path)
        val data = (content as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        data.writeToFile(path, atomically = true)
    }

    override suspend fun load(fileName: String): String? {
        val path = resolve(fileName)
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        return NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
    }

    override suspend fun saveBytes(fileName: String, content: ByteArray) {
        val path = resolve(fileName)
        ensureParent(path)
        val data = content.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = content.size.toULong())
        }
        data.writeToFile(path, atomically = true)
    }

    override suspend fun loadBytes(fileName: String): ByteArray? {
        val path = resolve(fileName)
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        val length = data.length.toInt()
        if (length == 0) return ByteArray(0)
        val bytes = ByteArray(length)
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), data.bytes, data.length)
        }
        return bytes
    }

    override suspend fun exists(fileName: String): Boolean {
        return NSFileManager.defaultManager.fileExistsAtPath(resolve(fileName))
    }

    private fun resolve(fileName: String): String {
        val trimmed = fileName.trimStart('/')
        return if (root.isBlank()) trimmed else "$root/$trimmed"
    }

    private fun ensureParent(path: String) {
        val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
        if (parent.isNotBlank()) {
            NSFileManager.defaultManager.createDirectoryAtPath(
                path = parent,
                withIntermediateDirectories = true,
                attributes = null,
                error = null
            )
        }
    }
}
