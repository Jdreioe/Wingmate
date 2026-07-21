package io.github.jdreioe.wingmate.domain

import kotlinx.serialization.Serializable

@Serializable
data class AzureSubscription(
    val id: String,
    val name: String,
    val tenantId: String,
    val state: String = "Enabled"
)

@Serializable
data class AzureF0Resource(
    val id: String,
    val name: String,
    val region: String,
    val resourceGroup: String
)

sealed class AzureSignInResult {
    object SUCCESS : AzureSignInResult()
    object CANCELLED : AzureSignInResult()
    data class ERROR(val message: String? = null) : AzureSignInResult()
}

data class AzureSignedInUser(
    val displayName: String,
    val username: String,
    val tenantId: String
)

interface AzureF0Provisioner {
    suspend fun signIn(): AzureSignInResult
    suspend fun signOut()
    suspend fun isSignedIn(): Boolean
    suspend fun getSignedInUser(): AzureSignedInUser?
    suspend fun getSubscriptions(): List<AzureSubscription>
    suspend fun getF0Resources(subscriptionId: String): List<AzureF0Resource>
    suspend fun getAccessToken(): String?
}
