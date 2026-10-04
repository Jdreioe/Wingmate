package io.github.jdreioe.wingmate.infrastructure

import platform.Foundation.NSBundle

class IosGoogleApiRequestHeaders : GoogleApiRequestHeaders {
    override fun values(): Map<String, String> =
        NSBundle.mainBundle.bundleIdentifier
            ?.takeIf(String::isNotBlank)
            ?.let { mapOf("X-Ios-Bundle-Identifier" to it) }
            .orEmpty()
}
