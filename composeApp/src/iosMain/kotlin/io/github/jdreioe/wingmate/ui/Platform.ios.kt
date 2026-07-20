package io.github.jdreioe.wingmate.ui

import kotlin.native.Platform
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier

actual fun isDesktop(): Boolean = false

actual fun isReleaseBuild(): Boolean = !Platform.isDebugBinary

actual fun systemLanguageTag(): String = NSLocale.currentLocale.localeIdentifier
