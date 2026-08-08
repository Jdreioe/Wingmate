package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.application.SettingsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import androidx.compose.ui.res.stringResource

import com.hojmoseit.wingmate.R
@Composable
fun VoiceEngineSelectorScreen(
    onNext: () -> Unit, 
    onCancel: () -> Unit,
    onAzureSelected: () -> Unit = {}
) {
    val settingsUseCase = koinInject<SettingsUseCase>()

    var ttsEngine by remember { mutableStateOf(TtsEngine.SYSTEM) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(settingsUseCase) {
        val settings = withContext(Dispatchers.Default) {
            runCatching { settingsUseCase.get() }.getOrNull() ?: Settings()
        }
        ttsEngine = settings.ttsEngine

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
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Text(
                stringResource(R.string.voice_engine_choose),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
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

                Spacer(modifier = Modifier.height(24.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.common_cancel))
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                // Save the selected TTS engine setting
                                val currentSettings: Settings = withContext(Dispatchers.Default) {
                                    runCatching { settingsUseCase.get() }.getOrNull() ?: Settings()
                                }
                                runCatching {
                                    settingsUseCase.update(currentSettings.copy(ttsEngine = ttsEngine))
                                }

                                // Navigate to appropriate next screen based on selection
                                if (ttsEngine == TtsEngine.SYSTEM) {
                                    onNext() // Go directly to voice selection
                                } else {
                                    onAzureSelected() // Go to Azure configuration
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.common_next))
                    }
                }
            }
        }
    }
}
