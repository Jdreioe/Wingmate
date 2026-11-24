package io.github.jdreioe.wingmate.infrastructure

import android.app.Activity
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageErrorCode
import io.github.jdreioe.wingmate.domain.obf.RemoteStorageException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * Shows a cloud drive's sign-in page in a Custom Tab and waits for the `wingmate-auth://oauth`
 * redirect. The app's redirect activity calls [deliverRedirect]; the host activity calls
 * [onHostPaused] / [onHostResumed] so that coming back without a redirect counts as cancel.
 */
class AndroidCustomTabsOAuthBrowser : OAuthBrowser {

    override suspend fun authorize(
        redirectUriTemplate: String,
        authorizeUrlFor: (redirectUri: String) -> String,
    ): OAuthRedirect? {
        val activity = host?.get()
            ?: throw RemoteStorageException(RemoteStorageErrorCode.UNSUPPORTED_PLATFORM, "Sign-in needs Wingmate to be open")
        val waiting = CompletableDeferred<String?>()
        synchronized(lock) {
            pending?.complete(null)
            pending = waiting
            leftHost = false
        }
        try {
            withContext(Dispatchers.Main) {
                CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
                    .launchUrl(activity, Uri.parse(authorizeUrlFor(redirectUriTemplate)))
            }
            val callbackUrl = waiting.await() ?: return null
            return OAuthRedirect(redirectUri = redirectUriTemplate, callbackUrl = callbackUrl)
        } catch (error: android.content.ActivityNotFoundException) {
            throw RemoteStorageException(RemoteStorageErrorCode.UNSUPPORTED_PLATFORM, "No browser is available for sign-in", error)
        } finally {
            synchronized(lock) { if (pending === waiting) pending = null }
        }
    }

    companion object {
        private val lock = Any()
        private var pending: CompletableDeferred<String?>? = null
        private var leftHost = false
        private var host: WeakReference<Activity>? = null

        /** The activity that opens Custom Tabs. Called from its `onCreate`. */
        fun attachHost(activity: Activity) {
            host = WeakReference(activity)
        }

        /** Called by the redirect activity with the full callback URL. */
        fun deliverRedirect(callbackUrl: String) {
            synchronized(lock) { pending?.complete(callbackUrl) }
        }

        fun onHostPaused() {
            synchronized(lock) { if (pending != null) leftHost = true }
        }

        /** Back in the app without a redirect: the person closed the sign-in page. */
        fun onHostResumed() {
            synchronized(lock) {
                if (leftHost) {
                    leftHost = false
                    pending?.complete(null)
                }
            }
        }
    }
}
