package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.platform.AudioClipboard
import platform.UIKit.UIPasteboard
import platform.Foundation.NSURL
import platform.MobileCoreServices.UTTypeAudio
import platform.UniformTypeIdentifiers.UTTypeAudio

class IosAudioClipboard : AudioClipboard {
    override fun copyAudioFile(filePath: String): Boolean {
        val url = NSURL.fileURLWithPath(filePath)
        val pasteboard = UIPasteboard.generalPasteboard
        
        // Clear existing
        pasteboard.items = emptyList<Map<Any?, Any?>>()
        
        // Set URL
        pasteboard.URL = url
        return true
    }
}
