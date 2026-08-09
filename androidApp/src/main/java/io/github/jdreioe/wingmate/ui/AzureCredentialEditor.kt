package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hojmoseit.wingmate.R

/** Never renders persisted credential values. Fields appear only for initial setup or replacement. */
@Composable
internal fun AzureCredentialEditor(
    credentialConfigured: Boolean,
    replacingCredentials: Boolean,
    endpoint: String,
    onEndpointChange: (String) -> Unit,
    subscriptionKey: String,
    onSubscriptionKeyChange: (String) -> Unit,
    onReplaceCredentials: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (credentialConfigured && !replacingCredentials) {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    text = stringResource(R.string.azure_credentials_configured),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = onReplaceCredentials,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.azure_credentials_replace))
                }
            }
        }
        return
    }

    val showKeyboard = rememberShowKeyboardOnFocus()
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = endpoint,
            onValueChange = onEndpointChange,
            label = { Text(stringResource(R.string.ui_settings_region_endpoint)) },
            placeholder = { Text(stringResource(R.string.ui_settings_region_example)) },
            modifier = Modifier.fillMaxWidth().then(showKeyboard),
            singleLine = true
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = subscriptionKey,
            onValueChange = onSubscriptionKeyChange,
            label = { Text(stringResource(R.string.ui_settings_subscription_key)) },
            placeholder = { Text(stringResource(R.string.azure_config_key_placeholder)) },
            modifier = Modifier.fillMaxWidth().then(showKeyboard),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true
        )
    }
}
