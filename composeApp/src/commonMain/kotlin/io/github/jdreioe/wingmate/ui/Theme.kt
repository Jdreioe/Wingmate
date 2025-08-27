package io.github.jdreioe.wingmate.ui

import androidx.compose.runtime.Composable

/**
 * Platform-specific theme implementation
 * Each platform will provide its own implementation with dynamic theming support
 */
@Composable
expect fun AppTheme(content: @Composable () -> Unit)
