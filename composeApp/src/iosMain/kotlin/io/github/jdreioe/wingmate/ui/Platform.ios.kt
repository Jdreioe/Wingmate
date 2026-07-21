package io.github.jdreioe.wingmate.ui

import androidx.compose.runtime.Composable
import kotlin.native.Platform
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier

actual fun isDesktop(): Boolean = false

actual fun isReleaseBuild(): Boolean = !Platform.isDebugBinary

actual fun systemLanguageTag(): String = NSLocale.currentLocale.localeIdentifier

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
}

@Composable
actual fun rememberMicrophonePermissionState(): MicrophonePermissionState {
    return MicrophonePermissionState(
        isGranted = true,
        shouldShowRationale = false,
        deniedPermanently = false,
        hasRequested = false,
        request = {},
        openSettings = {}
    )
}
