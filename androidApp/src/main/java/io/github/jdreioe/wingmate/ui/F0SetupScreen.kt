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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.runtime.saveable.rememberSaveable
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

private enum class F0Step { GUIDE, CREDENTIALS }

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

    // Saved so a rotation or font-size change keeps the page; the key itself is not saved.
    var step by rememberSaveable { mutableStateOf(F0Step.GUIDE) }
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
                    // Finish here, like Google and ElevenLabs: the caller switches the engine.
                    onDone()
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
                )
                F0Step.CREDENTIALS -> CredentialsStep(
                    endpoint = endpoint,
                    subscriptionKey = subscriptionKey,
                    error = saveError?.let { error ->
                        when (error) {
                            "endpoint" -> stringResource(R.string.azure_setup_error_endpoint)
                            "key" -> stringResource(R.string.azure_setup_error_key)
                            else -> stringResource(R.string.azure_setup_error_save, error)
                        }
                    },
                    onEndpointChange = { endpoint = it },
                    onKeyChange = { subscriptionKey = it },
                )
            }
        }
        SetupBottomBar(
            onBack = ::goBack,
            primaryLabel = when (step) {
                // Pinned like Continue, so someone who already has a key never scrolls for it.
                F0Step.GUIDE -> stringResource(R.string.azure_guide_paste_action)
                F0Step.CREDENTIALS -> stringResource(R.string.common_save)
            },
            onPrimary = { if (step == F0Step.GUIDE) step = F0Step.CREDENTIALS else saveCredentials() },
            primaryBusy = saving,
        )
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
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(R.string.azure_guide_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.azure_guide_computer), style = MaterialTheme.typography.bodyLarge)
        GuideStepCard(1, R.string.azure_guide_account_title, R.string.azure_guide_account_body, R.string.azure_guide_account_action, onSignUp)
        GuideStepCard(2, R.string.azure_guide_resource_title, R.string.azure_guide_resource_body, R.string.azure_guide_resource_action, onOpenPortal)
        GuideStepCard(3, R.string.azure_guide_key_title, R.string.azure_guide_key_body, R.string.azure_guide_key_action, onOpenSpeechResources)
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                "4. ${stringResource(R.string.azure_guide_paste_title)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(20.dp),
            )
        }
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
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$number. ${stringResource(title)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            body?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
            SetupActionButton(label = stringResource(action), onClick = onAction, opensBrowser = true)
        }
    }
}

/** Pasting what step 3 copied, in the same order: KEY 1, then the region. */
@Composable
private fun CredentialsStep(
    endpoint: String,
    subscriptionKey: String,
    error: String?,
    onEndpointChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(R.string.azure_setup_credentials_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.azure_setup_credentials_description), style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = subscriptionKey,
            onValueChange = onKeyChange,
            label = { Text(stringResource(R.string.azure_setup_key_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        OutlinedTextField(
            value = endpoint,
            onValueChange = onEndpointChange,
            label = { Text(stringResource(R.string.azure_setup_endpoint_label)) },
            placeholder = { Text(stringResource(R.string.azure_setup_endpoint_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        if (error != null) {
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
