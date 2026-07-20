package io.github.jdreioe.wingmate.ui

import java.util.Locale

actual fun isDesktop(): Boolean = true

actual fun isReleaseBuild(): Boolean {
	val prop = System.getProperty("wingmate.release")
	val env = System.getenv("WINGMATE_RELEASE")
	return prop.equals("true", ignoreCase = true) || env.equals("true", ignoreCase = true)
}

actual fun systemLanguageTag(): String = Locale.getDefault().toLanguageTag()
