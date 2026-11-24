package io.github.jdreioe.wingmate.infrastructure

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import io.github.jdreioe.wingmate.domain.obf.CloudDriveSignIn
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageErrorCode
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageException
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google Drive sign-in through Google Identity Services. Google no longer accepts custom-scheme
 * redirects for Android OAuth clients, so the account and consent come from Play services; the
 * Android OAuth client is matched by package name and signing certificate, not by a client ID.
 *
 * Play services keeps the grant, so there is no refresh token: [refreshPlatformToken] asks again
 * silently and returns null when Google needs the person to confirm (then they sign in again).
 */
class AndroidGoogleDriveSignIn(
    private val context: Context,
    private val now: () -> Long = { System.currentTimeMillis() },
) : CloudDriveSignIn {
    private var launchResolution: ((PendingIntent) -> Unit)? = null
    private var pendingResolution: CompletableDeferred<Intent?>? = null

    override fun isSupported(kind: RemoteStorageKind): Boolean = kind == RemoteStorageKind.GOOGLE_DRIVE

    /** Called from the host activity's `onCreate` with a `StartIntentSenderForResult` launcher. */
    fun registerResolutionLauncher(launch: (PendingIntent) -> Unit) {
        launchResolution = launch
    }

    /** Called from the launcher callback. A null [data] or non-OK [resultCode] means cancelled. */
    fun onResolutionResult(resultCode: Int, data: Intent?) {
        val waiting = pendingResolution
        pendingResolution = null
        waiting?.complete(data.takeIf { resultCode == Activity.RESULT_OK })
    }

    override suspend fun signIn(kind: RemoteStorageKind): String? {
        if (!isSupported(kind)) return null
        val first = authorize()
        val result = if (first.hasResolution()) {
            val pendingIntent = first.pendingIntent
                ?: throw RemoteStorageException(RemoteStorageErrorCode.UNAUTHORIZED, "Google sign-in could not start")
            val launch = launchResolution
                ?: throw RemoteStorageException(RemoteStorageErrorCode.UNSUPPORTED_PLATFORM, "Sign-in needs Wingmate to be open")
            val waiting = CompletableDeferred<Intent?>()
            pendingResolution?.complete(null)
            pendingResolution = waiting
            withContext(Dispatchers.Main) { launch(pendingIntent) }
            val data = waiting.await() ?: return null
            try {
                client().getAuthorizationResultFromIntent(data)
            } catch (error: com.google.android.gms.common.api.ApiException) {
                if (error.statusCode == com.google.android.gms.common.api.CommonStatusCodes.CANCELED) return null
                throw RemoteStorageException(RemoteStorageErrorCode.UNAUTHORIZED, "Google sign-in failed", error)
            }
        } else {
            first
        }
        return result.toBundle()
    }

    override suspend fun refreshPlatformToken(kind: RemoteStorageKind): String? {
        if (!isSupported(kind)) return null
        val result = runCatching { authorize() }.getOrNull() ?: return null
        if (result.hasResolution()) return null
        return result.toBundle()
    }

    private fun AuthorizationResult.toBundle(): String? {
        val token = accessToken ?: return null
        return OAuthTokenBundle(
            provider = RemoteStorageKind.GOOGLE_DRIVE.wireValue,
            accessToken = token,
            refreshToken = null,
            expiresAtMillis = now() + TOKEN_LIFETIME_MS,
            scope = CloudDriveOAuth.GOOGLE_DRIVE_FILE_SCOPE,
        ).encode()
    }

    private fun client() = Identity.getAuthorizationClient(context)

    private suspend fun authorize(): AuthorizationResult {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(CloudDriveOAuth.GOOGLE_DRIVE_FILE_SCOPE)))
            .build()
        return try {
            client().authorize(request).await()
        } catch (error: RemoteStorageException) {
            throw error
        } catch (error: Exception) {
            throw RemoteStorageException(RemoteStorageErrorCode.UNAUTHORIZED, "Google sign-in is unavailable on this device", error)
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }

    private companion object {
        /** Google access tokens last an hour; refresh a little early. */
        const val TOKEN_LIFETIME_MS = 55 * 60 * 1000L
    }
}
