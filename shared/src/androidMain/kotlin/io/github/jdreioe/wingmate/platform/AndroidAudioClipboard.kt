package io.github.jdreioe.wingmate.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

class AndroidAudioClipboard(private val context: Context) : AudioClipboard {
    override fun copyAudioFile(filePath: String): Boolean {
        return runCatching {
            val file = File(filePath)
            if (!file.exists()) return false
            // Ensure we serve a content:// Uri via FileProvider
            val authority = context.packageName + ".fileprovider"
            val uri: Uri = FileProvider.getUriForFile(context, authority, file)
            val clip = ClipData.newUri(context.contentResolver, file.name, uri)
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(clip)
            true
        }.getOrElse { false }
    }
}