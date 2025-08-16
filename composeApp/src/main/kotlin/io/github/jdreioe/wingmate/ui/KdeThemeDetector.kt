package io.github.jdreioe.wingmate.ui

import androidx.compose.ui.graphics.Color
import java.nio.file.Files
import java.nio.file.Paths

fun detectKdeColorSeed(): Color? {
    val home = System.getProperty("user.home") ?: return null
    val kdeglobals = Paths.get(home, ".config", "kdeglobals")
    if (!Files.exists(kdeglobals)) return null

    val lines = Files.readAllLines(kdeglobals)
    val schemeLine = lines.firstOrNull { it.trim().startsWith("ColorScheme=") }
    val schemeName = schemeLine?.split("=")?.getOrNull(1)?.trim()

    val candidates = mutableListOf<String>()
    if (!schemeName.isNullOrBlank()) {
        candidates += listOf(
            "$home/.local/share/color-schemes/$schemeName.colors",
            "/usr/share/color-schemes/$schemeName.colors"
        )
    }

    if (candidates.isEmpty()) {
        val folder = Paths.get(home, ".local", "share", "color-schemes")
        if (Files.isDirectory(folder)) {
            Files.list(folder).use { files -> files.forEach { candidates += it.toAbsolutePath().toString() } }
        }
    }

    fun parseHex(s: String): Color? {
        val hex = s.trim().removePrefix("#")
        return try {
            val rgb = hex.toInt(16)
            val r = (rgb shr 16 and 0xFF) / 255f
            val g = (rgb shr 8 and 0xFF) / 255f
            val b = (rgb and 0xFF) / 255f
            Color(r, g, b, 1f)
        } catch (_: Throwable) { null }
    }

    for (path in candidates) {
        try {
            val file = Paths.get(path)
            if (!Files.exists(file)) continue
            val content = Files.readAllLines(file)
            val keys = listOf("AccentColor", "Accent", "ButtonNormal", "BackgroundNormal", "Foreground")
            for (k in keys) {
                val line = content.firstOrNull { it.trim().startsWith("$k=", ignoreCase = true) }
                if (line != null) {
                    val value = line.substringAfter("=").trim()
                    parseHex(value)?.let { return it }
                    val rgbParts = value.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
                    if (rgbParts.size >= 3) {
                        val r = rgbParts[0] / 255f
                        val g = rgbParts[1] / 255f
                        val b = rgbParts[2] / 255f
                        return Color(r, g, b)
                    }
                }
            }
            val hexMatch = content.mapNotNull { Regex("#[0-9A-Fa-f]{6}").find(it)?.value }.firstOrNull()
            parseHex(hexMatch ?: "")?.let { return it }
        } catch (_: Throwable) { /* ignore */ }
    }
    return null
}

/**
 * Attempt to determine whether the active KDE color-scheme is considered dark.
 * Returns true = dark, false = light, null = unknown / not detectable.
 */
fun detectKdeIsDark(): Boolean? {
    val home = System.getProperty("user.home") ?: return null
    val kdeglobals = Paths.get(home, ".config", "kdeglobals")
    if (!Files.exists(kdeglobals)) return null

    val lines = Files.readAllLines(kdeglobals)
    val schemeLine = lines.firstOrNull { it.trim().startsWith("ColorScheme=") }
    val schemeName = schemeLine?.split("=")?.getOrNull(1)?.trim()

    val candidates = mutableListOf<String>()
    if (!schemeName.isNullOrBlank()) {
        candidates += listOf(
            "$home/.local/share/color-schemes/$schemeName.colors",
            "/usr/share/color-schemes/$schemeName.colors"
        )
    }

    if (candidates.isEmpty()) {
        val folder = Paths.get(home, ".local", "share", "color-schemes")
        if (Files.isDirectory(folder)) {
            Files.list(folder).use { files -> files.forEach { candidates += it.toAbsolutePath().toString() } }
        }
    }

    fun parseHexToLuminance(s: String): Float? {
        val hex = s.trim().removePrefix("#")
        return try {
            val rgb = hex.toInt(16)
            val r = (rgb shr 16 and 0xFF) / 255f
            val g = (rgb shr 8 and 0xFF) / 255f
            val b = (rgb and 0xFF) / 255f
            0.299f * r + 0.587f * g + 0.114f * b
        } catch (_: Throwable) { null }
    }

    for (path in candidates) {
        try {
            val file = Paths.get(path)
            if (!Files.exists(file)) continue
            val content = Files.readAllLines(file)
            // Look for background-like keys which suggest light/dark
            val bgKeys = listOf("BackgroundNormal", "Background", "ActiveBackground")
            for (k in bgKeys) {
                val line = content.firstOrNull { it.trim().startsWith("$k=", ignoreCase = true) }
                if (line != null) {
                    val value = line.substringAfter("=").trim()
                    parseHexToLuminance(value)?.let { lum -> return lum < 0.5f }
                    val rgbParts = value.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
                    if (rgbParts.size >= 3) {
                        val r = rgbParts[0] / 255f
                        val g = rgbParts[1] / 255f
                        val b = rgbParts[2] / 255f
                        val lum = 0.299f * r + 0.587f * g + 0.114f * b
                        return lum < 0.5f
                    }
                }
            }
        } catch (_: Throwable) { /* ignore and continue */ }
    }
    return null
}

/**
 * Heuristic detection for GTK-based dark preference and environment hints.
 */
fun detectGtkIsDark(): Boolean? {
    try {
        val gtkThemeEnv = System.getenv("GTK_THEME")
            if (!gtkThemeEnv.isNullOrBlank()) {
            if (gtkThemeEnv.contains("dark", ignoreCase = true)) {
                return true
            }
        }

        val home = System.getProperty("user.home") ?: return null
        val gtk3 = Paths.get(home, ".config", "gtk-3.0", "settings.ini")
        if (Files.exists(gtk3)) {
            val lines = Files.readAllLines(gtk3)
            val darkLine = lines.firstOrNull { it.trim().startsWith("gtk-application-prefer-dark-theme") }
                if (darkLine != null) {
                val value = darkLine.substringAfter("=").trim()
                return value == "1" || value.equals("true", true)
            }
        }

        val gtk4 = Paths.get(home, ".config", "gtk-4.0", "settings.ini")
        if (Files.exists(gtk4)) {
            val lines = Files.readAllLines(gtk4)
            val darkLine = lines.firstOrNull { it.trim().startsWith("gtk-application-prefer-dark-theme") }
            if (darkLine != null) {
                val value = darkLine.substringAfter("=").trim()
                return value == "1" || value.equals("true", true)
            }
        }
    } catch (_: Throwable) { /* ignore */ }
    return null
}

/**
 * Combined system detection: prefer KDE, then GTK/env hints, otherwise null
 */
fun detectSystemDark(): Boolean? {
    detectKdeIsDark()?.let { return it }
    detectGtkIsDark()?.let { return it }
    // as a last attempt, inspect XDG_CURRENT_DESKTOP for hints
    try {
        val xdg = System.getenv("XDG_CURRENT_DESKTOP") ?: System.getenv("DESKTOP_SESSION")
        if (!xdg.isNullOrBlank()) {
            if (xdg.contains("GNOME", ignoreCase = true) && System.getenv("GTK_THEME")?.contains("dark", true) == true) {
                return true
            }
            if (xdg.contains("KDE", ignoreCase = true)) {
                // KDE-specific detection failed earlier; don't override.
            }
        }
    } catch (_: Throwable) { /* ignore */ }
    return null
}
