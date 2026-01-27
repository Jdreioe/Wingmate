package io.github.jdreioe.wingmate.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

class DesktopFilePicker : FilePicker {
    override suspend fun pickFile(title: String, extensions: List<String>): String? = withContext(Dispatchers.IO) {
        val chooser = JFileChooser()
        chooser.dialogTitle = title
        chooser.isMultiSelectionEnabled = false
        
        if (extensions.isNotEmpty()) {
            val filter = FileNameExtensionFilter(
                "Board files (${extensions.joinToString(", ") { "*.$it" }})",
                *extensions.toTypedArray()
            )
            chooser.fileFilter = filter
        }
        
        val result = chooser.showOpenDialog(null)
        
        if (result == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile?.absolutePath
        } else {
            null
        }
    }

    override suspend fun readFileAsText(path: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            File(path).readText()
        }.getOrNull()
    }
}
