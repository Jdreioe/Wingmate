package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.platform.AudioClipboard
import platform.AppKit.NSPasteboard
import platform.Foundation.NSURL

class MacosAudioClipboard : AudioClipboard {
    override fun copyAudioFile(filePath: String): Boolean {
        val url = NSURL.fileURLWithPath(filePath)
        val pasteboard = NSPasteboard.generalPasteboard
        pasteboard.clearContents()
        pasteboard.writeObjects(listOf(url))
        return true
    }
}