package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.AzureF0Resource
import io.github.jdreioe.wingmate.domain.AzureSubscription
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Client for Azure Resource Manager REST API.
 * Requires an access token obtained via MSAL (or other OAuth flow).
 */
class AzureArmClient(private val httpClient: HttpClient) {

    private val armBaseUrl = "https://management.azure.com"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * List all Azure subscriptions accessible to the signed-in user.
     */
    suspend fun listSubscriptions(accessToken: String): List<AzureSubscription> {
        val response: HttpResponse = httpClient.get("$armBaseUrl/subscriptions") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            parameter("api-version", "2020-01-01")
        }
        if (!response.status.isSuccess()) {
            throw RuntimeException("Failed to list subscriptions: ${response.status}")
        }
        val body = response.body<SubscriptionListResponse>()
        return body.value.map { it.toDomain() }
    }

    /**
     * List F0 Speech resources in a subscription.
     */
    suspend fun listF0SpeechResources(accessToken: String, subscriptionId: String): List<AzureF0Resource> {
        val response: HttpResponse = httpClient.get(
            "$armBaseUrl/subscriptions/$subscriptionId/providers/Microsoft.CognitiveServices/accounts"
        ) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            parameter("api-version", "2024-10-01")
        }
        if (!response.status.isSuccess()) {
            throw RuntimeException("Failed to list Speech resources: ${response.status}")
        }
        val body = response.body<CognitiveAccountsListResponse>()
        return body.value
            .filter { it.kind == "SpeechServices" && it.sku?.name == "F0" }
            .map { it.toDomain(subscriptionId) }
    }

    /**
     * Get details of a specific Speech resource.
     */
    suspend fun getResourceKeys(accessToken: String, resourceId: String): ResourceKeys? {
        val response: HttpResponse = httpClient.post("$armBaseUrl$resourceId/listKeys") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            parameter("api-version", "2024-10-01")
        }
        if (!response.status.isSuccess()) {
            if (response.status.value == 403) return null
            throw RuntimeException("Failed to list keys: ${response.status}")
        }
        return response.body<ResourceKeys>()
    }

    @Serializable
    private data class SubscriptionListResponse(
        val value: List<SubscriptionDto>
    )

    @Serializable
    private data class SubscriptionDto(
        val subscriptionId: String,
        val displayName: String,
        val tenantId: String? = null,
        val state: String = "Enabled"
    ) {
        fun toDomain() = AzureSubscription(
            id = subscriptionId,
            name = displayName,
            tenantId = tenantId ?: "",
            state = state
        )
    }

    @Serializable
    private data class CognitiveAccountsListResponse(
        val value: List<CognitiveAccountDto>
    )

    @Serializable
    private data class CognitiveAccountDto(
        val id: String? = null,
        val name: String? = null,
        val location: String? = null,
        val kind: String? = null,
        val sku: SkuDto? = null,
        val properties: PropertiesDto? = null
    ) {
        fun toDomain(subscriptionId: String) = AzureF0Resource(
            id = id ?: "",
            name = name ?: "",
            region = location ?: "",
            resourceGroup = extractResourceGroup(id)
        )

        private fun extractResourceGroup(resourceId: String?): String {
            if (resourceId == null) return ""
            // /subscriptions/{sub}/resourceGroups/{rg}/providers/...
            val parts = resourceId.split("/")
            val rgIndex = parts.indexOf("resourceGroups")
            return if (rgIndex >= 0 && rgIndex + 1 < parts.size) parts[rgIndex + 1] else ""
        }
    }

    @Serializable
    private data class SkuDto(
        val name: String? = null
    )

    @Serializable
    private data class PropertiesDto(
        val publicNetworkAccess: String? = null
    )
}

@Serializable
data class ResourceKeys(
    val key1: String? = null,
    val key2: String? = null
)
