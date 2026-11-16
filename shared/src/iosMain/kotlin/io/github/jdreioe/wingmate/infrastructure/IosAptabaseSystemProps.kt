package io.github.jdreioe.wingmate.infrastructure

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.Foundation.NSBundle
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.languageCode
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPad
import kotlin.experimental.ExperimentalNativeApi

/** Device context Aptabase shows beside iOS events. Call on the main thread (UIKit). */
@OptIn(ExperimentalNativeApi::class)
fun iosAptabaseSystemProps(): JsonObject {
    val device = UIDevice.currentDevice
    val info = NSBundle.mainBundle.infoDictionary
    return buildJsonObject {
        put("isDebug", Platform.isDebugBinary)
        put("locale", NSLocale.currentLocale.languageCode)
        put("osName", if (device.userInterfaceIdiom == UIUserInterfaceIdiomPad) "iPadOS" else "iOS")
        put("osVersion", device.systemVersion)
        put("appVersion", info?.get("CFBundleShortVersionString") as? String ?: "")
        put("appBuildNumber", info?.get("CFBundleVersion") as? String ?: "")
        put("sdkVersion", "wingmate-ios@1")
    }
}
