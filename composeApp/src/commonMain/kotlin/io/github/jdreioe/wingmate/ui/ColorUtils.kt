package io.github.jdreioe.wingmate.ui

import androidx.compose.ui.graphics.Color

/**
 * Parse OBF color strings which can be:
 * - Hex: #RRGGBB or #AARRGGBB
 * - RGB: rgb(255, 0, 0)
 * - RGBA: rgba(255, 0, 0, 0.5)
 */
fun parseHexToColor(hexMaybe: String?): Color {
    if (hexMaybe.isNullOrBlank()) return Color.White
    val s = hexMaybe.trim()
    
    // Handle rgb(r, g, b) format
    if (s.startsWith("rgb(") && s.endsWith(")")) {
        val inner = s.removePrefix("rgb(").removeSuffix(")")
        val parts = inner.split(",").map { it.trim() }
        if (parts.size == 3) {
            val r = parts[0].toIntOrNull() ?: 255
            val g = parts[1].toIntOrNull() ?: 255
            val b = parts[2].toIntOrNull() ?: 255
            return Color(r, g, b)
        }
    }
    
    // Handle rgba(r, g, b, a) format
    if (s.startsWith("rgba(") && s.endsWith(")")) {
        val inner = s.removePrefix("rgba(").removeSuffix(")")
        val parts = inner.split(",").map { it.trim() }
        if (parts.size == 4) {
            val r = parts[0].toIntOrNull() ?: 255
            val g = parts[1].toIntOrNull() ?: 255
            val b = parts[2].toIntOrNull() ?: 255
            val a = parts[3].toFloatOrNull() ?: 1f
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
        else -> hex.padStart(8, 'F')
    }
    val intVal = try { full.toLong(16).toInt() } catch (_: Throwable) { 0xFFFFFFFF.toInt() }
    return Color(intVal)
}
