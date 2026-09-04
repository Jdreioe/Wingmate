package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.application.SettingsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import io.github.jdreioe.wingmate.ui.isDesktop
import androidx.compose.ui.res.stringResource

import com.hojmoseit.wingmate.R
@Composable
fun AzureSettingsFullScreen(
    onNext: () -> Unit, 
    onCancel: () -> Unit,
    onAzureSelected: () -> Unit = {}
) {
    val configRepo = koinInject<ConfigRepository>()
    val settingsUseCase = koinInject<SettingsUseCase>()
    
    var endpoint by remember { mutableStateOf("") }
    var subscriptionKey by remember { mutableStateOf("") }
    var credentialConfigured by remember { mutableStateOf(false) }
    var replacingCredentials by remember { mutableStateOf(false) }
    var ttsEngine by remember { mutableStateOf(TtsEngine.SYSTEM) }
    var loading by remember { mutableStateOf(true) }
    var virtualMic by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(configRepo, settingsUseCase) {
        val cfg = withContext(Dispatchers.Default) { configRepo.getSpeechConfigStatus() }
        OperationalLogger.debug("speech_config.load", "succeeded", enabled = cfg.credentialConfigured)
        endpoint = cfg.endpoint
        credentialConfigured = cfg.credentialConfigured

        val settings = withContext(Dispatchers.Default) { 
            runCatching { settingsUseCase.get() }.getOrNull() ?: Settings()
        }
        ttsEngine = settings.ttsEngine
        virtualMic = settings.virtualMicEnabled

        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(24.dp)
    ) {
        Text(stringResource(R.string.speech_settings_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(12.dp))
        
        if (loading) {
            CircularProgressIndicator()
        } else {
            
            // Pros and Cons Comparison
            Spacer(modifier = Modifier.height(16.dp))
            
            // Azure TTS Card
            Card(
                onClick = { 
                    ttsEngine = TtsEngine.AZURE_USER_RESOURCE
                    scope.launch {
                        val currentSettings: Settings = withContext(Dispatchers.Default) {
                            runCatching { settingsUseCase.get() }.getOrNull() ?: Settings()
                        }
                        runCatching {
                            settingsUseCase.update(currentSettings.copy(ttsEngine = TtsEngine.AZURE_USER_RESOURCE))
                        }
                        // Navigate to Azure config screen
                        onAzureSelected()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (ttsEngine != TtsEngine.SYSTEM) 
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                    else 
                        MaterialTheme.colorScheme.surfaceContainer
                ),
                border = if (ttsEngine != TtsEngine.SYSTEM) 
                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                else null
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Text(
                            stringResource(R.string.voice_engine_azure_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        if (ttsEngine != TtsEngine.SYSTEM) {
                            Spacer(Modifier.width(8.dp))
                            AssistChip(
                                onClick = { },
                                label = { Text(stringResource(R.string.common_selected), style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    
                    Text(
                        stringResource(R.string.voice_engine_pros),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(stringResource(R.string.voice_engine_azure_pros), style = MaterialTheme.typography.bodySmall)
                    
                    Spacer(Modifier.height(8.dp))
                    
                    Text(
                        stringResource(R.string.voice_engine_cons),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(stringResource(R.string.voice_engine_azure_cons), style = MaterialTheme.typography.bodySmall)
                }
            }
            
            Spacer(Modifier.height(12.dp))
            
            // System TTS Card  
            Card(
                onClick = { 
                    ttsEngine = TtsEngine.SYSTEM
                    scope.launch {
                        val currentSettings: Settings = withContext(Dispatchers.Default) {
                            runCatching { settingsUseCase.get() }.getOrNull() ?: Settings()
                        }
                        runCatching {
                            settingsUseCase.update(currentSettings.copy(ttsEngine = TtsEngine.SYSTEM))
                        }
                        // Go directly to voice selection since no config needed
                        onNext()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (ttsEngine == TtsEngine.SYSTEM) 
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                    else 
                        MaterialTheme.colorScheme.surfaceContainer
                ),
                border = if (ttsEngine == TtsEngine.SYSTEM) 
                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                else null
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Text(
                            stringResource(R.string.voice_engine_system_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        if (ttsEngine == TtsEngine.SYSTEM) {
                            Spacer(Modifier.width(8.dp))
                            AssistChip(
                                onClick = { },
                                label = { Text(stringResource(R.string.common_selected), style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    
                    Text(
                        stringResource(R.string.voice_engine_pros),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(stringResource(R.string.voice_engine_system_pros), style = MaterialTheme.typography.bodySmall)
                    
                    Spacer(Modifier.height(8.dp))
                    
                    Text(
                        stringResource(R.string.voice_engine_cons),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(stringResource(R.string.voice_engine_system_cons), style = MaterialTheme.typography.bodySmall)
                }
            }
            
            Spacer(Modifier.height(16.dp))
            
            // Recommendation Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.voice_engine_recommendation_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.voice_engine_recommendation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            
            // Reserved for desktop builds.
            if (isDesktop()) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = virtualMic, onCheckedChange = { checked -> virtualMic = checked })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(stringResource(R.string.ui_settings_virtual_mic_title))
                        Text(
                            stringResource(R.string.ui_settings_virtual_mic_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Azure Configuration (only show when Azure TTS is selected)
            if (ttsEngine != TtsEngine.SYSTEM) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(R.string.azure_config_title), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                AzureCredentialEditor(
                    credentialConfigured = credentialConfigured,
                    replacingCredentials = replacingCredentials,
                    endpoint = endpoint,
                    onEndpointChange = { endpoint = it },
                    subscriptionKey = subscriptionKey,
                    onSubscriptionKeyChange = { subscriptionKey = it },
                    onReplaceCredentials = {
                        replacingCredentials = true
                        subscriptionKey = ""
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                scope.launch {
                    // Save TTS preference
                    withContext(Dispatchers.Default) {
                        val current = runCatching { settingsUseCase.get() }.getOrNull() ?: Settings()
                        val updated = current.copy(ttsEngine = ttsEngine, virtualMicEnabled = virtualMic)
                        settingsUseCase.update(updated)
                    }

                    // Save Azure config only if Azure TTS is selected and fields are filled
                    if (ttsEngine != TtsEngine.SYSTEM &&
                        (!credentialConfigured || replacingCredentials) &&
                        endpoint.isNotBlank() && subscriptionKey.isNotBlank()
                    ) {
                        withContext(Dispatchers.Default) {
                            configRepo.saveSpeechConfig(SpeechServiceConfig(endpoint = endpoint, subscriptionKey = subscriptionKey))
                        }
                    }
                    onNext()
                }
            }) { Text(stringResource(R.string.common_next)) }
        }
    }
}
