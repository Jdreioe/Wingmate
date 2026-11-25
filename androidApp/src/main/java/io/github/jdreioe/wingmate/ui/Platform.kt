package io.github.jdreioe.wingmate.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Locale

fun systemLanguageTag(): String = Locale.getDefault().toLanguageTag()

/**
 * Hides the system status bar while [hidden] is true (e.g. inside a dedicated Screens
 * workspace) and restores it on leave, while keeping swipe-to-peek available.
 */
@Composable
fun HideStatusBarEffect(hidden: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, hidden) {
        val window = view.context.findActivity()?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            if (hidden) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.statusBars())
            } else {
                controller.show(WindowInsetsCompat.Type.statusBars())
            }
        }
        onDispose {
            if (hidden && window != null) {
                WindowCompat.getInsetsController(window, view)
                    .show(WindowInsetsCompat.Type.statusBars())
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun PlatformBackgroundEffect(onBackground: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, onBackground) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) onBackground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

class MicrophonePermissionState(
    val isGranted: Boolean,
    val shouldShowRationale: Boolean,
    val deniedPermanently: Boolean,
    val hasRequested: Boolean = false,
    val request: () -> Unit,
    val openSettings: () -> Unit
)

@Composable
fun rememberMicrophonePermissionState(): MicrophonePermissionState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val permission = Manifest.permission.RECORD_AUDIO

    fun checkGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    var isGranted by remember { mutableStateOf(checkGranted()) }
    var deniedPermanently by remember { mutableStateOf(false) }
    var shouldShowRationale by remember { mutableStateOf(false) }
    var hasRequested by remember { mutableStateOf(false) }

    fun refreshDenialState() {
        if (isGranted) {
            shouldShowRationale = false
            deniedPermanently = false
            return
        }
        if (!hasRequested) {
            shouldShowRationale = false
            deniedPermanently = false
            return
        }
        val activity = context as? Activity
        val canShowRationale = activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        shouldShowRationale = canShowRationale
        deniedPermanently = !canShowRationale
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasRequested = true
        isGranted = granted || checkGranted()
        if (isGranted) {
            shouldShowRationale = false
            deniedPermanently = false
        } else {
            refreshDenialState()
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = checkGranted()
                isGranted = granted
                if (granted) {
                    shouldShowRationale = false
                    deniedPermanently = false
                } else if (hasRequested) {
                    refreshDenialState()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return MicrophonePermissionState(
        isGranted = isGranted,
        shouldShowRationale = shouldShowRationale,
        deniedPermanently = deniedPermanently,
        hasRequested = hasRequested,
        request = { launcher.launch(permission) },
        openSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", context.packageName, null)
                }
            )
        }
    )
}
