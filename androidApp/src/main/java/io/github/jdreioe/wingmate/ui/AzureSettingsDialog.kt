package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@Composable
fun AzureSettingsDialog(show: Boolean, onDismiss: () -> Unit, onSaved: (() -> Unit)? = null) {
    if (!show) return

    // get the ConfigRepository from Koin in a safe way
    val configRepo = remember {
        org.koin.core.context.GlobalContext.getOrNull()?.let { koin ->
            runCatching { koin.get<ConfigRepository>() }.getOrNull()
        }
    }

    // Log which ConfigRepository implementation we got (helps diagnose persistence)
    if (configRepo == null) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text("Azure Settings") }, text = { Text("Config repository not available") }, confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        })
        return
    }

    var endpoint by remember { mutableStateOf("") }
    var subscriptionKey by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        // load existing config
        val cfg = withContext(Dispatchers.Default) { configRepo.getSpeechConfig() }

        cfg?.let {
            endpoint = it.endpoint
            subscriptionKey = it.subscriptionKey
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Speech settings") },
        text = {
            Column {

                OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, label = { Text("Region / Endpoint (e.g. westus) or full endpoint") })
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = subscriptionKey, onValueChange = { subscriptionKey = it }, label = { Text("Subscription Key") })
            }
        },
        confirmButton = {
            val scope = rememberCoroutineScope()
            Button(onClick = {
                // save
                scope.launch {
                    withContext(Dispatchers.Default) {
                        try {
                            configRepo.saveSpeechConfig(SpeechServiceConfig(endpoint = endpoint, subscriptionKey = subscriptionKey))

                        } catch (t: Throwable) {

                            throw t
                        }
                    }
                    onSaved?.invoke()
                    onDismiss()
                }
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
