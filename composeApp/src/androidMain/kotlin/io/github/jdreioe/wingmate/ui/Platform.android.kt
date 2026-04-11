package io.github.jdreioe.wingmate.ui

actual fun isDesktop(): Boolean = false

actual fun isReleaseBuild(): Boolean = runCatching {
	// Resolve app-module BuildConfig at runtime so common UI can detect Android release builds.
	val buildConfig = Class.forName("com.hojmoseit.wingmate.BuildConfig")
	val isDebug = buildConfig.getField("DEBUG").getBoolean(null)
	!isDebug
}.getOrDefault(true)
