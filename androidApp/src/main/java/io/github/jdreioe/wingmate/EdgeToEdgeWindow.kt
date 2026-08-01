package io.github.jdreioe.wingmate

import android.content.res.Configuration
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat

/**
 * Lets Compose receive and consume the window insets without relying on the
 * system-bar color and display-cutout APIs deprecated in Android 15.
 */
internal fun ComponentActivity.configureEdgeToEdgeWindow() {
    WindowCompat.setDecorFitsSystemWindows(window, false)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        val isDarkTheme =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).run {
            isAppearanceLightStatusBars = !isDarkTheme
            isAppearanceLightNavigationBars = !isDarkTheme
        }
    }
}
