package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import org.slf4j.LoggerFactory

@Composable
fun AzureSettingsFullScreen(onNext: () -> Unit, onCancel: () -> Unit) {
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
            LoggerFactory.getLogger("AzureSettingsFullScreen").info("Loaded config: {}", cfg)
            cfg?.let { endpoint = it.endpoint; subscriptionKey = it.subscriptionKey }
        }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Azure TTS Settings", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(12.dp))
        if (loading) {
            CircularProgressIndicator()
        } else if (configRepo == null) {
            Text("Config repository not available")
        } else {
            OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, label = { Text("Region / Endpoint") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = subscriptionKey, onValueChange = { subscriptionKey = it }, label = { Text("Subscription Key") }, modifier = Modifier.fillMaxWidth())
        }

        Spacer(modifier = Modifier.height(16.dp))

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                scope.launch {
                    if (configRepo != null) {
                        withContext(Dispatchers.Default) {
                            configRepo.saveSpeechConfig(SpeechServiceConfig(endpoint = endpoint, subscriptionKey = subscriptionKey))
                        }
                    }
                    onNext()
                }
            }) { Text("Next") }
        }
    }
}
