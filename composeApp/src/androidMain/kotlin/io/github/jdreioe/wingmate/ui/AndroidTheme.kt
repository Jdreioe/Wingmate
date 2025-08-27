package io.github.jdreioe.wingmate.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val useDarkTheme = isSystemInDarkTheme()
    
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            // Android 12+ (API 31+) supports dynamic colors
            if (useDarkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        useDarkTheme -> darkColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF6950A1),
            onPrimary = androidx.compose.ui.graphics.Color.White,
            secondary = androidx.compose.ui.graphics.Color(0xFF7C4DFF),
            background = androidx.compose.ui.graphics.Color(0xFF121217),
            surface = androidx.compose.ui.graphics.Color(0xFF1A1A1F),
            onBackground = androidx.compose.ui.graphics.Color(0xFFECECF0),
            onSurface = androidx.compose.ui.graphics.Color(0xFFECECF0)
        )
        else -> lightColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF7C4DFF),
            onPrimary = androidx.compose.ui.graphics.Color.White,
            secondary = androidx.compose.ui.graphics.Color(0xFF6950A1),
            background = androidx.compose.ui.graphics.Color(0xFFF2EFF6),
            surface = androidx.compose.ui.graphics.Color(0xFFECE9F4),
            onBackground = androidx.compose.ui.graphics.Color(0xFF111111),
            onSurface = androidx.compose.ui.graphics.Color(0xFF111111)
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

@Composable
fun AndroidTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    useDynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    
    val colorScheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (useDarkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        useDarkTheme -> darkColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF6950A1),
            onPrimary = androidx.compose.ui.graphics.Color.White,
            secondary = androidx.compose.ui.graphics.Color(0xFF7C4DFF),
            background = androidx.compose.ui.graphics.Color(0xFF121217),
            surface = androidx.compose.ui.graphics.Color(0xFF1A1A1F),
            onBackground = androidx.compose.ui.graphics.Color(0xFFECECF0),
            onSurface = androidx.compose.ui.graphics.Color(0xFFECECF0)
        )
        else -> lightColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF7C4DFF),
            onPrimary = androidx.compose.ui.graphics.Color.White,
            secondary = androidx.compose.ui.graphics.Color(0xFF6950A1),
            background = androidx.compose.ui.graphics.Color(0xFFF2EFF6),
            surface = androidx.compose.ui.graphics.Color(0xFFECE9F4),
            onBackground = androidx.compose.ui.graphics.Color(0xFF111111),
            onSurface = androidx.compose.ui.graphics.Color(0xFF111111)
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
