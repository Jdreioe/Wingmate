package io.github.jdreioe.wingmate.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.application.FeatureUsageEvents
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.application.reportEvent
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.infrastructure.AzureSpeechEndpoint
import io.github.jdreioe.wingmate.infrastructure.AzureSpeechEndpointResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import org.koin.compose.koinInject

import com.hojmoseit.wingmate.R
/** Azure Portal's Deploy to Azure link for the project-owned F0 ARM template. */
internal const val F0_PORTAL_TEMPLATE_URL =
    "https://portal.azure.com/#create/Microsoft.Template/uri/https%3A%2F%2Fraw.githubusercontent.com%2Fjdreioe%2Fwingmate%2Fmain%2Finfra%2Fazure-user-f0%2Fazuredeploy.json"

/** Azure Portal list filtered to Cognitive Services accounts, including Speech resources. */
internal const val AZURE_SPEECH_RESOURCES_URL =
    "https://portal.azure.com/#view/HubsExtension/BrowseResource/resourceType/Microsoft.CognitiveServices%2Faccounts"

/** Microsoft's free account sign-up, the first step for someone without an Azure subscription. */
internal const val AZURE_SIGN_UP_URL = "https://azure.microsoft.com/free/"

private enum class F0Step { GUIDE, CREDENTIALS, SUCCESS }

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
    val featureUsageReporter = koinInject<FeatureUsageReporter>()
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(F0Step.GUIDE) }
    var endpoint by remember { mutableStateOf("") }
    var subscriptionKey by remember { mutableStateOf("") }
    var saveError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun openPortal() {
        featureUsageReporter.reportEvent(FeatureUsageEvents.AZURE_F0_PORTAL_OPENED)
        uriHandler.openUri(F0_PORTAL_TEMPLATE_URL)
    }

    fun saveCredentials() {
        when {
            endpoint.isBlank() -> saveError = "endpoint"
            AzureSpeechEndpoint.parse(endpoint) is AzureSpeechEndpointResult.Invalid ->
                saveError = "endpoint"
            subscriptionKey.isBlank() -> saveError = "key"
            else -> scope.launch {
                featureUsageReporter.reportEvent(FeatureUsageEvents.AZURE_F0_CREDENTIALS_SUBMITTED)
                saving = true
                saveError = null
                val failure = runCatching {
                    withContext(Dispatchers.Default) {
                        // Credentials only: callers switch the engine (setup with the voice, Settings on done).
                        configRepository.saveSpeechConfig(
                            SpeechServiceConfig(endpoint.trim(), subscriptionKey.trim())
                        )
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

    /** Back steps through this page's own steps before leaving it. */
    fun goBack() {
        when (step) {
            F0Step.GUIDE -> onBack()
            F0Step.CREDENTIALS -> step = F0Step.GUIDE
            F0Step.SUCCESS -> onDone()
        }
    }
    BackHandler(enabled = step == F0Step.CREDENTIALS) { step = F0Step.GUIDE }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            when (step) {
                F0Step.GUIDE -> GuideStep(
                    onSignUp = { uriHandler.openUri(AZURE_SIGN_UP_URL) },
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
                            "endpoint" -> stringResource(R.string.azure_setup_error_endpoint)
                            "key" -> stringResource(R.string.azure_setup_error_key)
                            else -> stringResource(R.string.azure_setup_error_save, error)
                        }
                    },
                    onEndpointChange = { endpoint = it },
                    onKeyChange = { subscriptionKey = it },
                    onSave = ::saveCredentials
                )
                F0Step.SUCCESS -> SuccessStep(onDone)
            }
        }
        if (step != F0Step.SUCCESS) {
            SetupBackButton(onClick = ::goBack, modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp))
        }
        }
    }
}

/**
 * The whole Azure path in four numbered steps, each with the link it needs, plus the
 * fixes for the errors Azure most often shows at "Review + create". Someone who already
 * has a key goes straight to step 4.
 */
@Composable
private fun GuideStep(
    onSignUp: () -> Unit,
    onOpenPortal: () -> Unit,
    onOpenSpeechResources: () -> Unit,
    onEnterCredentials: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(R.string.azure_guide_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.azure_guide_computer), style = MaterialTheme.typography.bodyLarge)
        GuideStepCard(1, R.string.azure_guide_account_title, R.string.azure_guide_account_body, R.string.azure_guide_account_action, onSignUp)
        GuideStepCard(2, R.string.azure_guide_resource_title, R.string.azure_guide_resource_body, R.string.azure_guide_resource_action, onOpenPortal)
        GuideStepCard(3, R.string.azure_guide_key_title, R.string.azure_guide_key_body, R.string.azure_guide_key_action, onOpenSpeechResources)
        GuideStepCard(4, R.string.azure_guide_paste_title, null, R.string.azure_guide_paste_action, onEnterCredentials, primary = true)
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.azure_guide_errors_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.azure_guide_errors_body), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun GuideStepCard(
    number: Int,
    title: Int,
    body: Int?,
    action: Int,
    onAction: () -> Unit,
    primary: Boolean = false,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$number. ${stringResource(title)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            body?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
            val modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            if (primary) {
                Button(onClick = onAction, modifier = modifier) { Text(stringResource(action)) }
            } else {
                OutlinedButton(onClick = onAction, modifier = modifier) { Text(stringResource(action)) }
            }
        }
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
    val saveCredentialsDescription = stringResource(R.string.azure_setup_save_credentials)
    Column {
        Text(stringResource(R.string.azure_setup_credentials_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.azure_setup_credentials_description), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = endpoint,
            onValueChange = onEndpointChange,
            label = { Text(stringResource(R.string.azure_setup_endpoint_label)) },
            placeholder = { Text(stringResource(R.string.azure_setup_endpoint_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = subscriptionKey,
            onValueChange = onKeyChange,
            label = { Text(stringResource(R.string.azure_setup_key_label)) },
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
            else Text(stringResource(R.string.azure_setup_save_continue))
        }
    }
}

@Composable
private fun SuccessStep(onDone: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(60.dp))
        Text(stringResource(R.string.azure_setup_complete_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.azure_setup_complete_description), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(32.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_continue)) }
    }
}
