package io.github.jdreioe.wingmate.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

actual fun isDesktop(): Boolean = false

actual fun isReleaseBuild(): Boolean = runCatching {
	// Resolve app-module BuildConfig at runtime so common UI can detect Android release builds.
	val buildConfig = Class.forName("com.hojmoseit.wingmate.BuildConfig")
	val isDebug = buildConfig.getField("DEBUG").getBoolean(null)
	!isDebug
}.getOrDefault(true)

actual fun systemLanguageTag(): String = Locale.getDefault().toLanguageTag()

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}

@Composable
actual fun rememberMicrophonePermissionState(): MicrophonePermissionState {
    val context = LocalContext.current
    var deniedPermanently by remember { mutableStateOf(false) }
    var shouldShowRationale by remember { mutableStateOf(false) }
    val permission = Manifest.permission.RECORD_AUDIO

    val isGranted = ContextCompat.checkSelfPermission(context, permission) ==
        PackageManager.PERMISSION_GRANTED

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            val activity = context as? Activity
            val canShowRationale = activity != null &&
                ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
            shouldShowRationale = canShowRationale
            deniedPermanently = !canShowRationale
        } else {
            shouldShowRationale = false
            deniedPermanently = false
        }
    }

    return remember(launcher, isGranted, deniedPermanently, shouldShowRationale) {
        MicrophonePermissionState(
            isGranted = isGranted,
            shouldShowRationale = shouldShowRationale,
            deniedPermanently = deniedPermanently,
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
}
