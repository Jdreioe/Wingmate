package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.FeatureUsageEvents
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.application.reportEvent
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.domain.TtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import wingmatekmp.composeapp.generated.resources.*

/** Azure Portal's Deploy to Azure link for the project-owned F0 ARM template. */
internal const val F0_PORTAL_TEMPLATE_URL =
    "https://portal.azure.com/#create/Microsoft.Template/uri/https%3A%2F%2Fraw.githubusercontent.com%2Fjdreioe%2Fwingmate%2Fmain%2Finfra%2Fazure-user-f0%2Fazuredeploy.json"

/** Azure Portal list filtered to Cognitive Services accounts, including Speech resources. */
internal const val AZURE_SPEECH_RESOURCES_URL =
    "https://portal.azure.com/#view/HubsExtension/BrowseResource/resourceType/Microsoft.CognitiveServices%2Faccounts"

private enum class F0Step { WELCOME, PORTAL, CREDENTIALS, SUCCESS }

/**
 * Sets up the free Speech tier through Azure Portal instead of acquiring an ARM token.
 * This works for personal Microsoft accounts, while direct ARM delegated access does not.
 */
@Composable
fun F0SetupScreen(
    onDone: () -> Unit,
    onBack: () -> Unit
) {
    val configRepository = koinInject<ConfigRepository>()
    val settingsUseCase = koinInject<SettingsUseCase>()
    val featureUsageReporter = koinInject<FeatureUsageReporter>()
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(F0Step.WELCOME) }
    var endpoint by remember { mutableStateOf("") }
    var subscriptionKey by remember { mutableStateOf("") }
    var saveError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun openPortal() {
        featureUsageReporter.reportEvent(FeatureUsageEvents.AZURE_F0_PORTAL_OPENED)
        uriHandler.openUri(F0_PORTAL_TEMPLATE_URL)
        step = F0Step.PORTAL
    }

    fun saveCredentials() {
        when {
            endpoint.isBlank() -> saveError = "endpoint"
            subscriptionKey.isBlank() -> saveError = "key"
            else -> scope.launch {
                featureUsageReporter.reportEvent(FeatureUsageEvents.AZURE_F0_CREDENTIALS_SUBMITTED)
                saving = true
                saveError = null
                val failure = runCatching {
                    withContext(Dispatchers.Default) {
                        configRepository.saveSpeechConfig(
                            SpeechServiceConfig(endpoint.trim(), subscriptionKey.trim())
                        )
                        val settings = runCatching { settingsUseCase.get() }.getOrDefault(Settings())
                        settingsUseCase.update(settings.copy(ttsEngine = TtsEngine.AZURE_USER_RESOURCE))
                    }
                }.exceptionOrNull()
                saving = false
                if (failure == null) {
                    featureUsageReporter.reportEvent(FeatureUsageEvents.AZURE_F0_SETUP_COMPLETED)
                    step = F0Step.SUCCESS
                } else {
                    featureUsageReporter.reportEvent(FeatureUsageEvents.AZURE_F0_SETUP_FAILED, "reason" to "save_failed")
                    saveError = failure.message ?: "unknown"
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        featureUsageReporter.reportEvent(FeatureUsageEvents.AZURE_F0_SETUP_STARTED)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        when (step) {
                            F0Step.WELCOME -> onBack()
                            F0Step.PORTAL -> step = F0Step.WELCOME
                            F0Step.CREDENTIALS -> step = F0Step.WELCOME
                            F0Step.SUCCESS -> onDone()
                        }
                    }
                ) { Text(stringResource(if (step == F0Step.WELCOME) Res.string.common_cancel else Res.string.common_back)) }
                Text(stringResource(Res.string.azure_setup_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(64.dp))
            }

            Spacer(Modifier.height(24.dp))
            when (step) {
                F0Step.WELCOME -> WelcomeStep(onOpenPortal = ::openPortal, onEnterCredentials = { step = F0Step.CREDENTIALS })
                F0Step.PORTAL -> PortalStep(
                    onOpenPortal = ::openPortal,
                    onOpenSpeechResources = {
                        featureUsageReporter.reportEvent(FeatureUsageEvents.AZURE_F0_RESOURCES_OPENED)
                        uriHandler.openUri(AZURE_SPEECH_RESOURCES_URL)
                    },
                    onEnterCredentials = { step = F0Step.CREDENTIALS }
                )
                F0Step.CREDENTIALS -> CredentialsStep(
                    endpoint = endpoint,
                    subscriptionKey = subscriptionKey,
                    saving = saving,
                    error = saveError?.let { error ->
                        when (error) {
                            "endpoint" -> stringResource(Res.string.azure_setup_error_endpoint)
                            "key" -> stringResource(Res.string.azure_setup_error_key)
                            else -> stringResource(Res.string.azure_setup_error_save, error)
                        }
                    },
                    onEndpointChange = { endpoint = it },
                    onKeyChange = { subscriptionKey = it },
                    onSave = ::saveCredentials
                )
                F0Step.SUCCESS -> SuccessStep(onDone)
            }
        }
    }
}

@Composable
private fun WelcomeStep(onOpenPortal: () -> Unit, onEnterCredentials: () -> Unit) {
    Column {
        Text(stringResource(Res.string.azure_setup_free_tier_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(Res.string.azure_setup_free_tier_description),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))
        Card(onClick = onOpenPortal, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(Res.string.azure_setup_portal_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(Res.string.azure_setup_portal_description),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedCard(onClick = onEnterCredentials, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(Res.string.azure_setup_existing_key_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(Res.string.azure_setup_existing_key_description), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PortalStep(
    onOpenPortal: () -> Unit,
    onOpenSpeechResources: () -> Unit,
    onEnterCredentials: () -> Unit
) {
    Column {
        Text(stringResource(Res.string.azure_setup_create_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(Res.string.azure_setup_in_portal), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(Res.string.azure_setup_portal_steps),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onOpenPortal, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.azure_setup_open_portal)) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenSpeechResources, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.azure_setup_open_resources)) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onEnterCredentials, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.azure_setup_have_credentials)) }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(Res.string.azure_setup_f0_limit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CredentialsStep(
    endpoint: String,
    subscriptionKey: String,
    saving: Boolean,
    error: String?,
    onEndpointChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onSave: () -> Unit
) {
    val saveCredentialsDescription = stringResource(Res.string.azure_setup_save_credentials)
    Column {
        Text(stringResource(Res.string.azure_setup_credentials_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(Res.string.azure_setup_credentials_description), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = endpoint,
            onValueChange = onEndpointChange,
            label = { Text(stringResource(Res.string.azure_setup_endpoint_label)) },
            placeholder = { Text(stringResource(Res.string.azure_setup_endpoint_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = subscriptionKey,
            onValueChange = onKeyChange,
            label = { Text(stringResource(Res.string.azure_setup_key_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onSave, enabled = !saving, modifier = Modifier.fillMaxWidth().semantics { contentDescription = saveCredentialsDescription }) {
            if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(stringResource(Res.string.azure_setup_save_continue))
        }
    }
}

@Composable
private fun SuccessStep(onDone: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(60.dp))
        Text(stringResource(Res.string.azure_setup_complete_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(Res.string.azure_setup_complete_description), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(32.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.common_continue)) }
    }
}
