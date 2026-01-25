package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.platform.ShareService
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.Foundation.NSURL

class IosShareService : ShareService {
    override fun shareAudio(filePath: String): Boolean {
        val url = NSURL.fileURLWithPath(filePath)
        val controller = UIActivityViewController(
            activityItems = listOf(url),
            applicationActivities = null
        )
        
        val rootController = UIApplication.sharedApplication.keyWindow?.rootViewController
        
        // For iPad: configure popover if needed, though mostly handled by UIKit for basic cases
        // but robust implementation checks device idiom. For simplicity in KMP, we try generic presentation.
        if (rootController != null) {
            // Find top controller to present from
            var top = rootController
            while (top.presentedViewController != null) {
                top = top.presentedViewController!!
            }
            
            // On iPad, popover presentation controller configuration is required usually, 
            // but effectively we might need the view origin. 
            // Since we don't have view context here, we can try relying on UIKit's center default 
            // or hope it's an iPhone.
            // A specific improvement would be to pass a view anchor, but ShareService interface is generic.
            
            // Workaround for iPad crash "must provide location":
            controller.popoverPresentationController?.sourceView = top.view
            controller.popoverPresentationController?.sourceRect = top.view.bounds
            
            top.presentViewController(controller, animated = true, completion = null)
            return true
        }
        return false
    }
}
