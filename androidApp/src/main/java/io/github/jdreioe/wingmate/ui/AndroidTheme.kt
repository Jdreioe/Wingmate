package io.github.jdreioe.wingmate.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val powerSaveMode = rememberPowerSaveMode()
    val useDarkTheme = powerSaveMode || isSystemInDarkTheme()
    val view = LocalView.current
    SideEffect {
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).run {
                isAppearanceLightStatusBars = !useDarkTheme
                isAppearanceLightNavigationBars = !useDarkTheme
            }
        }
    }
    
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            // Android 12+ (API 31+) supports dynamic colors
            if (useDarkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        useDarkTheme -> darkColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF6950A1),
            onPrimary = androidx.compose.ui.graphics.Color.White,
            secondary = androidx.compose.ui.graphics.Color(0xFF7C4DFF),
            background = androidx.compose.ui.graphics.Color(0xFF121217),
            surface = androidx.compose.ui.graphics.Color(0xFF1A1A1F),
            onBackground = androidx.compose.ui.graphics.Color(0xFFECECF0),
            onSurface = androidx.compose.ui.graphics.Color(0xFFECECF0)
        )
        else -> lightColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF7C4DFF),
            onPrimary = androidx.compose.ui.graphics.Color.White,
            secondary = androidx.compose.ui.graphics.Color(0xFF6950A1),
            background = androidx.compose.ui.graphics.Color(0xFFF2EFF6),
            surface = androidx.compose.ui.graphics.Color(0xFFECE9F4),
            onBackground = androidx.compose.ui.graphics.Color(0xFF111111),
            onSurface = androidx.compose.ui.graphics.Color(0xFF111111)
        )
    }

    MaterialTheme(
        colorScheme = if (powerSaveMode) colorScheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceDim = Color.Black,
            surfaceContainerLowest = Color.Black,
        ) else colorScheme,
        content = content
    )
}

/** System state only; refresh on resume as well as when Battery Saver changes. */
@Composable
private fun rememberPowerSaveMode(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    var enabled by remember(context) { mutableStateOf(powerManager.isPowerSaveMode) }
    DisposableEffect(context, lifecycleOwner) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                enabled = powerManager.isPowerSaveMode
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) enabled = powerManager.isPowerSaveMode
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        enabled = powerManager.isPowerSaveMode
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    return enabled
}
