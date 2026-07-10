package io.github.jdreioe.wingmate.platform

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual suspend fun setClipboardText(text: String) = withContext(Dispatchers.Main) {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    clipboard.setContents(StringSelection(text), null)
}
