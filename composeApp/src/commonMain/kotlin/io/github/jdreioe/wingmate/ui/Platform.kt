package io.github.jdreioe.wingmate.ui

import androidx.compose.runtime.Composable

expect fun isDesktop(): Boolean
expect fun isReleaseBuild(): Boolean
expect fun systemLanguageTag(): String

@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
