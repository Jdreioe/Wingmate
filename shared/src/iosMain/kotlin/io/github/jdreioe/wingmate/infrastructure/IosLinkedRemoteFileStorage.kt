package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.obf.LinkedFileHandle
import io.github.jdreioe.wingmate.domain.obf.LinkedRemoteFileStorage
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageErrorCode
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageException
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToURL
import platform.posix.memcpy
import kotlin.uuid.Uuid

/**
 * iOS security-scoped bookmark implementation of [LinkedRemoteFileStorage].
 * Works with iCloud Drive, Google Drive, OneDrive, Dropbox, Nextcloud, and On My iPad/iPhone
 * files selected through SwiftUI's native file importer/exporter.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosLinkedRemoteFileStorage : LinkedRemoteFileStorage {
    private val defaults = NSUserDefaults.standardUserDefaults

    override val isSupported: Boolean = true

    // SwiftUI owns the document picker sheet and registers the chosen URL via registerUrl().
    override suspend fun pickExistingFile(): LinkedFileHandle? = null
    override suspend fun createLinkedFile(suggestedFileName: String): LinkedFileHandle? = null

    /**
     * Persists a security-scoped bookmark for a file URL returned by SwiftUI `.fileImporter`
     * or `.fileExporter`, returning a handle whose [LinkedFileHandle.location] survives restarts.
     */
    fun registerUrl(fileUrlString: String, displayName: String? = null): LinkedFileHandle {
        val url = parseUrl(fileUrlString)
        val started = url.startAccessingSecurityScopedResource()
        return try {
            val bookmarkData = memScoped {
                val err = alloc<ObjCObjectVar<NSError?>>()
                url.bookmarkDataWithOptions(
                    options = 0u,
                    includingResourceValuesForKeys = null,
                    relativeToURL = null,
                    error = err.ptr,
                )
            }
            val resolvedName = displayName?.trim()?.takeIf(String::isNotEmpty)
                ?: url.lastPathComponent?.takeIf(String::isNotEmpty)
                ?: "screen.obz"
            if (bookmarkData != null) {
                val id = Uuid.random().toString()
                val encoded = bookmarkData.base64EncodedStringWithOptions(0u)
                defaults.setObject(encoded, forKey = BOOKMARK_PREFIX + id)
                LinkedFileHandle(
                    location = "$BOOKMARK_SCHEME$id",
                    displayName = resolvedName,
                    writable = true,
                )
            } else {
                val path = url.path ?: fileUrlString.removePrefix("file://")
                LinkedFileHandle(location = path, displayName = resolvedName, writable = true)
            }
        } finally {
            if (started) url.stopAccessingSecurityScopedResource()
        }
    }

    override suspend fun readBytes(location: String): ByteArray {
        return withResolvedUrl(location) { url ->
            val path = url.path
            if (path != null && !NSFileManager.defaultManager.fileExistsAtPath(path)) {
                throw RemoteStorageException(RemoteStorageErrorCode.NOT_FOUND, "Linked file was not found")
            }
            val data = NSData.dataWithContentsOfURL(url)
                ?: throw RemoteStorageException(RemoteStorageErrorCode.NOT_FOUND, "Linked file could not be read")
            data.toByteArray()
        }
    }

    override suspend fun writeBytes(location: String, bytes: ByteArray) {
        withResolvedUrl(location) { url ->
            val data = bytes.toNSData()
            val ok = data.writeToURL(url, atomically = true)
            if (!ok) {
                throw RemoteStorageException(RemoteStorageErrorCode.IO_ERROR, "Could not write to linked file")
            }
        }
    }

    override suspend fun releaseAccess(location: String) {
        if (location.startsWith(BOOKMARK_SCHEME)) {
            val id = location.removePrefix(BOOKMARK_SCHEME)
            defaults.removeObjectForKey(BOOKMARK_PREFIX + id)
        }
    }

    private fun <T> withResolvedUrl(location: String, block: (NSURL) -> T): T {
        val trimmed = location.trim()
        if (trimmed.isEmpty()) {
            throw RemoteStorageException(RemoteStorageErrorCode.INVALID_CONFIGURATION, "Linked file path is empty")
        }
        val url = if (trimmed.startsWith(BOOKMARK_SCHEME)) {
            val id = trimmed.removePrefix(BOOKMARK_SCHEME)
            val encoded = defaults.stringForKey(BOOKMARK_PREFIX + id)
                ?: throw RemoteStorageException(
                    RemoteStorageErrorCode.UNAUTHORIZED,
                    "Linked file bookmark is missing. Disconnect and reconnect the file",
                )
            val bookmarkData = NSData.create(base64EncodedString = encoded, options = 0u)
                ?: throw RemoteStorageException(RemoteStorageErrorCode.IO_ERROR, "Linked file bookmark is corrupted")
            memScoped {
                val stale = alloc<BooleanVar>()
                val err = alloc<ObjCObjectVar<NSError?>>()
                val resolved = NSURL.URLByResolvingBookmarkData(
                    bookmarkData = bookmarkData,
                    options = 0u,
                    relativeToURL = null,
                    bookmarkDataIsStale = stale.ptr,
                    error = err.ptr,
                ) ?: throw RemoteStorageException(
                    RemoteStorageErrorCode.NOT_FOUND,
                    err.value?.localizedDescription ?: "Linked file could not be resolved",
                )
                if (stale.value) {
                    val refreshed = resolved.bookmarkDataWithOptions(0u, null, null, null)
                    if (refreshed != null) {
                        defaults.setObject(refreshed.base64EncodedStringWithOptions(0u), forKey = BOOKMARK_PREFIX + id)
                    }
                }
                resolved
            }
        } else {
            parseUrl(trimmed)
        }
        val started = url.startAccessingSecurityScopedResource()
        return try {
            block(url)
        } finally {
            if (started) url.stopAccessingSecurityScopedResource()
        }
    }

    private fun parseUrl(raw: String): NSURL {
        return if (raw.startsWith("file://")) {
            NSURL.URLWithString(raw) ?: NSURL.fileURLWithPath(raw.removePrefix("file://"))
        } else {
            NSURL.fileURLWithPath(raw)
        }
    }

    private fun NSData.toByteArray(): ByteArray = ByteArray(length.toInt()).also { out ->
        if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    }

    private fun ByteArray.toNSData(): NSData = if (isEmpty()) {
        NSData()
    } else {
        usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }
    }

    private companion object {
        const val BOOKMARK_SCHEME = "ios-bookmark://"
        const val BOOKMARK_PREFIX = "wingmate_linked_file_bookmark_"
    }
}
