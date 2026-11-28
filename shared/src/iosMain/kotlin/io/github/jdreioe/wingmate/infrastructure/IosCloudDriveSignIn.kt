package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.obf.CloudDriveSignIn
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageErrorCode
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageException
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageKind
import io.ktor.client.HttpClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.concurrent.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Implemented in Swift with `ASWebAuthenticationSession`. Calls [completion] with the callback URL,
 * or with a null URL and `"cancelled"` / an error message.
 */
interface IosWebAuthenticationBridge {
    fun authenticate(url: String, callbackScheme: String, completion: (callbackUrl: String?, error: String?) -> Unit)
}

/** [OAuthBrowser] that shows the provider's page in the system web-authentication sheet. */
class IosOAuthBrowser(private val bridge: IosWebAuthenticationBridge) : OAuthBrowser {
    override suspend fun authorize(
        redirectUriTemplate: String,
        authorizeUrlFor: (redirectUri: String) -> String,
    ): OAuthRedirect? {
        val url = authorizeUrlFor(redirectUriTemplate)
        val scheme = redirectUriTemplate.substringBefore(':')
        return suspendCancellableCoroutine { continuation ->
            bridge.authenticate(url, scheme) { callbackUrl, error ->
                if (!continuation.isActive) return@authenticate
                when {
                    callbackUrl != null -> continuation.resume(OAuthRedirect(redirectUriTemplate, callbackUrl))
                    error == null || error == CANCELLED -> continuation.resume(null)
                    else -> continuation.resumeWithException(RemoteStorageException(RemoteStorageErrorCode.UNAUTHORIZED, error))
                }
            }
        }
    }

    companion object {
        const val CANCELLED = "cancelled"
    }
}

/**
 * Google Drive / OneDrive sign-in on iOS. The Swift app registers the web-authentication bridge and
 * the public OAuth client IDs from Info.plist at launch; a provider is offered only when configured.
 */
class IosCloudDriveSignIn(private val httpClient: HttpClient) : CloudDriveSignIn {
    private class Registration(val bridge: IosWebAuthenticationBridge, val googleClientId: String?, val microsoftClientId: String?)

    private fun delegate(): OAuthPkceSignIn? {
        val registration = registrationRef.value ?: return null
        val configs = buildList {
            registration.googleClientId?.trim()?.takeIf(String::isNotEmpty)?.let { id ->
                add(CloudDriveOAuth.google(id, CloudDriveOAuth.googleIosRedirectUri(id)))
            }
            registration.microsoftClientId?.trim()?.takeIf(String::isNotEmpty)?.let { id ->
                add(CloudDriveOAuth.microsoft(id))
            }
        }
        if (configs.isEmpty()) return null
        return OAuthPkceSignIn(httpClient, configs, IosOAuthBrowser(registration.bridge))
    }

    override fun isSupported(kind: RemoteStorageKind): Boolean = delegate()?.isSupported(kind) == true

    override suspend fun signIn(kind: RemoteStorageKind): String? = (delegate()
        ?: throw RemoteStorageException(RemoteStorageErrorCode.UNSUPPORTED_PLATFORM, "Sign-in for this storage is not set up in this build of Wingmate"))
        .signIn(kind)

    companion object {
        private val registrationRef = AtomicReference<Registration?>(null)

        fun register(bridge: IosWebAuthenticationBridge, googleClientId: String?, microsoftClientId: String?) {
            registrationRef.value = Registration(bridge, googleClientId, microsoftClientId)
        }
    }
}
