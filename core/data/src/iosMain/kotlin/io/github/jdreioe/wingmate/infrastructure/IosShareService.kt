package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.platform.ShareService
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.Foundation.NSURL
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSData
import platform.Foundation.writeToFile

class IosShareService : ShareService {
    override fun shareAudio(filePath: String): Boolean {
        val url = NSURL.fileURLWithPath(filePath)
        val controller = UIActivityViewController(
            activityItems = listOf(url),
            applicationActivities = null
        )

        val rootController = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return false
        var top = rootController
        while (true) {
            val presented = top.presentedViewController ?: break
            top = presented
        }

        top.presentViewController(controller, animated = true, completion = null)
        return true
    }

    override fun shareText(text: String): Boolean {
        val controller = UIActivityViewController(
            activityItems = listOf(text),
            applicationActivities = null
        )
        
        val rootController = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return false
        var top = rootController
        while (true) {
            val presented = top.presentedViewController ?: break
            top = presented
        }

        top.presentViewController(controller, animated = true, completion = null)
        return true
    }

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
        val controller = UIActivityViewController(
            activityItems = listOf(url),
            applicationActivities = null
        )
        val rootController = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return false
        var top = rootController
        while (true) {
            val presented = top.presentedViewController ?: break
            top = presented
        }
        top.presentViewController(controller, animated = true, completion = null)
        return true
    }
}
