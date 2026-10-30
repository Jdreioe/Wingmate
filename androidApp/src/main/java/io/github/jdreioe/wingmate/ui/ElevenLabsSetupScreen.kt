package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.application.SpeechFacade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@Composable
fun ElevenLabsSetupScreen(onDone: () -> Unit, onBack: () -> Unit) {
    val facade = koinInject<SpeechFacade>()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var key by remember { mutableStateOf("") }
    var configured by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(facade) {
        try { configured = facade.getElevenLabsSpeechConfig().credentialConfigured }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.elevenlabs_setup), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.elevenlabs_description))
        Text(stringResource(R.string.elevenlabs_help))
        OutlinedButton(onClick = { uri.openUri("https://elevenlabs.io/app/settings/api-keys") }) {
            Text(stringResource(R.string.elevenlabs_key))
        }
        if (configured) Text(stringResource(R.string.elevenlabs_configured))
        OutlinedTextField(value = key, onValueChange = { key = it; failed = false }, enabled = !busy,
            label = { Text(stringResource(R.string.elevenlabs_key)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        if (failed) Text(stringResource(R.string.elevenlabs_error), color = MaterialTheme.colorScheme.error)
        if (busy) CircularProgressIndicator()
        Button(enabled = !busy && key.isNotBlank(), onClick = {
            scope.launch {
                busy = true; failed = false
                try {
                    withContext(Dispatchers.Default) { facade.saveValidatedElevenLabsSpeechConfig(key) }
                    key = ""; configured = true; onDone()
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { failed = true }
                finally { busy = false }
            }
        }) { Text(stringResource(R.string.elevenlabs_save)) }
        if (configured) TextButton(enabled = !busy, onClick = {
            scope.launch {
                busy = true; failed = false
                try { facade.clearElevenLabsSpeechConfig(); configured = false; key = "" }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { failed = true }
                finally { busy = false }
            }
        }) { Text(stringResource(R.string.elevenlabs_clear)) }
        TextButton(onClick = onBack, enabled = !busy) { Text(stringResource(R.string.common_back)) }
    }
}
