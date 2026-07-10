package io.github.jdreioe.wingmate.ui

import kotlin.native.Platform

actual fun isDesktop(): Boolean = false

actual fun isReleaseBuild(): Boolean = !Platform.isDebugBinary
