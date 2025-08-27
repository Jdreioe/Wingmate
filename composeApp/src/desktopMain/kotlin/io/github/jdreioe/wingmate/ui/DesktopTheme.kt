package io.github.jdreioe.wingmate.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.isSystemInDarkTheme
import javax.swing.UIManager
import java.awt.Color as AwtColor

// Desktop-specific colors (can be different from common theme)
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
    // If useDark is explicitly set, use it. Otherwise detect system theme.
    val useDarkTheme = if (useDark != null) {
        useDark
    } else {
        // Use common theme detection first, with desktop-specific enhancement
        val commonSystemDark = isSystemInDarkTheme()

        // Enhanced detection for Linux desktop environments
        val polledHint = remember { mutableStateOf<Boolean?>(try { detectSystemDark() } catch (_: Throwable) { null }) }

        LaunchedEffect(Unit) {
            while (true) {
                val hint = try { detectSystemDark() } catch (_: Throwable) { null }
                if (hint != polledHint.value) polledHint.value = hint
                delay(2000)
            }
        }

        // Listen for D-Bus theme changes (Linux)
        LaunchedEffect(Unit) {
            try {
                val pb = ProcessBuilder("gdbus", "monitor", "--session")
                pb.redirectErrorStream(true)
                val proc = pb.start()
                val reader = proc.inputStream.bufferedReader()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.contains("gtk-theme", ignoreCase = true) || 
                        line.contains("ColorScheme", ignoreCase = true) || 
                        line.contains("Theme", ignoreCase = true) || 
                        line.contains("kdeglobals", ignoreCase = true) || 
                        line.contains("org.gtk.Settings", ignoreCase = true) || 
                        line.contains("org.gnome", ignoreCase = true)) {
                        val hint = try { detectSystemDark() } catch (_: Throwable) { null }
                        if (hint != null) polledHint.value = hint
                    }
                }
            } catch (_: Throwable) {
                // ignore: gdbus may not exist on some systems
            }
        }

        // OS-specific theme detection
        LaunchedEffect(Unit) {
            val os = System.getProperty("os.name")?.lowercase() ?: ""
            if (os.contains("mac") || os.contains("darwin")) {
                // macOS: use osascript to query dark mode
                while (true) {
                    try {
                        val pb = ProcessBuilder("osascript", "-e", "tell application \"System Events\" to tell appearance preferences to get dark mode")
                        pb.redirectErrorStream(true)
                        val p = pb.start()
                        val out = p.inputStream.bufferedReader().readText().trim().lowercase()
                        val dark = when (out) {
                            "true" -> true
                            "false" -> false
                            "dark" -> true
                            else -> null
                        }
                        if (dark != null && dark != polledHint.value) polledHint.value = dark
                    } catch (_: Throwable) {
                        // ignore
                    }
                    delay(1500)
                }
            } else if (os.contains("win")) {
                // Windows: read AppsUseLightTheme from registry
                while (true) {
                    try {
                        val pb = ProcessBuilder("reg", "query", "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme")
                        pb.redirectErrorStream(true)
                        val p = pb.start()
                        val out = p.inputStream.bufferedReader().readText()
                        val regex = Regex("AppsUseLightTheme\\s+REG_DWORD\\s+0x([0-9a-fA-F]+)")
                        val m = regex.find(out)
                        if (m != null) {
                            val hex = m.groupValues[1]
                            val intVal = try { Integer.parseInt(hex, 16) } catch (_: Throwable) { -1 }
                            if (intVal >= 0) {
                                val dark = intVal == 0
                                if (dark != polledHint.value) polledHint.value = dark
                            }
                        }
                    } catch (_: Throwable) {
                        // ignore
                    }
                    delay(1500)
                }
            }
        }

        // Use enhanced detection if available, otherwise fall back to common detection
        polledHint.value ?: commonSystemDark
    }

    // Select color scheme
    val colors = when {
        seed != null -> {
            val primary = seed
            val luminance = 0.299f * primary.red + 0.587f * primary.green + 0.114f * primary.blue
            val onPrimary = if (luminance > 0.5f) Color.Black else Color.White
            val secondary = lerp(primary, Color(0xFF6200EE), 0.18f)
            if (useDarkTheme) {
                darkColorScheme(
                    primary = primary,
                    onPrimary = onPrimary,
                    secondary = secondary,
                    background = DesktopDarkColors.background,
                    surface = DesktopDarkColors.surface,
                    onBackground = DesktopDarkColors.onBackground,
                    onSurface = DesktopDarkColors.onSurface
                )
            } else {
                lightColorScheme(
                    primary = primary,
                    onPrimary = onPrimary,
                    secondary = secondary,
                    background = DesktopLightColors.background,
                    surface = DesktopLightColors.surface,
                    onBackground = DesktopLightColors.onBackground,
                    onSurface = DesktopLightColors.onSurface
                )
            }
        }
        useDarkTheme -> DesktopDarkColors
        else -> DesktopLightColors
    }

    MaterialTheme(colorScheme = colors, content = content)
}
