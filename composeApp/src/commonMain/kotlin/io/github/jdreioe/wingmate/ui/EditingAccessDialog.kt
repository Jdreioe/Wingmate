package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.application.EditingAccessController
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import wingmatekmp.composeapp.generated.resources.Res
import wingmatekmp.composeapp.generated.resources.*

internal enum class EditingAccessDialogMode { Unlock, Configure, Disable }

@Composable
internal fun EditingAccessDialog(
    controller: EditingAccessController,
    mode: EditingAccessDialogMode,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    var code by remember(mode) { mutableStateOf("") }
    var confirmation by remember(mode) { mutableStateOf("") }
    var error by remember(mode) { mutableStateOf<String?>(null) }
    var working by remember(mode) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val validCode = code.length in 4..8 && code.all(Char::isDigit)
    val canSubmit = validCode && (mode != EditingAccessDialogMode.Configure || code == confirmation) && !working

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = {
            Text(stringResource(when (mode) {
                EditingAccessDialogMode.Unlock -> Res.string.editing_access_unlock_title
                EditingAccessDialogMode.Configure -> Res.string.editing_access_setup_title
                EditingAccessDialogMode.Disable -> Res.string.editing_access_disable_title
            }))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.editing_access_code_help))
                OutlinedTextField(
                    value = code,
                    onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) code = it },
                    label = { Text(stringResource(Res.string.editing_access_code)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (mode == EditingAccessDialogMode.Configure) {
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) confirmation = it },
                        label = { Text(stringResource(Res.string.editing_access_confirm_code)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                error?.let { Text(it) }
            }
        },
        confirmButton = {
            val incorrect = stringResource(Res.string.editing_access_incorrect)
            TextButton(
                enabled = canSubmit,
                onClick = {
                    working = true
                    scope.launch {
                        val success = when (mode) {
                            EditingAccessDialogMode.Unlock -> controller.unlock(code)
                            EditingAccessDialogMode.Configure -> runCatching { controller.configure(code) }.isSuccess
                            EditingAccessDialogMode.Disable -> controller.disable(code)
                        }
                        working = false
                        if (success) onSuccess() else error = incorrect
                    }
                }
            ) { Text(stringResource(Res.string.common_ok)) }
        },
        dismissButton = {
            TextButton(enabled = !working, onClick = onDismiss) {
                Text(stringResource(Res.string.common_cancel))
            }
        }
    )
}
