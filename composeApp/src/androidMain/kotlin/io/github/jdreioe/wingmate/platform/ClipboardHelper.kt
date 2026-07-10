package io.github.jdreioe.wingmate.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

actual suspend fun setClipboardText(text: String) = withContext(Dispatchers.Main) {
    val context = GlobalContext.getOrNull()?.getOrNull<Context>() ?: return@withContext
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Wingmate", text))
}
