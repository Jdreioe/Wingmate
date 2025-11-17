package io.github.jdreioe.wingmate.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme

// Desktop-specific colors
private val DesktopLightColors = lightColorScheme(
    primary = Color(0xFF7C4DFF),
    onPrimary = Color.White,
    secondary = Color(0xFF6950A1),
    background = Color(0xFFF2EFF6),
    surface = Color(0xFFECE9F4),
    onBackground = Color(0xFF111111),
    onSurface = Color(0xFF111111)
)

private val DesktopDarkColors = darkColorScheme(
    primary = Color(0xFF6950A1),
    onPrimary = Color.White,
    secondary = Color(0xFF7C4DFF),
    background = Color(0xFF121217),
    surface = Color(0xFF1A1A1F),
    onBackground = Color(0xFFECECF0),
    onSurface = Color(0xFFECECF0)
)

@Composable
fun DesktopTheme(useDark: Boolean? = null, seed: Color? = null, content: @Composable () -> Unit) {
    // Use explicitly provided dark mode setting, or detect from system
    var detectedDark by remember { mutableStateOf<Boolean?>(null) }
    
    val useDarkTheme = when {
        useDark != null -> useDark
        detectedDark != null -> detectedDark!!
        else -> isSystemInDarkTheme()
    }

    // Enhanced detection for Linux desktop environments
    LaunchedEffect(Unit) {
        while (true) {
            val hint = try { detectSystemDark() } catch (_: Throwable) { null }
            if (hint != null && hint != detectedDark) {
                detectedDark = hint
            }
            delay(2000)
        }
    }

    // Select color scheme
    val colors = if (useDarkTheme) DesktopDarkColors else DesktopLightColors

    MaterialTheme(colorScheme = colors, content = content)
}
