package io.github.jdreioe.wingmate.platform

import android.content.Context
import android.net.Uri

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Android implementation of FilePicker using ActivityResultLauncher.
 * Note: usage requires hooking into Activity lifecycle, which is complex in pure class.
 * Ideally this should be bound to Activity or Context.
 * For simplicity in KMP, we might need a workaround or assume Activity context is available via a provider.
 */
class AndroidFilePicker(private val context: Context) : FilePicker {

    private var launcher: ((List<String>) -> Unit)? = null
    private var activeContinuation: kotlinx.coroutines.CancellableContinuation<String?>? = null

    // Called by MainActivity to register the actual launcher
    fun registerLauncher(launchFunction: (List<String>) -> Unit) {
        this.launcher = launchFunction
    }

    // Called by MainActivity when result is received
    fun onFilePicked(uri: Uri?) {
        val cont = activeContinuation
        activeContinuation = null
        if (cont?.isActive == true) {
            if (uri == null) {
                cont.resume(null)
                return
            }

            // Take persistable permission for the URI
            try {
                val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (e: Exception) {
                // Ignore if not supported
            }

            // For ZIP/OBZ files, ZipFile requires a local file path.
            // We copy the content to a temporary file in the cache directory.
            val path = if (uri.scheme == "content") {
                copyToTempFile(uri)
            } else {
                uri.toString()
            }
            
            cont.resume(path)
        }
    }

    private fun copyToTempFile(uri: Uri): String? {
        return try {
            val contentResolver = context.contentResolver
            
            // Try to get the original filename if possible
            val cursor = contentResolver.query(uri, null, null, null, null)
            val displayName = cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) it.getString(index) else null
                } else null
            }
            
            val suffix = if (displayName?.contains(".") == true) {
                "." + displayName.substringAfterLast(".")
            } else {
                ".tmp"
            }
            
            val prefix = if (displayName != null) {
                displayName.substringBeforeLast(".")
            } else {
                "picked_file_"
            }

            // Ensure prefix is at least 3 chars long for createTempFile
            val safePrefix = if (prefix.length < 3) (prefix + "file").take(3) else prefix

            val tempFile = java.io.File.createTempFile(safePrefix, suffix, context.cacheDir)
            tempFile.deleteOnExit() // Standard attempt to cleanup

            contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            tempFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            uri.toString() // Fallback to URI string if copy fails
        }
    }

    override suspend fun pickFile(title: String, extensions: List<String>): String? = suspendCancellableCoroutine { cont ->
        val safeLauncher = launcher
        if (safeLauncher == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        
        // Cancel previous if exists
        activeContinuation?.cancel()
        activeContinuation = cont
        
        try {
            safeLauncher(extensions)
        } catch (e: Exception) {
            activeContinuation = null
            cont.resume(null)
        }
    }

    override suspend fun readFileAsText(path: String): String? {
        return try {
            if (path.startsWith("/")) {
                java.io.File(path).readText()
            } else {
                val uri = Uri.parse(path)
                context.contentResolver.openInputStream(uri)?.use { 
                    it.bufferedReader().readText() 
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override suspend fun readZipEntries(path: String): Map<String, ByteArray>? {
        return try {
            val file = if (path.startsWith("/")) {
                java.io.File(path)
            } else {
                val uri = Uri.parse(path)
                val tempPath = copyToTempFile(uri) ?: return null
                java.io.File(tempPath)
            }
            
            val entries = mutableMapOf<String, ByteArray>()
            java.util.zip.ZipFile(file).use { zip ->
                val enumeration = zip.entries()
                while (enumeration.hasMoreElements()) {
                    val entry = enumeration.nextElement()
                    if (!entry.isDirectory) {
                        try {
                            zip.getInputStream(entry).use { input ->
                                entries[entry.name] = input.readBytes()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
            entries
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
