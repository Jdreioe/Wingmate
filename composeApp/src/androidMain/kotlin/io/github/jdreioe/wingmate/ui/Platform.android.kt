package io.github.jdreioe.wingmate.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
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
