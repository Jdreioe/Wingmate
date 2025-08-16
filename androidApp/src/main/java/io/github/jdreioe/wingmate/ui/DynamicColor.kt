package io.github.jdreioe.wingmate.ui

import android.content.Context
import android.os.Build
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

// A small helper that centralizes dynamic color selection.
// If running on Android 12+ we prefer platform dynamic colors. Otherwise,
// if a seed color is provided, generate a simple derived ColorScheme as a fallback.

private val FallbackPrimaryLight = Color(0xFF7C4DFF)
private val FallbackSecondaryLight = Color(0xFF6950A1)
private val FallbackBackgroundLight = Color(0xFFF2EFF6)
private val FallbackSurfaceLight = Color(0xFFECE9F4)

private val FallbackPrimaryDark = Color(0xFF6950A1)
private val FallbackSecondaryDark = Color(0xFF7C4DFF)
private val FallbackBackgroundDark = Color(0xFF121217)
private val FallbackSurfaceDark = Color(0xFF1A1A1F)

fun getColorScheme(context: Context, useDark: Boolean, seed: Color? = null): ColorScheme {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (seed != null) {
            // Very small generator: derive primary from seed and compute readable onPrimary.
            val primary = seed
            val luminance = 0.299f * primary.red + 0.587f * primary.green + 0.114f * primary.blue
            val onPrimary = if (luminance > 0.5f) Color.Black else Color.White
            val secondary = lerp(primary, Color(0xFF6200EE), 0.18f)
            if (useDark) {
                darkColorScheme(
                    primary = primary,
                    onPrimary = onPrimary,
                    secondary = secondary,
                    background = FallbackBackgroundDark,
                    surface = FallbackSurfaceDark,
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            } else {
                lightColorScheme(
                    primary = primary,
                    onPrimary = onPrimary,
                    secondary = secondary,
                    background = FallbackBackgroundLight,
                    surface = FallbackSurfaceLight,
                    onBackground = Color.Black,
                    onSurface = Color.Black
                )
            }
        } else {
            if (useDark) {
                darkColorScheme(
                    primary = FallbackPrimaryDark,
                    onPrimary = Color.White,
                    secondary = FallbackSecondaryDark,
                    background = FallbackBackgroundDark,
                    surface = FallbackSurfaceDark,
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            } else {
                lightColorScheme(
                    primary = FallbackPrimaryLight,
                    onPrimary = Color.White,
                    secondary = FallbackSecondaryLight,
                    background = FallbackBackgroundLight,
                    surface = FallbackSurfaceLight,
                    onBackground = Color.Black,
                    onSurface = Color.Black
                )
            }
        }
    }
}
