package io.github.jdreioe.wingmate.platform

import java.awt.Desktop
import java.io.File

class DesktopShareService : ShareService {
    override fun shareAudio(filePath: String): Boolean {
        return runCatching {
            val file = File(filePath)
            if (!file.exists()) return false
            // Try to open with the default application as a simple share equivalent
            if (Desktop.isDesktopSupported()) {
                val desktop = Desktop.getDesktop()
                if (desktop.isSupported(Desktop.Action.OPEN)) {
                    desktop.open(file)
                    return@runCatching true
                }
            }
            // Linux fallback: try xdg-open
            val proc = ProcessBuilder("xdg-open", file.absolutePath)
                .redirectErrorStream(true)
                .start()
            val exit = proc.waitFor()
            exit == 0
        }.getOrElse { false }
    }
    override fun shareText(text: String): Boolean {
        return runCatching {
            val selection = java.awt.datatransfer.StringSelection(text)
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(selection, selection)
            true
        }.getOrElse { false }
    }
}
