package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.FileStorage
import io.github.jdreioe.wingmate.domain.FileStorageWriteException
import kotlinx.cinterop.BetaInteropApi
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
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosFileStorage internal constructor(private val root: String) : FileStorage {
    constructor() : this(documentsDirectory())

    override suspend fun save(fileName: String, content: String) {
        saveBytes(fileName, content.encodeToByteArray())
    }

    override suspend fun load(fileName: String): String? {
        val path = resolve(fileName)
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        return NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
    }

    override suspend fun saveBytes(fileName: String, content: ByteArray) {
        val path = resolve(fileName)
        ensureParent(path)
        val data = if (content.isEmpty()) {
            NSData.create(bytes = null, length = 0u)
        } else {
            content.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = content.size.toULong())
            }
        }
        if (!data.writeToFile(path, atomically = true)) {
            throw FileStorageWriteException()
        }
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

    override suspend fun delete(fileName: String) {
        val path = resolve(fileName)
        if (NSFileManager.defaultManager.fileExistsAtPath(path)) {
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        }
    }

    private fun resolve(fileName: String): String {
        val trimmed = fileName.trimStart('/')
        return if (root.isBlank()) trimmed else "$root/$trimmed"
    }

    private fun ensureParent(path: String) {
        val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
        if (parent.isBlank()) return
        val created = NSFileManager.defaultManager.createDirectoryAtPath(
            path = parent,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
        if (!created) {
            throw FileStorageWriteException()
        }
    }

    companion object {
        fun documentsDirectory(): String {
            val urls = NSFileManager.defaultManager.URLsForDirectory(
                directory = NSDocumentDirectory,
                inDomains = NSUserDomainMask
            )
            return (urls.firstOrNull() as? platform.Foundation.NSURL)?.path ?: ""
        }
    }
}
