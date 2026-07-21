package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.infrastructure.AutoF0FlowResult
import io.github.jdreioe.wingmate.infrastructure.AutoF0FlowUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private enum class F0Step {
    WELCOME,
    SIGNING_IN,
    PROVISIONING,
    SUCCESS,
    ERROR
}

@Composable
fun F0SetupScreen(
    onDone: () -> Unit,
    onManualByok: () -> Unit,
    onBack: () -> Unit
) {
    val flowUseCase = koinInject<AutoF0FlowUseCase>()

    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var currentStep by remember { mutableStateOf(F0Step.WELCOME) }
    var statusMessage by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var resultResourceName by remember { mutableStateOf("") }
    var resultRegion by remember { mutableStateOf("") }

    val stepAnnouncement = when (currentStep) {
        F0Step.WELCOME -> "Azure TTS setup. Choose automatic setup or manual key entry."
        F0Step.SIGNING_IN -> "Signing in to your Microsoft account."
        F0Step.PROVISIONING -> statusMessage
        F0Step.SUCCESS -> "Setup complete. Your Azure TTS is ready."
        F0Step.ERROR -> "Setup failed. $errorMessage"
    }

    fun launchAutoFlow() {
        scope.launch {
            statusMessage = "Signing in..."
            currentStep = F0Step.SIGNING_IN

            val result = withContext(Dispatchers.IO) { flowUseCase.execute() }
            println("F0SetupFlow: result=$result")
            when (result) {
                is AutoF0FlowResult.Success -> {
                    resultResourceName = result.resourceName
                    resultRegion = result.region
                    currentStep = F0Step.SUCCESS
                }
                is AutoF0FlowResult.SignInFailed -> {
                    println("F0SetupFlow: Sign-in failed, reason=${result.reason}")
                    errorMessage = "Sign-in was cancelled or failed. Please try again."
                    currentStep = F0Step.ERROR
                }
                is AutoF0FlowResult.NoSubscriptions -> {
                    println("F0SetupFlow: No subscriptions found")
                    errorMessage = "No Azure subscriptions found. Create a free Azure account at azure.com/free."
                    currentStep = F0Step.ERROR
                }
                is AutoF0FlowResult.ExistingF0Conflict -> {
                    println("F0SetupFlow: Existing F0 conflict")
                    errorMessage = "An F0 Speech resource already exists in your subscription. Use the manual key entry option to connect it."
                    currentStep = F0Step.ERROR
                }
                is AutoF0FlowResult.ProviderRegistrationFailed -> {
                    println("F0SetupFlow: Provider registration failed")
                    errorMessage = "Could not register the Speech provider. You may need admin permissions on your Azure subscription."
                    currentStep = F0Step.ERROR
                }
                is AutoF0FlowResult.Error -> {
                    println("F0SetupFlow: Error - ${result.message}")
                    errorMessage = result.message
                    currentStep = F0Step.ERROR
                }
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(scrollState)
                .padding(24.dp)
        ) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentStep != F0Step.WELCOME && currentStep != F0Step.SIGNING_IN) {
                    TextButton(onClick = onBack) { Text("Back") }
                } else {
                    TextButton(onClick = onBack) { Text("Cancel") }
                }
                Text(
                    "Azure TTS Setup",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.width(64.dp)) // Balance the top row
            }

            Spacer(Modifier.height(24.dp))

            when (currentStep) {
                F0Step.WELCOME -> WelcomeStep(
                    onAutoSetup = { launchAutoFlow() },
                    onManualByok = onManualByok
                )

                F0Step.SIGNING_IN -> SigningInStep(statusMessage)

                F0Step.PROVISIONING -> ProvisioningStep(statusMessage)

                F0Step.SUCCESS -> SuccessStep(
                    resourceName = resultResourceName,
                    region = resultRegion,
                    onDone = onDone
                )

                F0Step.ERROR -> ErrorStep(
                    message = errorMessage ?: "Unknown error",
                    onRetry = {
                        currentStep = F0Step.WELCOME
                        errorMessage = null
                    },
                    onManualByok = onManualByok,
                    onCancel = onBack
                )
            }
        }
    }
}

@Composable
private fun WelcomeStep(onAutoSetup: () -> Unit, onManualByok: () -> Unit) {
    Column {
        Text(
            "High-Quality Voice, Free Tier",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Azure Text-to-Speech provides 400+ neural voices in 140+ languages. " +
            "The free (F0) tier includes 500,000 characters per month — enough for daily use.",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.height(24.dp))

        Card(
            onClick = onAutoSetup,
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "⚡ Set up automatically",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Sign in with your Microsoft account. Wingmate will find or create " +
                    "a free Speech resource for you — no manual configuration needed.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "You'll need: a Microsoft account and an Azure subscription " +
                    "(free subscription available at azure.com/free).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedCard(
            onClick = onManualByok,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "🔑 Enter key manually",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Already have an Azure Speech resource? Enter your endpoint and " +
                    "subscription key directly.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "💡 About Azure Free Tier",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "500,000 characters/month • No credit card required for first subscription " +
                    "• Cancel anytime • Your keys stay on your device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
private fun SigningInStep(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(80.dp))
        CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = "Signing in" })
        Spacer(Modifier.height(24.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "A browser window will open for Microsoft sign-in.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ProvisioningStep(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(80.dp))
        CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = message })
        Spacer(Modifier.height(24.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "This may take up to a minute.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SuccessStep(resourceName: String, region: String, onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(60.dp))
        Text(
            "✅",
            style = MaterialTheme.typography.displayLarge
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "All Set!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Your Azure Speech resource is ready.",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Resource: $resourceName", style = MaterialTheme.typography.bodyMedium)
                Text("Region: $region", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your subscription key has been securely saved on this device. " +
                    "You can now choose from 400+ neural voices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(Modifier.height(32.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text("Continue")
        }
    }
}

@Composable
private fun ErrorStep(
    message: String,
    onRetry: () -> Unit,
    onManualByok: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(60.dp))
        Text(
            "⚠️",
            style = MaterialTheme.typography.displayLarge
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Something Went Wrong",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(24.dp))

        if (message.contains("409") || message.contains("Conflict") || message.contains("already exists")) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Only one F0 Speech resource per subscription is allowed. " +
                        "You may already have one — check the Azure Portal or enter your existing key manually.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text("Try Again")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onManualByok, modifier = Modifier.fillMaxWidth()) {
            Text("Enter Key Manually")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}
