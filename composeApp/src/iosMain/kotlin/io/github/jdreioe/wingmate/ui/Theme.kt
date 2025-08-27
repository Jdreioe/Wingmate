package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
actual fun AppTheme(content: @Composable () -> Unit) {
    val useDarkTheme = isSystemInDarkTheme()
    
    val colorScheme = if (useDarkTheme) {
        darkColorScheme(
            primary = Color(0xFF007AFF), // iOS Blue
            onPrimary = Color.White,
            secondary = Color(0xFF5856D6), // iOS Purple
            background = Color(0xFF000000), // iOS Dark Background
            surface = Color(0xFF1C1C1E), // iOS Dark Surface
            onBackground = Color(0xFFFFFFFF),
            onSurface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFF2C2C2E)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF007AFF), // iOS Blue
            onPrimary = Color.White,
            secondary = Color(0xFF5856D6), // iOS Purple
            background = Color(0xFFF2F2F7), // iOS Light Background
            surface = Color(0xFFFFFFFF), // iOS Light Surface
            onBackground = Color(0xFF000000),
            onSurface = Color(0xFF000000),
            surfaceVariant = Color(0xFFF2F2F7)
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
