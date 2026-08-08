package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.jdreioe.wingmate.domain.UserDataManager
import io.github.jdreioe.wingmate.platform.ShareService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import org.koin.compose.koinInject

import com.hojmoseit.wingmate.R
@Composable
fun SettingsExportDialog(
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current

    val userDataManager = koinInject<UserDataManager>()
    val shareService = koinInject<ShareService>()

    var statusMessage by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf("") }
    var isExporting by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }

    val exportingLabel = stringResource(R.string.data_management_status_exporting)
    val exportReadyLabel = stringResource(R.string.data_management_status_export_ready)
    val copiedToClipboardLabel = stringResource(R.string.data_management_status_copied_clipboard)
    val exportFailedTemplate = stringResource(R.string.data_management_status_export_failed)
    val importingLabel = stringResource(R.string.data_management_status_importing)
    val importSuccessLabel = stringResource(R.string.data_management_status_import_success)
    val importInvalidJsonLabel = stringResource(R.string.data_management_status_import_failed_invalid)

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    stringResource(R.string.data_management_title),
                    style = MaterialTheme.typography.headlineSmall
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    stringResource(R.string.data_management_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // === EXPORT SECTION ===
                Text(stringResource(R.string.data_management_export), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = {
                        isExporting = true
                        statusMessage = exportingLabel
                        scope.launch(Dispatchers.Default) {
                            try {
                                val json = userDataManager.exportData()
                                try {
                                    // Try native share first
                                    shareService.shareText(json)
                                    statusMessage = exportReadyLabel
                                } catch (e: Exception) {
                                    // Fallback to clipboard
                                    clipboard.setClipEntry(plainTextClipEntry(json))
                                    statusMessage = copiedToClipboardLabel
                                }
                            } catch (e: Exception) {
                                statusMessage = String.format(exportFailedTemplate, e.message ?: "")
                            } finally {
                                isExporting = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isExporting
                ) {
                    Text(if (isExporting) exportingLabel else stringResource(R.string.data_management_button_share_copy_history))
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // === IMPORT SECTION ===
                HorizontalDivider()
                Spacer(modifier = Modifier.height(24.dp))
                
                Text(stringResource(R.string.data_management_import), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                
                val showKeyboard = rememberShowKeyboardOnFocus()
                OutlinedTextField(
                    value = importText,
                    onValueChange = { importText = it },
                    label = { Text(stringResource(R.string.data_management_paste_json_label)) },
                    modifier = Modifier.fillMaxWidth().height(120.dp).then(showKeyboard),
                    maxLines = 10
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = {
                        if (importText.isBlank()) return@Button
                        isImporting = true
                        statusMessage = importingLabel
                        scope.launch(Dispatchers.Default) {
                            try {
                                userDataManager.importData(importText)
                                statusMessage = importSuccessLabel
                                importText = ""
                            } catch (e: Exception) {
                                statusMessage = importInvalidJsonLabel
                            } finally {
                                isImporting = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isImporting && importText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (isImporting) importingLabel else stringResource(R.string.data_management_button_overwrite_history))
                }
                
                if (statusMessage.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        statusMessage,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(androidx.compose.ui.Alignment.End)
                ) {
                    Text(stringResource(R.string.common_close))
                }
            }
        }
    }
}
