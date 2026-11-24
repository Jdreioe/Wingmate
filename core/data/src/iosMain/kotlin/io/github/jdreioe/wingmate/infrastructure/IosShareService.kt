package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.platform.ShareService
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGRectMake
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.popoverPresentationController
import platform.Foundation.NSURL
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSData
import platform.Foundation.NSThread
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_sync

@OptIn(ExperimentalForeignApi::class)
class IosShareService : ShareService {
    override fun shareAudio(filePath: String): Boolean =
        presentShareSheet(NSURL.fileURLWithPath(filePath))

    override fun shareText(text: String): Boolean = presentShareSheet(text)

    override fun shareFile(fileName: String, content: ByteArray): Boolean {
        val tmpDir = NSTemporaryDirectory()
        val filePath = "$tmpDir/$fileName"
        val data = content.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = content.size.toULong())
        }
        if (!data.writeToFile(filePath, atomically = true)) return false
        return presentShareSheet(NSURL.fileURLWithPath(filePath))
    }

    /**
     * Presents the system share sheet on the main thread. Callers are often
     * suspend functions that resume on a background dispatcher, and UIKit
     * crashes when a share sheet is presented off the main queue.
     */
    private fun presentShareSheet(item: Any): Boolean {
        if (NSThread.isMainThread) return presentOnMain(item)
        var presented = false
        dispatch_sync(dispatch_get_main_queue()) { presented = presentOnMain(item) }
        return presented
    }

    private fun presentOnMain(item: Any): Boolean {
        var top = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return false
        while (true) {
            top = top.presentedViewController ?: break
        }
        val controller = UIActivityViewController(activityItems = listOf(item), applicationActivities = null)
        // iPad shows the sheet as a popover, which must be anchored or UIKit throws.
        controller.popoverPresentationController?.let { popover ->
            popover.sourceView = top.view
            popover.sourceRect = top.view.bounds.useContents {
                CGRectMake(size.width / 2, size.height / 2, 0.0, 0.0)
            }
            popover.permittedArrowDirections = 0u
        }
        top.presentViewController(controller, animated = true, completion = null)
        return true
    }
}
