package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.platform.ShareService
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.Foundation.NSURL

class IosShareService : ShareService {
    private fun topViewController(): UIViewController? {
        val root = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return null
        var current: UIViewController = root
        while (true) {
            val presented = current.presentedViewController ?: break
            current = presented
        }
        return current
    }

    override fun shareAudio(filePath: String): Boolean {
        val url = NSURL.fileURLWithPath(filePath)
        val controller = UIActivityViewController(
            activityItems = listOf(url),
            applicationActivities = null
        )

        val top = topViewController()
        if (top != null) {
            top.presentViewController(controller, animated = true, completion = null)
            return true
        }
        return false
    }

    override fun shareText(text: String): Boolean {
        val controller = UIActivityViewController(
            activityItems = listOf(text),
            applicationActivities = null
        )

        val top = topViewController()
        if (top != null) {
            top.presentViewController(controller, animated = true, completion = null)
            return true
        }
        return false
    }
}
