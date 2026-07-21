package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.AzureF0Provisioner
import io.github.jdreioe.wingmate.domain.AzureF0Resource
import io.github.jdreioe.wingmate.domain.AzureSignInResult
import io.github.jdreioe.wingmate.domain.AzureSubscription
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.SettingsRepository
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.domain.TtsEngine
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

class AutoF0FlowUseCase(
    private val provisioner: AzureF0Provisioner,
    private val armClient: AzureArmClient,
    private val configRepository: ConfigRepository,
    private val settingsRepository: SettingsRepository
) {
    suspend fun execute(): AutoF0FlowResult {
        val signInResult = provisioner.signIn()
        if (signInResult != AzureSignInResult.SUCCESS) {
            return AutoF0FlowResult.SignInFailed(signInResult)
        }

        val subscriptions = try {
            provisioner.getSubscriptions()
        } catch (e: Exception) {
            return AutoF0FlowResult.Error("Failed to list subscriptions: ${e.message}")
        }
        if (subscriptions.isEmpty()) {
            return AutoF0FlowResult.NoSubscriptions
        }

        val subscription = subscriptions.firstOrNull { it.state == "Enabled" } ?: subscriptions.first()

        val accessToken = getAccessTokenFromScope() ?: return AutoF0FlowResult.Error("Cannot acquire ARM token")

        val existingResources = try {
            armClient.listF0SpeechResources(accessToken, subscription.id)
        } catch (e: Exception) {
            return AutoF0FlowResult.Error("Failed to list Speech resources: ${e.message}")
        }
        if (existingResources.isNotEmpty()) {
            return reuseResource(accessToken, existingResources.first())
        }

        return createNewResource(accessToken, subscription)
    }

    private suspend fun reuseResource(accessToken: String, resource: AzureF0Resource): AutoF0FlowResult {
        return importKeysAndSave(accessToken, resource, resource.region)
    }

    private suspend fun createNewResource(accessToken: String, subscription: AzureSubscription): AutoF0FlowResult {
        val region = "northeurope"

        val isRegistered = try {
            armClient.isProviderRegistered(accessToken, subscription.id)
        } catch (_: Exception) { false }
        if (!isRegistered) {
            val registered = try { armClient.registerProvider(accessToken, subscription.id) } catch (_: Exception) { false }
            if (!registered) return AutoF0FlowResult.ProviderRegistrationFailed
        }

        val resourceGroups = try {
            armClient.listResourceGroups(accessToken, subscription.id)
        } catch (e: Exception) {
            return AutoF0FlowResult.Error("Failed to list resource groups: ${e.message}")
        }

        val resourceGroup = if (resourceGroups.contains("wingmate-speech-rg")) {
            "wingmate-speech-rg"
        } else {
            try {
                armClient.createResourceGroup(accessToken, subscription.id, "wingmate-speech-rg", region)
                "wingmate-speech-rg"
            } catch (_: Exception) {
                resourceGroups.firstOrNull() ?: return AutoF0FlowResult.Error("No resource group available")
            }
        }

        val deploymentName = "wingmate-f0-deploy-${kotlinx.datetime.Clock.System.now().toEpochMilliseconds()}"
        try {
            armClient.startDeployment(
                accessToken = accessToken,
                subscriptionId = subscription.id,
                resourceGroup = resourceGroup,
                deploymentName = deploymentName,
                template = F0Template.templateJson,
                location = region
            )
        } catch (e: Exception) {
            val msg = e.message ?: ""
            return if (msg.contains("Conflict") || msg.contains("F0") || msg.contains("already exists")) {
                AutoF0FlowResult.ExistingF0Conflict
            } else {
                AutoF0FlowResult.Error("Deployment failed: $msg")
            }
        }

        val deployedResource = pollDeployment(accessToken, subscription.id, resourceGroup, deploymentName)
        if (deployedResource == null) {
            return AutoF0FlowResult.Error("Deployment did not complete in time")
        }
        return importKeysAndSave(accessToken, deployedResource, region)
    }

    private suspend fun pollDeployment(
        accessToken: String,
        subscriptionId: String,
        resourceGroup: String,
        deploymentName: String
    ): AzureF0Resource? {
        var attempts = 0
        while (attempts < 12) {
            try {
                val status = armClient.getDeploymentStatus(accessToken, subscriptionId, resourceGroup, deploymentName)
                if (status.isSucceeded) {
                    val resourceId = extractOutputValue(status.outputs, "resourceId") ?: continue
                    val name = extractOutputValue(status.outputs, "name") ?: continue
                    val region = extractOutputValue(status.outputs, "region") ?: continue
                    return AzureF0Resource(resourceId, name, region, resourceGroup)
                }
                if (status.isFailed) return null
            } catch (_: Exception) { }
            attempts++
            delay(5000)
        }
        return null
    }

    private suspend fun importKeysAndSave(accessToken: String, resource: AzureF0Resource, region: String): AutoF0FlowResult {
        val keys = try {
            armClient.getResourceKeys(accessToken, resource.id)
        } catch (e: Exception) {
            return AutoF0FlowResult.Error("Failed to retrieve keys: ${e.message}")
        }
        val key1 = keys?.key1
        if (key1.isNullOrBlank()) {
            return AutoF0FlowResult.Error("No key returned from Azure")
        }
        try {
            configRepository.saveSpeechConfig(SpeechServiceConfig(endpoint = region, subscriptionKey = key1))
            val current = runCatching { settingsRepository.get() }.getOrNull()
            if (current != null && current.ttsEngine != TtsEngine.AZURE_USER_RESOURCE) {
                settingsRepository.update(current.copy(ttsEngine = TtsEngine.AZURE_USER_RESOURCE))
            }
        } catch (e: Exception) {
            return AutoF0FlowResult.Error("Failed to save credentials: ${e.message}")
        }
        return AutoF0FlowResult.Success(resource.name, resource.region)
    }

    /**
     * Acquire an ARM access token by triggering a getSubscriptions call.
     * The provisioner caches the token via MSAL internally.
     * For the ARM client we need the raw token — we store it via a side channel.
     *
     * This is a simplified approach; in production the token should be retrieved
     * from MSAL's IAuthenticationResult directly.
     */
    private suspend fun getAccessTokenFromScope(): String? {
        return try {
            provisioner.getSubscriptions()
            // Token acquisition happened as a side effect
            // In I-06 we'll plumb the actual token through
            "arm-token-placeholder"
        } catch (_: Exception) {
            null
        }
    }

    private fun extractOutputValue(outputs: JsonElement?, key: String): String? {
        if (outputs == null) return null
        val obj = outputs.jsonObject
        val entry = obj[key]?.jsonObject ?: return null
        return entry["value"]?.jsonPrimitive?.contentOrNull
    }
}

sealed class AutoF0FlowResult {
    data class Success(val resourceName: String, val region: String) : AutoF0FlowResult()
    data class SignInFailed(val reason: AzureSignInResult) : AutoF0FlowResult()
    object NoSubscriptions : AutoF0FlowResult()
    object ExistingF0Conflict : AutoF0FlowResult()
    object ProviderRegistrationFailed : AutoF0FlowResult()
    data class Error(val message: String) : AutoF0FlowResult()
}
