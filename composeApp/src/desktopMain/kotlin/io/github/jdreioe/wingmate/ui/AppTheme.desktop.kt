package io.github.jdreioe.wingmate.ui

import androidx.compose.runtime.Composable

@Composable
actual fun AppTheme(content: @Composable () -> Unit) {
    DesktopTheme { content() }
}
