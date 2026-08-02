package io.github.jdreioe.wingmate.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Pick whichever of black or white has the stronger WCAG contrast ratio. */
fun contrastingContentColor(background: Color): Color {
    val luminance = background.luminance()
    val contrastWithBlack = (luminance + 0.05f) / 0.05f
    val contrastWithWhite = 1.05f / (luminance + 0.05f)
    return if (contrastWithWhite >= contrastWithBlack) Color.White else Color.Black
}

/**
 * Parse OBF color strings which can be:
 * - Hex: #RRGGBB or #AARRGGBB
 * - RGB: rgb(255, 0, 0)
 * - RGBA: rgba(255, 0, 0, 0.5)
 */
fun parseHexToColor(hexMaybe: String?): Color {
    return parseObfColorOrNull(hexMaybe) ?: Color.White
}

/** Strict OBF/CSS color parsing for rendering user-authored board colors. */
fun parseObfColorOrNull(value: String?): Color? {
    if (value.isNullOrBlank()) return null
    val s = value.trim()
    
    // Handle rgb(r, g, b) format
    if (s.startsWith("rgb(") && s.endsWith(")")) {
        val inner = s.removePrefix("rgb(").removeSuffix(")")
        val parts = inner.split(",").map { it.trim() }
        if (parts.size == 3) {
            val r = parts[0].toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            val g = parts[1].toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            val b = parts[2].toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            return Color(r, g, b)
        }
    }
    
    // Handle rgba(r, g, b, a) format
    if (s.startsWith("rgba(") && s.endsWith(")")) {
        val inner = s.removePrefix("rgba(").removeSuffix(")")
        val parts = inner.split(",").map { it.trim() }
        if (parts.size == 4) {
            val r = parts[0].toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            val g = parts[1].toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            val b = parts[2].toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            val a = parts[3].toFloatOrNull()?.takeIf { it in 0f..1f } ?: return null
            return Color(r, g, b, (a * 255).toInt())
        }
    }
    
    // Handle hex format
    val hex = s.removePrefix("#")
    val full = when (hex.length) {
        3 -> "FF" + hex.map { "$it$it" }.joinToString("") // #RGB -> #RRGGBB
        4 -> hex[0].toString() + hex[0] + hex.drop(1).map { "$it$it" }.joinToString("") // #ARGB
        6 -> "FF" + hex
        8 -> hex
        else -> return null
    }
    val intVal = try { full.toLong(16).toInt() } catch (_: Throwable) { return null }
    return Color(intVal)
}
