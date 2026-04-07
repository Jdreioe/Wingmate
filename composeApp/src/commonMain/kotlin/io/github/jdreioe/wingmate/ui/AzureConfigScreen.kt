package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

@Composable
fun AzureConfigScreen(onNext: () -> Unit, onBack: () -> Unit) {
    val configRepo = remember {
        GlobalContext.getOrNull()?.let { koin -> runCatching { koin.get<ConfigRepository>() }.getOrNull() }
    }
    
    var endpoint by remember { mutableStateOf("") }
    var subscriptionKey by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(configRepo) {
        if (configRepo != null) {
            val cfg = withContext(Dispatchers.Default) { configRepo.getSpeechConfig() }
            cfg?.let { 
                endpoint = it.endpoint
                subscriptionKey = it.subscriptionKey
            }
        }
        loading = false
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(24.dp)
        ) {
            Text(
                "Azure TTS Configuration", 
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            "Configure your Azure Cognitive Services settings to use high-quality neural voices.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(24.dp))

        if (loading) {
            CircularProgressIndicator()
        } else {
            // Endpoint Input
            val showKeyboard = rememberShowKeyboardOnFocus()
            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                label = { Text("Azure Endpoint") },
                placeholder = { Text("https://your-region.api.cognitive.microsoft.com/") },
                modifier = Modifier.fillMaxWidth().then(showKeyboard),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Subscription Key Input
            OutlinedTextField(
                value = subscriptionKey,
                onValueChange = { subscriptionKey = it },
                label = { Text("Subscription Key") },
                placeholder = { Text("Your Azure subscription key") },
                modifier = Modifier.fillMaxWidth().then(showKeyboard),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Info Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "ℹ️ How to get Azure credentials:",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "1. Go to Azure portal (portal.azure.com)\n" +
                        "2. Create a 'Speech Services' resource\n" +
                        "3. Copy the endpoint URL and subscription key\n" +
                        "4. Paste them in the fields above",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Back")
                }

                Button(
                    onClick = {
                        scope.launch {
                            configRepo?.let { repo ->
                                val config = SpeechServiceConfig(
                                    endpoint = endpoint,
                                    subscriptionKey = subscriptionKey
                                )
                                withContext(Dispatchers.Default) {
                                    runCatching { repo.saveSpeechConfig(config) }
                                }
                            }
                            onNext()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = endpoint.isNotBlank() && subscriptionKey.isNotBlank()
                ) {
                    Text("Continue")
                }
            }
        }
        }
    }
}
