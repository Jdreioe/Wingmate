package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.application.SpeechFacade
import io.github.jdreioe.wingmate.infrastructure.GoogleApiRequestHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.getKoin

internal const val GOOGLE_TTS_API_URL =
    "https://console.cloud.google.com/apis/library/texttospeech.googleapis.com"
internal const val GOOGLE_API_CREDENTIALS_URL = "https://console.cloud.google.com/apis/credentials"
internal const val GOOGLE_CLOUD_BILLING_URL = "https://console.cloud.google.com/billing"

private enum class GoogleTtsSetupStep { WELCOME, CLOUD_PROJECT, API_KEY, SUCCESS }

@Composable
fun GoogleTtsSetupScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    showNavigation: Boolean = true,
) {
    val koin = getKoin()
    val speechFacade = remember(koin) { koin.get<SpeechFacade>() }
    val requestHeaders = remember(koin) {
        runCatching { koin.getOrNull<GoogleApiRequestHeaders>()?.values().orEmpty() }
            .getOrDefault(emptyMap())
    }
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(GoogleTtsSetupStep.WELCOME) }
    var apiKey by remember { mutableStateOf("") }
    var credentialConfigured by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    val setupFailure = stringResource(R.string.google_setup_error_validation)
    val packageName = requestHeaders["X-Android-Package"].orEmpty()
    val certificate = requestHeaders["X-Android-Cert"].orEmpty()
        .chunked(2)
        .joinToString(":")

    LaunchedEffect(speechFacade) {
        credentialConfigured = withContext(Dispatchers.Default) {
            runCatching { speechFacade.getGoogleSpeechConfig().credentialConfigured }
                .getOrDefault(false)
        }
    }

    fun saveAndValidate() {
        if (apiKey.isBlank()) {
            saveError = setupFailure
            return
        }
        scope.launch {
            saving = true
            saveError = null
            try {
                withContext(Dispatchers.Default) {
                    speechFacade.saveValidatedGoogleSpeechConfig(apiKey)
                }
                apiKey = ""
                credentialConfigured = true
                step = GoogleTtsSetupStep.SUCCESS
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                saveError = setupFailure
            } finally {
                saving = false
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (showNavigation) Modifier.statusBarsPadding() else Modifier)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            if (showNavigation) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {
                        when (step) {
                            GoogleTtsSetupStep.WELCOME -> onBack()
                            GoogleTtsSetupStep.CLOUD_PROJECT, GoogleTtsSetupStep.API_KEY ->
                                step = GoogleTtsSetupStep.WELCOME
                            GoogleTtsSetupStep.SUCCESS -> onDone()
                        }
                    }) {
                        Text(stringResource(if (step == GoogleTtsSetupStep.WELCOME) R.string.common_cancel else R.string.common_back))
                    }
                    Text(stringResource(R.string.google_setup_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(64.dp))
                }
                Spacer(Modifier.height(24.dp))
            }
            when (step) {
                GoogleTtsSetupStep.WELCOME -> {
                    Text(
                        stringResource(R.string.google_setup_welcome_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.google_setup_welcome_description))
                    Spacer(Modifier.height(24.dp))
                    Card(
                        onClick = { step = GoogleTtsSetupStep.CLOUD_PROJECT },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Text(stringResource(R.string.google_setup_guided_title), fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.google_setup_guided_description))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedCard(
                        onClick = { step = GoogleTtsSetupStep.API_KEY },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Text(stringResource(R.string.google_setup_existing_title), fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.google_setup_existing_description))
                        }
                    }
                    if (credentialConfigured) {
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.google_tts_configured), color = MaterialTheme.colorScheme.primary)
                        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.common_continue))
                        }
                    }
                }

                GoogleTtsSetupStep.CLOUD_PROJECT -> {
                    Text(
                        stringResource(R.string.google_setup_cloud_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.google_setup_cloud_steps))
                    Spacer(Modifier.height(20.dp))
                    OutlinedButton(
                        onClick = { uriHandler.openUri(GOOGLE_CLOUD_BILLING_URL) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.google_setup_open_billing)) }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { uriHandler.openUri(GOOGLE_TTS_API_URL) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.google_setup_enable_api)) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { uriHandler.openUri(GOOGLE_API_CREDENTIALS_URL) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.google_setup_create_key)) }
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { step = GoogleTtsSetupStep.API_KEY },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.google_setup_have_key)) }
                }

                GoogleTtsSetupStep.API_KEY -> {
                    Text(
                        stringResource(R.string.google_setup_key_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.google_setup_key_description))
                    Spacer(Modifier.height(12.dp))
                    SelectionContainer {
                        Text(
                            stringResource(R.string.google_setup_android_restriction, packageName, certificate),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it; saveError = null },
                        label = { Text(stringResource(R.string.google_tts_api_key)) },
                        supportingText = { Text(stringResource(R.string.google_setup_secure_storage_android)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    saveError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = ::saveAndValidate,
                        enabled = apiKey.isNotBlank() && !saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.google_setup_save_validate))
                    }
                }

                GoogleTtsSetupStep.SUCCESS -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.height(60.dp))
                        Text(
                            stringResource(R.string.google_setup_complete_title),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.google_setup_complete_description))
                        Spacer(Modifier.height(32.dp))
                        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.common_continue))
                        }
                    }
                }
            }
        }
    }
}
