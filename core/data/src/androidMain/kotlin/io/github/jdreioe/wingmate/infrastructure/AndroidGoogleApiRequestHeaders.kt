package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

class AndroidGoogleApiRequestHeaders(private val context: Context) : GoogleApiRequestHeaders {
    override fun values(): Map<String, String> {
        val certificate = signingCertificateSha1() ?: return emptyMap()
        return mapOf(
            "X-Android-Package" to context.packageName,
            "X-Android-Cert" to certificate,
        )
    }

    @Suppress("DEPRECATION")
    private fun signingCertificateSha1(): String? {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            packageInfo.signatures?.firstOrNull()
        } ?: return null
        return MessageDigest.getInstance("SHA-1")
            .digest(signature.toByteArray())
            .joinToString(separator = "") { byte -> "%02X".format(byte) }
    }
}
