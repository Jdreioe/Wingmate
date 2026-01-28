package io.github.jdreioe.wingmate.platform

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
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
            // Persist permission if needed (OpenDocument usually gives temporary access, but for reading immediately it's fine)
            // If we need long term access, we should takePersistableUriPermission
            uri?.let {
                try {
                    val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(it, flags)
                } catch (e: Exception) {
                    // StartUp provider or some contexts might fail this, ignore
                }
            }
            cont.resume(uri?.toString())
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
            val uri = Uri.parse(path)
            context.contentResolver.openInputStream(uri)?.use { 
                it.bufferedReader().readText() 
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
