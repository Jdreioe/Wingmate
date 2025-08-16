package io.github.jdreioe.wingmate.ui

import android.os.Build
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.jdreioe.wingmate.ui.getColorScheme

@Composable
fun AppTheme(seed: Color? = null, content: @Composable () -> Unit) {
    val context = LocalContext.current
    // Prefer a direct check on the current UI mode configuration which is more
    // reliable on Android devices than relying solely on Compose's isSystemInDarkTheme
    val configuration = context.resources.configuration
    val uiModeNight = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    val useDark = when (uiModeNight) {
        Configuration.UI_MODE_NIGHT_YES -> true
        Configuration.UI_MODE_NIGHT_NO -> false
        else -> isSystemInDarkTheme()

    }

    // use provided seed for non-dynamic fallback color generation
    val colorScheme = getColorScheme(context, useDark, seed = seed)

    // Provide default Material3 typography and shapes explicitly to make the app
    // look consistent across screens.
    val typography = Typography()
    val shapes = Shapes()

    MaterialTheme(colorScheme = colorScheme, typography = typography, shapes = shapes, content = content)
}
