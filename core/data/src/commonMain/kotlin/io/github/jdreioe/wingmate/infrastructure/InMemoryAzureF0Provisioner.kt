package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.AzureF0Provisioner
import io.github.jdreioe.wingmate.domain.AzureF0Resource
import io.github.jdreioe.wingmate.domain.AzureSignInResult
import io.github.jdreioe.wingmate.domain.AzureSignedInUser
import io.github.jdreioe.wingmate.domain.AzureSubscription

class InMemoryAzureF0Provisioner : AzureF0Provisioner {
    private var signedIn = false
    private var subscriptions = listOf<AzureSubscription>()
    private var f0Resources = mutableMapOf<String, List<AzureF0Resource>>()

    fun configureSubscriptions(subs: List<AzureSubscription>) { subscriptions = subs }

    fun configureF0Resources(subscriptionId: String, resources: List<AzureF0Resource>) {
        f0Resources[subscriptionId] = resources
    }

    override suspend fun signIn(): AzureSignInResult {
        signedIn = true
        return AzureSignInResult.SUCCESS
    }

    override suspend fun signOut() { signedIn = false }

    override suspend fun isSignedIn(): Boolean = signedIn

    override suspend fun getSignedInUser(): AzureSignedInUser? = if (signedIn)
        AzureSignedInUser("Test User", "test@example.com", "tenant-id") else null

    override suspend fun getSubscriptions(): List<AzureSubscription> =
        if (signedIn) subscriptions else emptyList()

    override suspend fun getF0Resources(subscriptionId: String): List<AzureF0Resource> =
        if (signedIn) f0Resources[subscriptionId] ?: emptyList() else emptyList()
}
