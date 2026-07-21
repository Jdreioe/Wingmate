package io.github.jdreioe.wingmate

import android.app.Activity
import android.content.Context
import com.microsoft.identity.client.*
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalUserCancelException
import io.github.jdreioe.wingmate.domain.AzureF0Provisioner
import io.github.jdreioe.wingmate.domain.AzureF0Resource
import io.github.jdreioe.wingmate.domain.AzureSignInResult
import io.github.jdreioe.wingmate.domain.AzureSignedInUser
import io.github.jdreioe.wingmate.domain.AzureSubscription
import io.github.jdreioe.wingmate.infrastructure.AzureArmClient
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class AndroidAzureF0Provisioner(
    private val context: Context
) : AzureF0Provisioner {

    private val armClient = AzureArmClient(HttpClient(OkHttp))
    private val scopes = arrayOf("https://management.azure.com/user_impersonation")

    private val msalApp: ISingleAccountPublicClientApplication by lazy {
        val config = com.hojmoseit.wingmate.R.raw.msal_config
        PublicClientApplication.createSingleAccountPublicClientApplication(context, config)
    }

    override suspend fun signIn(): AzureSignInResult = withContext(Dispatchers.Main) {
        val activity = getActivity() ?: return@withContext AzureSignInResult.ERROR("No activity context")
        suspendCancellableCoroutine { cont ->
            msalApp.signIn(activity, null, scopes, object : AuthenticationCallback {
                override fun onSuccess(authenticationResult: IAuthenticationResult) {
                    cont.resume(AzureSignInResult.SUCCESS)
                }
                override fun onError(exception: MsalException) {
                    val result = if (exception is MsalUserCancelException) {
                        AzureSignInResult.CANCELLED
                    } else {
                        println("MSAL sign-in error: ${exception.message}")
                        AzureSignInResult.ERROR(exception.message)
                    }
                    cont.resume(result)
                }
                override fun onCancel() {
                    cont.resume(AzureSignInResult.CANCELLED)
                }
            })
        }
    }

    override suspend fun signOut() = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            msalApp.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
                override fun onSignOut() { cont.resume(Unit) }
                override fun onError(exception: MsalException) { cont.resume(Unit) }
            })
        }
    }

    override suspend fun isSignedIn(): Boolean = withContext(Dispatchers.IO) {
        getAccount() != null
    }

    override suspend fun getSignedInUser(): AzureSignedInUser? = withContext(Dispatchers.IO) {
        val account = getAccount()
        account?.let {
            AzureSignedInUser(
                displayName = it.claims?.get("name")?.toString() ?: "",
                username = it.username ?: "",
                tenantId = it.tenantId ?: ""
            )
        }
    }

    override suspend fun getSubscriptions(): List<AzureSubscription> = withContext(Dispatchers.IO) {
        val token = acquireTokenSilent() ?: return@withContext emptyList()
        armClient.listSubscriptions(token)
    }

    override suspend fun getF0Resources(subscriptionId: String): List<AzureF0Resource> = withContext(Dispatchers.IO) {
        val token = acquireTokenSilent() ?: return@withContext emptyList()
        armClient.listF0SpeechResources(token, subscriptionId)
    }

    override suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        acquireTokenSilent()
    }

    private suspend fun getAccount(): IAccount? = suspendCancellableCoroutine { cont ->
        msalApp.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
            override fun onAccountLoaded(account: IAccount?) { cont.resume(account) }
            override fun onError(exception: MsalException) { cont.resume(null) }
            override fun onAccountChanged(oldAccount: IAccount?, newAccount: IAccount?) {
                cont.resume(newAccount)
            }
        })
    }

    private suspend fun acquireTokenSilent(): String? = suspendCancellableCoroutine { cont ->
        val account = getAccountSync()
        if (account == null) { cont.resume(null); return@suspendCancellableCoroutine }
        msalApp.acquireTokenSilentAsync(scopes, account.authority, object : AuthenticationCallback {
            override fun onSuccess(authenticationResult: IAuthenticationResult) {
                cont.resume(authenticationResult.accessToken)
            }
            override fun onError(exception: MsalException) { cont.resume(null) }
            override fun onCancel() { cont.resume(null) }
        })
    }

    // Non-suspending version to avoid calling suspend from within suspendCancellableCoroutine
    private fun getAccountSync(): IAccount? {
        var result: IAccount? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        msalApp.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
            override fun onAccountLoaded(account: IAccount?) { result = account; latch.countDown() }
            override fun onError(exception: MsalException) { latch.countDown() }
            override fun onAccountChanged(old: IAccount?, new: IAccount?) { result = new; latch.countDown() }
        })
        latch.await(10, TimeUnit.SECONDS)
        return result
    }

    private fun getActivity(): Activity? = when (context) {
        is Activity -> context
        else -> null
    }
}
