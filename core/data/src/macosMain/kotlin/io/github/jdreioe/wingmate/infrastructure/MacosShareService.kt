package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.platform.ShareService
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AppKit.NSApplication
import platform.AppKit.NSSharingServicePicker
import platform.AppKit.NSWindow
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.writeToFile

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class MacosShareService : ShareService {
    private fun showPicker(items: List<Any>): Boolean {
        val window = NSApplication.sharedApplication.keyWindow
            ?: NSApplication.sharedApplication.windows.firstOrNull { (it as? NSWindow)?.isVisible() == true } as? NSWindow
            ?: return false
        val contentView = window.contentView ?: return false
        val picker = NSSharingServicePicker(items = items)
        picker.showRelativeToRect(contentView.bounds, ofView = contentView, preferredEdge = 0u)
        return true
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun shareAudio(filePath: String): Boolean {
        val url = NSURL.fileURLWithPath(filePath)
        return showPicker(listOf(url))
    }

    override fun shareText(text: String): Boolean {
        return showPicker(listOf(text))
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun shareFile(fileName: String, content: ByteArray): Boolean {
        val tmpDir = NSTemporaryDirectory() ?: return false
        val filePath = "$tmpDir/$fileName"
        val data = content.usePinned { pinned ->
            pinned.addressOf(0).let { ptr ->
                NSData.create(bytes = ptr, length = content.size.toULong())
            }
        }
        data.writeToFile(filePath, atomically = true)
        val url = NSURL.fileURLWithPath(filePath)
        return showPicker(listOf(url))
    }
}