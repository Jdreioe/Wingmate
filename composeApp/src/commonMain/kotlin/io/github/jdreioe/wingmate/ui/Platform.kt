package io.github.jdreioe.wingmate.ui

import androidx.compose.runtime.Composable

expect fun isDesktop(): Boolean
expect fun isReleaseBuild(): Boolean
expect fun systemLanguageTag(): String

@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

class MicrophonePermissionState(
    val isGranted: Boolean,
    val shouldShowRationale: Boolean,
    val deniedPermanently: Boolean,
    val request: () -> Unit,
    val openSettings: () -> Unit
)

@Composable
expect fun rememberMicrophonePermissionState(): MicrophonePermissionState
