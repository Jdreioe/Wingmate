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

private val LightColors = lightColorScheme(
    primary = Color(0xFF7C4DFF),
    onPrimary = Color.White,
    secondary = Color(0xFF6950A1),
    background = Color(0xFFF2EFF6),
    surface = Color(0xFFECE9F4),
    onBackground = Color(0xFF111111),
    onSurface = Color(0xFF111111)
)

private val DarkColors = darkColorScheme(
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
    // allow optional override: if useDark==null fallback to a Swing-based detection
    // First try desktop/GTK/KDE hints
    // If caller requested explicit theme use it. Otherwise follow the system and re-evaluate
    // periodically so the app responds when the system theme changes.
    val use = if (useDark != null) {
        useDark
    } else {
        // Compose's system theme detection (callable here because DesktopTheme is @Composable)
        val composeSystemDark = isSystemInDarkTheme()

        // mutable state that we'll update from a coroutine polling non-composable system hints
        val polledHint = remember { mutableStateOf<Boolean?>(try { detectSystemDark() } catch (_: Throwable) { null }) }

        LaunchedEffect(Unit) {
            while (true) {
                val hint = try { detectSystemDark() } catch (_: Throwable) { null }
                if (hint != polledHint.value) polledHint.value = hint
                delay(2000)
            }
        }

        // Additionally, attempt to run `gdbus monitor --session` (if available) to listen for
        // D-Bus changes and update immediately when we detect relevant events. This avoids
        // adding a native DBus library dependency and still provides near-instant reactions
        // on GNOME/KDE systems that ship `gdbus`.
        LaunchedEffect(Unit) {
            try {
                val pb = ProcessBuilder("gdbus", "monitor", "--session")
                pb.redirectErrorStream(true)
                val proc = pb.start()
                val reader = proc.inputStream.bufferedReader()
                while (true) {
                    val line = reader.readLine() ?: break
                    // heuristics: change notifications commonly include keys like 'gtk-theme', 'ColorScheme', 'Theme' or settings paths
                    if (line.contains("gtk-theme", ignoreCase = true) || line.contains("ColorScheme", ignoreCase = true) || line.contains("Theme", ignoreCase = true) || line.contains("kdeglobals", ignoreCase = true) || line.contains("org.gtk.Settings", ignoreCase = true) || line.contains("org.gnome", ignoreCase = true)) {
                        val hint = try { detectSystemDark() } catch (_: Throwable) { null }
                        if (hint != null) polledHint.value = hint
                    }
                }
            } catch (_: Throwable) {
                // ignore: gdbus may not exist on some systems
            }
        }

        // OS-specific quick checks: macOS and Windows expose theme via system commands/registry.
        LaunchedEffect(Unit) {
            val os = System.getProperty("os.name")?.lowercase() ?: ""
            if (os.contains("mac") || os.contains("darwin")) {
                // macOS: use osascript to query appearance dark-mode boolean
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
                // Windows: read AppsUseLightTheme from registry; 1 = light, 0 = dark
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

        // If the polled hint is available use it, otherwise fall back to AWT brightness detection
        val detected = polledHint.value ?: try {
            val awt = UIManager.getColor("Panel.background") ?: AwtColor(0xFF, 0xFF, 0xFF)
            val r = awt.red / 255f
            val g = awt.green / 255f
            val b = awt.blue / 255f
            val luminance = 0.299f * r + 0.587f * g + 0.114f * b
            luminance < 0.5f
        } catch (_: Throwable) {
            composeSystemDark
        }

    // invert detected because some desktop heuristics return "true" for light in our tests;
    // flip so detected==true means dark theme (consistent with isSystemInDarkTheme semantics)
    !detected
    }

    // detection completed; use dark-mode if requested or detected

    val colors = when {
        seed != null -> {
            val primary = seed
            val luminance = 0.299f * primary.red + 0.587f * primary.green + 0.114f * primary.blue
            val onPrimary = if (luminance > 0.5f) Color.Black else Color.White
            val secondary = lerp(primary, Color(0xFF6200EE), 0.18f)
            if (use) {
                darkColorScheme(
                    primary = primary,
                    onPrimary = onPrimary,
                    secondary = secondary,
                    background = DarkColors.background,
                    surface = DarkColors.surface,
                    onBackground = DarkColors.onBackground,
                    onSurface = DarkColors.onSurface
                )
            } else {
                lightColorScheme(
                    primary = primary,
                    onPrimary = onPrimary,
                    secondary = secondary,
                    background = LightColors.background,
                    surface = LightColors.surface,
                    onBackground = LightColors.onBackground,
                    onSurface = LightColors.onSurface
                )
            }
        }
        use -> DarkColors
        else -> LightColors
    }

    MaterialTheme(colorScheme = colors, content = content)
}
