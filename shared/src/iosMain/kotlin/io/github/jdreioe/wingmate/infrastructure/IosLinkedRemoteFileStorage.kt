package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.obf.LinkedFileHandle
import io.github.jdreioe.wingmate.domain.obf.LinkedRemoteFileStorage
import io.github.jdreioe.wingmate.domain.obf.RemoteFileChoice
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageErrorCode
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageException
import io.github.jdreioe.wingmate.domain.obf.SyncConnection
import io.github.jdreioe.wingmate.domain.obf.SyncFolderHandle
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
 * files and sync folders selected through SwiftUI's native document picker.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosLinkedRemoteFileStorage : LinkedRemoteFileStorage {
    private val defaults = NSUserDefaults.standardUserDefaults

    override val isSupported: Boolean = true

    // SwiftUI owns the document picker sheet and registers chosen URLs via registerUrl()/registerFolderUrl().
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
            val bookmarkData = createBookmark(url)
            val resolvedName = displayName?.trim()?.takeIf(String::isNotEmpty)
                ?: url.lastPathComponent?.takeIf(String::isNotEmpty)
                ?: "screen.obz"
            if (bookmarkData != null) {
                val id = Uuid.random().toString()
                defaults.setObject(bookmarkData.base64EncodedStringWithOptions(0u), forKey = BOOKMARK_PREFIX + id)
                LinkedFileHandle(location = "$BOOKMARK_SCHEME$id", displayName = resolvedName, writable = true)
            } else {
                val path = url.path ?: fileUrlString.removePrefix("file://")
                LinkedFileHandle(location = path, displayName = resolvedName, writable = true)
            }
        } finally {
            if (started) url.stopAccessingSecurityScopedResource()
        }
    }

    /**
     * Persists a security-scoped bookmark for a user-selected folder. Wingmate automatically
     * creates and syncs `.obz` Screens inside `<folder>/Wingmate` (or directly if the folder is already named Wingmate).
     */
    fun registerFolderUrl(folderUrlString: String, displayName: String? = null): SyncFolderHandle {
        val url = parseUrl(folderUrlString)
        val started = url.startAccessingSecurityScopedResource()
        return try {
            val bookmarkData = createBookmark(url)
            val resolvedName = displayName?.trim()?.takeIf(String::isNotEmpty)
                ?: url.lastPathComponent?.takeIf(String::isNotEmpty)
                ?: SyncConnection.WINGMATE_SYNC_FOLDER_NAME
            if (bookmarkData != null) {
                val id = Uuid.random().toString()
                defaults.setObject(bookmarkData.base64EncodedStringWithOptions(0u), forKey = BOOKMARK_PREFIX + id)
                SyncFolderHandle(location = "$FOLDER_BOOKMARK_SCHEME$id", displayName = resolvedName)
            } else {
                val path = url.path ?: folderUrlString.removePrefix("file://")
                SyncFolderHandle(location = path, displayName = resolvedName)
            }
        } finally {
            if (started) url.stopAccessingSecurityScopedResource()
        }
    }

    override suspend fun listFolderScreenFiles(folderLocation: String): List<RemoteFileChoice> {
        val folderKey = folderLocation.trim()
        return withResolvedFolderUrl(folderKey) { folderUrl ->
            val wingmateDir = wingmateSubdirectory(folderUrl)
            val dirPath = wingmateDir.path ?: return@withResolvedFolderUrl emptyList()
            val fm = NSFileManager.defaultManager
            if (!fm.fileExistsAtPath(dirPath)) return@withResolvedFolderUrl emptyList()
            val items = fm.contentsOfDirectoryAtPath(dirPath, error = null) ?: return@withResolvedFolderUrl emptyList()
            items.mapNotNull { it as? String }
                .filter { it.endsWith(".obz", ignoreCase = true) && !it.startsWith(".") }
                .sorted()
                .map { name ->
                    RemoteFileChoice(
                        location = childLocation(folderKey, name),
                        name = name,
                        ownedByMe = true,
                    )
                }
        }
    }

    override suspend fun createFolderScreenFile(folderLocation: String, fileName: String): RemoteFileChoice {
        val folderKey = folderLocation.trim()
        val safeName = CloudDriveFileOperations.sanitizeFileName(fileName)
        return withResolvedFolderUrl(folderKey) { folderUrl ->
            val wingmateDir = ensureWingmateDirectory(folderUrl)
            val fileUrl = wingmateDir.URLByAppendingPathComponent(safeName)
                ?: throw RemoteStorageException(RemoteStorageErrorCode.IO_ERROR, "Could not resolve file path in Wingmate folder")
            val path = fileUrl.path
            if (path == null || !NSFileManager.defaultManager.fileExistsAtPath(path)) {
                val created = NSData().writeToURL(fileUrl, atomically = true)
                if (!created) {
                    throw RemoteStorageException(RemoteStorageErrorCode.IO_ERROR, "Could not create $safeName in Wingmate folder")
                }
            }
            RemoteFileChoice(
                location = childLocation(folderKey, safeName),
                name = safeName,
                ownedByMe = true,
            )
        }
    }

    override suspend fun readBytes(location: String): ByteArray {
        val trimmed = location.trim()
        if (trimmed.startsWith(FOLDER_FILE_SCHEME)) {
            val (folderId, fileName) = parseFolderFileLocation(trimmed)
            return withResolvedFolderUrl("$FOLDER_BOOKMARK_SCHEME$folderId") { folderUrl ->
                val fileUrl = wingmateSubdirectory(folderUrl).URLByAppendingPathComponent(fileName)
                    ?: throw RemoteStorageException(RemoteStorageErrorCode.NOT_FOUND, "Linked file was not found")
                val path = fileUrl.path
                if (path != null && !NSFileManager.defaultManager.fileExistsAtPath(path)) {
                    throw RemoteStorageException(RemoteStorageErrorCode.NOT_FOUND, "Linked file was not found")
                }
                val data = NSData.dataWithContentsOfURL(fileUrl)
                    ?: throw RemoteStorageException(RemoteStorageErrorCode.NOT_FOUND, "Linked file could not be read")
                data.toByteArray()
            }
        }
        return withResolvedUrl(trimmed) { url ->
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
        val trimmed = location.trim()
        if (trimmed.startsWith(FOLDER_FILE_SCHEME)) {
            val (folderId, fileName) = parseFolderFileLocation(trimmed)
            withResolvedFolderUrl("$FOLDER_BOOKMARK_SCHEME$folderId") { folderUrl ->
                val wingmateDir = ensureWingmateDirectory(folderUrl)
                val fileUrl = wingmateDir.URLByAppendingPathComponent(fileName)
                    ?: throw RemoteStorageException(RemoteStorageErrorCode.IO_ERROR, "Could not resolve file path in Wingmate folder")
                val ok = bytes.toNSData().writeToURL(fileUrl, atomically = true)
                if (!ok) {
                    throw RemoteStorageException(RemoteStorageErrorCode.IO_ERROR, "Could not write to Wingmate folder")
                }
            }
            return
        }
        withResolvedUrl(trimmed) { url ->
            val data = bytes.toNSData()
            val ok = data.writeToURL(url, atomically = true)
            if (!ok) {
                throw RemoteStorageException(RemoteStorageErrorCode.IO_ERROR, "Could not write to linked file")
            }
        }
    }

    override suspend fun releaseAccess(location: String) {
        val trimmed = location.trim()
        when {
            trimmed.startsWith(BOOKMARK_SCHEME) -> {
                defaults.removeObjectForKey(BOOKMARK_PREFIX + trimmed.removePrefix(BOOKMARK_SCHEME))
            }
            trimmed.startsWith(FOLDER_BOOKMARK_SCHEME) -> {
                defaults.removeObjectForKey(BOOKMARK_PREFIX + trimmed.removePrefix(FOLDER_BOOKMARK_SCHEME))
            }
            // Individual files inside a synced folder share the parent folder's bookmark; do not remove it here.
        }
    }

    private fun childLocation(folderLocation: String, fileName: String): String =
        if (folderLocation.startsWith(FOLDER_BOOKMARK_SCHEME)) {
            val folderId = folderLocation.removePrefix(FOLDER_BOOKMARK_SCHEME)
            "$FOLDER_FILE_SCHEME$folderId/$fileName"
        } else {
            val dir = wingmateSubdirectory(parseUrl(folderLocation))
            dir.URLByAppendingPathComponent(fileName)?.path ?: "$folderLocation/$fileName"
        }

    private fun parseFolderFileLocation(location: String): Pair<String, String> {
        val rest = location.removePrefix(FOLDER_FILE_SCHEME)
        val slash = rest.indexOf('/')
        if (slash <= 0 || slash >= rest.lastIndex) {
            throw RemoteStorageException(RemoteStorageErrorCode.INVALID_CONFIGURATION, "Invalid sync folder file location")
        }
        val folderId = rest.substring(0, slash)
        val fileName = rest.substring(slash + 1)
        if (fileName.contains('/') || fileName == "." || fileName == "..") {
            throw RemoteStorageException(RemoteStorageErrorCode.INVALID_CONFIGURATION, "Invalid sync folder file name")
        }
        return folderId to fileName
    }

    private fun wingmateSubdirectory(folderUrl: NSURL): NSURL {
        val folderName = folderUrl.lastPathComponent
        if (folderName != null && folderName.equals(SyncConnection.WINGMATE_SYNC_FOLDER_NAME, ignoreCase = true)) {
            return folderUrl
        }
        return folderUrl.URLByAppendingPathComponent(SyncConnection.WINGMATE_SYNC_FOLDER_NAME, isDirectory = true) ?: folderUrl
    }

    private fun ensureWingmateDirectory(folderUrl: NSURL): NSURL {
        val dir = wingmateSubdirectory(folderUrl)
        val path = dir.path
        if (path != null && !NSFileManager.defaultManager.fileExistsAtPath(path)) {
            memScoped {
                val err = alloc<ObjCObjectVar<NSError?>>()
                val ok = NSFileManager.defaultManager.createDirectoryAtURL(
                    dir,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = err.ptr,
                )
                if (!ok) {
                    throw RemoteStorageException(
                        RemoteStorageErrorCode.IO_ERROR,
                        err.value?.localizedDescription ?: "Could not create Wingmate folder",
                    )
                }
            }
        }
        return dir
    }

    private fun createBookmark(url: NSURL): NSData? = memScoped {
        val err = alloc<ObjCObjectVar<NSError?>>()
        url.bookmarkDataWithOptions(
            options = 0u,
            includingResourceValuesForKeys = null,
            relativeToURL = null,
            error = err.ptr,
        )
    }

    private fun <T> withResolvedFolderUrl(folderLocation: String, block: (NSURL) -> T): T {
        val normalized = if (folderLocation.startsWith(FOLDER_BOOKMARK_SCHEME)) {
            BOOKMARK_SCHEME + folderLocation.removePrefix(FOLDER_BOOKMARK_SCHEME)
        } else {
            folderLocation
        }
        return withResolvedUrl(normalized, block)
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
                    "Linked file bookmark is missing. Disconnect and reconnect it",
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
        const val FOLDER_BOOKMARK_SCHEME = "ios-folder-bookmark://"
        const val FOLDER_FILE_SCHEME = "ios-folder-file://"
        const val BOOKMARK_PREFIX = "wingmate_linked_file_bookmark_"
    }
}
