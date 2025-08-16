package io.github.jdreioe.wingmate.ui

import androidx.compose.ui.graphics.Color

fun parseHexToColor(hexMaybe: String?): Color {
    if (hexMaybe.isNullOrBlank()) return Color.White
    val s = hexMaybe.removePrefix("#")
    val full = when (s.length) {
        6 -> "FF" + s
        8 -> s
        else -> s.padStart(8, 'F')
    }
    val intVal = try { full.toLong(16).toInt() } catch (_: Throwable) { 0xFFFFFFFF.toInt() }
    return Color(intVal)
}
