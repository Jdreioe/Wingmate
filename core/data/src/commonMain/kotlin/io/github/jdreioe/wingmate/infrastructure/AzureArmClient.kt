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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Client for Azure Resource Manager REST API.
 * Requires an access token obtained via MSAL (or other OAuth flow).
 */
class AzureArmClient(private val httpClient: HttpClient) {

    private val armBaseUrl = "https://management.azure.com"
    private val json = Json { ignoreUnknownKeys = true }

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

    suspend fun listResourceGroups(accessToken: String, subscriptionId: String): List<String> {
        val response: HttpResponse = httpClient.get(
            "$armBaseUrl/subscriptions/$subscriptionId/resourceGroups"
        ) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            parameter("api-version", "2021-04-01")
        }
        if (!response.status.isSuccess()) {
            throw RuntimeException("Failed to list resource groups: ${response.status}")
        }
        val body = response.body<ResourceGroupListResponse>()
        return body.value.map { it.name }
    }

    suspend fun createResourceGroup(accessToken: String, subscriptionId: String, name: String, location: String) {
        val body = """{"location":"$location"}"""
        val response: HttpResponse = httpClient.put(
            "$armBaseUrl/subscriptions/$subscriptionId/resourceGroups/$name"
        ) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header(HttpHeaders.ContentType, "application/json")
            parameter("api-version", "2021-04-01")
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            throw RuntimeException("Failed to create resource group: ${response.status}")
        }
    }

    suspend fun registerProvider(accessToken: String, subscriptionId: String): Boolean {
        val response: HttpResponse = httpClient.post(
            "$armBaseUrl/subscriptions/$subscriptionId/providers/Microsoft.CognitiveServices/register"
        ) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            parameter("api-version", "2021-04-01")
        }
        if (response.status.value == 403) return false
        if (!response.status.isSuccess()) {
            throw RuntimeException("Failed to register provider: ${response.status}")
        }
        return true
    }

    suspend fun isProviderRegistered(accessToken: String, subscriptionId: String): Boolean {
        val response: HttpResponse = httpClient.get(
            "$armBaseUrl/subscriptions/$subscriptionId/providers/Microsoft.CognitiveServices"
        ) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            parameter("api-version", "2021-04-01")
        }
        if (!response.status.isSuccess()) return false
        val body = response.body<ProviderResponse>()
        return body.registrationState == "Registered"
    }

    suspend fun startDeployment(
        accessToken: String,
        subscriptionId: String,
        resourceGroup: String,
        deploymentName: String,
        template: String,
        location: String
    ) {
        val bodyStr = buildString {
            appendLine("{")
            appendLine("  \"properties\": {")
            appendLine("    \"mode\": \"Incremental\",")
            appendLine("    \"template\": $template,")
            appendLine("    \"parameters\": {")
            appendLine("      \"location\": { \"value\": \"$location\" }")
            appendLine("    }")
            appendLine("  }")
            appendLine("}")
        }
        val response: HttpResponse = httpClient.put(
            "$armBaseUrl/subscriptions/$subscriptionId/resourceGroups/$resourceGroup/providers/Microsoft.Resources/deployments/$deploymentName"
        ) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header(HttpHeaders.ContentType, "application/json")
            parameter("api-version", "2021-04-01")
            setBody(bodyStr)
        }
        if (!response.status.isSuccess()) {
            val responseBody = response.bodyAsText()
            throw RuntimeException("Failed to start deployment: ${response.status} - ${responseBody.take(500)}")
        }
    }

    suspend fun getDeploymentStatus(
        accessToken: String,
        subscriptionId: String,
        resourceGroup: String,
        deploymentName: String
    ): DeploymentStatus {
        val response: HttpResponse = httpClient.get(
            "$armBaseUrl/subscriptions/$subscriptionId/resourceGroups/$resourceGroup/providers/Microsoft.Resources/deployments/$deploymentName"
        ) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            parameter("api-version", "2021-04-01")
        }
        if (!response.status.isSuccess()) {
            throw RuntimeException("Failed to get deployment status: ${response.status}")
        }
        val body = response.body<DeploymentResponse>()
        return DeploymentStatus(
            provisioningState = body.properties?.provisioningState ?: "Unknown",
            outputs = body.properties?.outputs
        )
    }

    // ── DTOs ──

    @Serializable
    private data class SubscriptionListResponse(val value: List<SubscriptionDto>)

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
    private data class CognitiveAccountsListResponse(val value: List<CognitiveAccountDto>)

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
            val parts = resourceId.split("/")
            val rgIndex = parts.indexOf("resourceGroups")
            return if (rgIndex >= 0 && rgIndex + 1 < parts.size) parts[rgIndex + 1] else ""
        }
    }

    @Serializable
    private data class SkuDto(val name: String? = null)

    @Serializable
    private data class PropertiesDto(val publicNetworkAccess: String? = null)

    @Serializable
    private data class ResourceGroupListResponse(val value: List<ResourceGroupDto>)

    @Serializable
    private data class ResourceGroupDto(val name: String)

    @Serializable
    private data class ProviderResponse(val registrationState: String = "NotRegistered")
}

@Serializable
private data class DeploymentBody(
    val properties: DeploymentProperties
)

@Serializable
private data class DeploymentProperties(
    val mode: String,
    val template: JsonElement,
    val parameters: JsonObject
)

@Serializable
private data class DeploymentResponse(
    val properties: DeploymentPropertiesResponse? = null
)

@Serializable
private data class DeploymentPropertiesResponse(
    val provisioningState: String = "Unknown",
    val outputs: JsonElement? = null
)

@Serializable
data class ResourceKeys(
    val key1: String? = null,
    val key2: String? = null
)

data class DeploymentStatus(
    val provisioningState: String,
    val outputs: JsonElement? = null
) {
    val isRunning get() = provisioningState in listOf("Running", "Accepted")
    val isSucceeded get() = provisioningState == "Succeeded"
    val isFailed get() = provisioningState in listOf("Failed", "Canceled")
}
