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
import org.koin.compose.koinInject
import androidx.compose.ui.res.stringResource

import com.hojmoseit.wingmate.R
@Composable
fun AzureConfigScreen(onNext: () -> Unit, onBack: () -> Unit) {
    val configRepo = koinInject<ConfigRepository>()
    
    var endpoint by remember { mutableStateOf("") }
    var subscriptionKey by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(configRepo) {
        val cfg = withContext(Dispatchers.Default) { configRepo.getSpeechConfig() }
        cfg?.let {
            endpoint = it.endpoint
            subscriptionKey = it.subscriptionKey
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
                stringResource(R.string.azure_config_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            stringResource(R.string.azure_config_description),
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
                label = { Text(stringResource(R.string.azure_config_endpoint)) },
                placeholder = { Text(stringResource(R.string.azure_setup_endpoint_placeholder)) },
                modifier = Modifier.fillMaxWidth().then(showKeyboard),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Subscription Key Input
            OutlinedTextField(
                value = subscriptionKey,
                onValueChange = { subscriptionKey = it },
                label = { Text(stringResource(R.string.ui_settings_subscription_key)) },
                placeholder = { Text(stringResource(R.string.azure_config_key_placeholder)) },
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
                        stringResource(R.string.azure_config_help_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.azure_config_help_steps),
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
                    Text(stringResource(R.string.common_back))
                }

                Button(
                    onClick = {
                        scope.launch {
                            val config = SpeechServiceConfig(
                                endpoint = endpoint,
                                subscriptionKey = subscriptionKey
                            )
                            withContext(Dispatchers.Default) {
                                runCatching { configRepo.saveSpeechConfig(config) }
                            }
                            onNext()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = endpoint.isNotBlank() && subscriptionKey.isNotBlank()
                ) {
                    Text(stringResource(R.string.common_continue))
                }
            }
        }
        }
    }
}
