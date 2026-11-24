package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import io.github.jdreioe.wingmate.domain.obf.LinkedFileHandle
import io.github.jdreioe.wingmate.domain.obf.LinkedRemoteFileStorage
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageErrorCode
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageException
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
 * using persistable document URIs — with zero OAuth credentials or Wingmate backend.
 */
class AndroidLinkedRemoteFileStorage(
    private val context: Context,
) : LinkedRemoteFileStorage {
    private var openLauncher: (() -> Unit)? = null
    private var createLauncher: ((String) -> Unit)? = null
    private var activeContinuation: CancellableContinuation<LinkedFileHandle?>? = null

    override val isSupported: Boolean = true

    fun registerLaunchers(
        launchOpen: () -> Unit,
        launchCreate: (suggestedFileName: String) -> Unit,
    ) {
        openLauncher = launchOpen
        createLauncher = launchCreate
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
