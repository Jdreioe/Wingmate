package io.github.jdreioe.wingmate.ui

import androidx.compose.runtime.Composable
import java.util.Locale

actual fun isDesktop(): Boolean = true

actual fun isReleaseBuild(): Boolean {
	val prop = System.getProperty("wingmate.release")
	val env = System.getenv("WINGMATE_RELEASE")
	return prop.equals("true", ignoreCase = true) || env.equals("true", ignoreCase = true)
}

actual fun systemLanguageTag(): String = Locale.getDefault().toLanguageTag()

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
