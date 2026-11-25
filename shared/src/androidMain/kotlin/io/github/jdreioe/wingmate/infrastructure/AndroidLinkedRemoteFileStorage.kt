package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import io.github.jdreioe.wingmate.domain.obf.LinkedFileHandle
import io.github.jdreioe.wingmate.domain.obf.LinkedRemoteFileStorage
import io.github.jdreioe.wingmate.domain.obf.RemoteFileChoice
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageErrorCode
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageException
import io.github.jdreioe.wingmate.domain.obf.SyncConnection
import io.github.jdreioe.wingmate.domain.obf.SyncFolderHandle
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android Storage Access Framework (SAF) implementation of [LinkedRemoteFileStorage].
 * Works with Google Drive, OneDrive, Nextcloud, Dropbox, Syncthing, and local device folders
 * using persistable document and tree URIs.
 */
class AndroidLinkedRemoteFileStorage(
    private val context: Context,
) : LinkedRemoteFileStorage {
    private var openLauncher: (() -> Unit)? = null
    private var createLauncher: ((String) -> Unit)? = null
    private var folderLauncher: (() -> Unit)? = null
    private var activeContinuation: CancellableContinuation<LinkedFileHandle?>? = null
    private var activeFolderContinuation: CancellableContinuation<SyncFolderHandle?>? = null

    override val isSupported: Boolean = true

    fun registerLaunchers(
        launchOpen: () -> Unit,
        launchCreate: (suggestedFileName: String) -> Unit,
        launchFolder: (() -> Unit)? = null,
    ) {
        openLauncher = launchOpen
        createLauncher = launchCreate
        folderLauncher = launchFolder
    }

    fun onDocumentSelected(uri: Uri?) {
        val cont = activeContinuation
        activeContinuation = null
        if (cont?.isActive != true) return
        if (uri == null) {
            cont.resume(null)
            return
        }
        val resolver = context.contentResolver
        var writable = true
        try {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            writable = false
            runCatching {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val displayName = resolveDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "screen.obz"
        cont.resume(LinkedFileHandle(location = uri.toString(), displayName = displayName, writable = writable))
    }

    fun onFolderSelected(uri: Uri?) {
        val cont = activeFolderContinuation
        activeFolderContinuation = null
        if (cont?.isActive != true) return
        if (uri == null) {
            cont.resume(null)
            return
        }
        val resolver = context.contentResolver
        runCatching {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure {
            runCatching {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        val docUri = docId?.let { DocumentsContract.buildDocumentUriUsingTree(uri, it) }
        val displayName = docUri?.let(::resolveDisplayName)
            ?: docId?.substringAfterLast(':')?.substringAfterLast('/')?.takeIf(String::isNotBlank)
            ?: SyncConnection.WINGMATE_SYNC_FOLDER_NAME
        cont.resume(SyncFolderHandle(location = uri.toString(), displayName = displayName))
    }

    override suspend fun pickSyncFolder(): SyncFolderHandle? = suspendCancellableCoroutine { cont ->
        val launch = folderLauncher
        if (launch == null) {
            cont.resumeWithException(
                RemoteStorageException(RemoteStorageErrorCode.UNSUPPORTED_PLATFORM, "Folder picker is unavailable"),
            )
            return@suspendCancellableCoroutine
        }
        activeFolderContinuation?.cancel()
        activeFolderContinuation = cont
        try {
            launch()
        } catch (error: Exception) {
            activeFolderContinuation = null
            cont.resumeWithException(
                RemoteStorageException(RemoteStorageErrorCode.IO_ERROR, "Could not open folder picker", error),
            )
        }
    }

    override suspend fun listFolderScreenFiles(folderLocation: String): List<RemoteFileChoice> = withContext(Dispatchers.IO) {
        val trimmed = folderLocation.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        if (trimmed.startsWith('/')) {
            val base = File(trimmed)
            val wingmateDir = if (base.name.equals(SyncConnection.WINGMATE_SYNC_FOLDER_NAME, ignoreCase = true)) {
                base
            } else {
                File(base, SyncConnection.WINGMATE_SYNC_FOLDER_NAME)
            }
            if (!wingmateDir.isDirectory) return@withContext emptyList()
            return@withContext wingmateDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".obz", ignoreCase = true) && !it.name.startsWith(".") }
                ?.sortedBy { it.name }
                ?.map { RemoteFileChoice(location = it.absolutePath, name = it.name, ownedByMe = true) }
                ?: emptyList()
        }
        val treeUri = Uri.parse(trimmed)
        val wingmateDocId = findWingmateDirectoryId(treeUri, createIfMissing = false) ?: return@withContext emptyList()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, wingmateDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val result = mutableListOf<RemoteFileChoice>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val docId = if (idIdx >= 0) cursor.getString(idIdx) else null
                val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null
                if (docId != null && name != null &&
                    mime != DocumentsContract.Document.MIME_TYPE_DIR &&
                    name.endsWith(".obz", ignoreCase = true) && !name.startsWith(".")
                ) {
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    result += RemoteFileChoice(location = fileUri.toString(), name = name, ownedByMe = true)
                }
            }
        }
        result.sortedBy { it.name }
    }

    override suspend fun createFolderScreenFile(folderLocation: String, fileName: String): RemoteFileChoice = withContext(Dispatchers.IO) {
        val trimmed = folderLocation.trim()
        val safeName = CloudDriveFileOperations.sanitizeFileName(fileName)
        if (trimmed.startsWith('/')) {
            val base = File(trimmed)
            val wingmateDir = if (base.name.equals(SyncConnection.WINGMATE_SYNC_FOLDER_NAME, ignoreCase = true)) {
                base
            } else {
                File(base, SyncConnection.WINGMATE_SYNC_FOLDER_NAME)
            }
            wingmateDir.mkdirs()
            val target = File(wingmateDir, safeName)
            if (!target.exists()) target.writeBytes(ByteArray(0))
            return@withContext RemoteFileChoice(location = target.absolutePath, name = safeName, ownedByMe = true)
        }
        val treeUri = Uri.parse(trimmed)
        val wingmateDocId = findWingmateDirectoryId(treeUri, createIfMissing = true)
            ?: throw RemoteStorageException(RemoteStorageErrorCode.IO_ERROR, "Could not create Wingmate folder")
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, wingmateDocId)
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            parentUri,
            "application/octet-stream",
            safeName,
        ) ?: throw RemoteStorageException(RemoteStorageErrorCode.IO_ERROR, "Could not create $safeName in Wingmate folder")
        RemoteFileChoice(
            location = created.toString(),
            name = resolveDisplayName(created) ?: safeName,
            ownedByMe = true,
        )
    }

    private fun findWingmateDirectoryId(treeUri: Uri, createIfMissing: Boolean): String? {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        val rootName = resolveDisplayName(rootUri)
        if (rootName != null && rootName.equals(SyncConnection.WINGMATE_SYNC_FOLDER_NAME, ignoreCase = true)) {
            return rootDocId
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val docId = if (idIdx >= 0) cursor.getString(idIdx) else null
                val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null
                if (docId != null && mime == DocumentsContract.Document.MIME_TYPE_DIR &&
                    name.equals(SyncConnection.WINGMATE_SYNC_FOLDER_NAME, ignoreCase = true)
                ) {
                    return docId
                }
            }
        }
        if (!createIfMissing) return null
        val createdDir = DocumentsContract.createDocument(
            context.contentResolver,
            rootUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            SyncConnection.WINGMATE_SYNC_FOLDER_NAME,
        ) ?: return null
        return DocumentsContract.getDocumentId(createdDir)
    }

    override suspend fun pickExistingFile(): LinkedFileHandle? = suspendCancellableCoroutine { cont ->
        val launch = openLauncher
        if (launch == null) {
            cont.resumeWithException(
                RemoteStorageException(RemoteStorageErrorCode.UNSUPPORTED_PLATFORM, "Document picker is unavailable"),
            )
            return@suspendCancellableCoroutine
        }
        activeContinuation?.cancel()
        activeContinuation = cont
        try {
            launch()
        } catch (error: Exception) {
            activeContinuation = null
            cont.resumeWithException(
                RemoteStorageException(RemoteStorageErrorCode.IO_ERROR, "Could not open document picker", error),
            )
        }
    }

    override suspend fun createLinkedFile(suggestedFileName: String): LinkedFileHandle? = suspendCancellableCoroutine { cont ->
        val launch = createLauncher
        if (launch == null) {
            cont.resumeWithException(
                RemoteStorageException(RemoteStorageErrorCode.UNSUPPORTED_PLATFORM, "Document creator is unavailable"),
            )
            return@suspendCancellableCoroutine
        }
        activeContinuation?.cancel()
        activeContinuation = cont
        val safeName = suggestedFileName.trim().ifBlank { "screen" }.let {
            if (it.lowercase().endsWith(".obz")) it else "$it.obz"
        }
        try {
            launch(safeName)
        } catch (error: Exception) {
            activeContinuation = null
            cont.resumeWithException(
                RemoteStorageException(RemoteStorageErrorCode.IO_ERROR, "Could not create linked file", error),
            )
        }
    }

    override suspend fun readBytes(location: String): ByteArray = withContext(Dispatchers.IO) {
        val trimmed = location.trim()
        if (trimmed.isEmpty()) {
            throw RemoteStorageException(RemoteStorageErrorCode.INVALID_CONFIGURATION, "Linked file path is empty")
        }
        try {
            if (trimmed.startsWith('/')) {
                val file = File(trimmed)
                if (!file.exists()) {
                    throw RemoteStorageException(RemoteStorageErrorCode.NOT_FOUND, "Linked file was not found")
                }
                file.readBytes()
            } else {
                val uri = Uri.parse(trimmed)
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw RemoteStorageException(RemoteStorageErrorCode.NOT_FOUND, "Linked file could not be opened")
            }
        } catch (error: RemoteStorageException) {
            throw error
        } catch (error: FileNotFoundException) {
            throw RemoteStorageException(RemoteStorageErrorCode.NOT_FOUND, "Linked file was not found", error)
        } catch (error: SecurityException) {
            throw RemoteStorageException(
                RemoteStorageErrorCode.UNAUTHORIZED,
                "Permission to access the linked file was revoked. Disconnect and reconnect it",
                error,
            )
        } catch (error: Exception) {
            throw RemoteStorageException(
                RemoteStorageErrorCode.IO_ERROR,
                error.message ?: "Could not read linked file",
                error,
            )
        }
    }

    override suspend fun writeBytes(location: String, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        val trimmed = location.trim()
        if (trimmed.isEmpty()) {
            throw RemoteStorageException(RemoteStorageErrorCode.INVALID_CONFIGURATION, "Linked file path is empty")
        }
        try {
            if (trimmed.startsWith('/')) {
                val target = File(trimmed)
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile ?: context.cacheDir, ".${target.name}.wingmate.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) {
                    target.writeBytes(bytes)
                    tmp.delete()
                }
            } else {
                val uri = Uri.parse(trimmed)
                val out = runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
                    ?: context.contentResolver.openOutputStream(uri, "w")
                    ?: throw RemoteStorageException(RemoteStorageErrorCode.IO_ERROR, "Linked file is not writable")
                out.use { it.write(bytes); it.flush() }
            }
        } catch (error: RemoteStorageException) {
            throw error
        } catch (error: SecurityException) {
            throw RemoteStorageException(
                RemoteStorageErrorCode.UNAUTHORIZED,
                "Write permission to the linked file is missing",
                error,
            )
        } catch (error: Exception) {
            throw RemoteStorageException(
                RemoteStorageErrorCode.IO_ERROR,
                error.message ?: "Could not save linked file",
                error,
            )
        }
    }

    override suspend fun releaseAccess(location: String) {
        if (location.startsWith("content://")) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(location),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx)?.takeIf(String::isNotBlank) else null
            } else null
        }
    }.getOrNull()
}
