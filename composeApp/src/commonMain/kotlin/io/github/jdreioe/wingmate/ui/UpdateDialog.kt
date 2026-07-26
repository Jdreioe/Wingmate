package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import wingmatekmp.composeapp.generated.resources.*

@Composable
fun UpdateDialog(
    updateInfo: UpdateInfo,
    updateStatus: UpdateStatus,
    onDismiss: () -> Unit,
    onInstallUpdate: (UpdateInfo) -> Unit,
    onCheckForUpdates: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val noReleaseNotes = stringResource(Res.string.update_no_release_notes)
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                stringResource(Res.string.update_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            ) 
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Version info
                Text(
                    stringResource(Res.string.update_version_available, updateInfo.version.version),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // File info
                Text(
                    stringResource(Res.string.update_file, updateInfo.assetName),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    stringResource(Res.string.update_size, formatFileSize(updateInfo.assetSize)),
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Release notes
                Text(
                    stringResource(Res.string.update_release_notes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = updateInfo.releaseNotes.ifBlank { noReleaseNotes },
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Status indicator
                when (updateStatus) {
                    UpdateStatus.DOWNLOADING -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(Res.string.update_downloading))
                        }
                    }
                    UpdateStatus.DOWNLOADED -> {
                        Text(
                            stringResource(Res.string.update_download_complete),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    UpdateStatus.INSTALLING -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(Res.string.update_installing))
                        }
                    }
                    UpdateStatus.ERROR -> {
                        Text(
                            stringResource(Res.string.update_failed),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    else -> { /* No status message for other states */ }
                }
            }
        },
        confirmButton = {
            val buttonText = when (updateStatus) {
                UpdateStatus.AVAILABLE -> stringResource(Res.string.update_install)
                UpdateStatus.DOWNLOADED -> stringResource(Res.string.update_install_now)
                UpdateStatus.DOWNLOADING, UpdateStatus.INSTALLING -> stringResource(Res.string.update_wait)
                UpdateStatus.ERROR -> stringResource(Res.string.common_retry)
                else -> stringResource(Res.string.update_install)
            }
            
            Button(
                onClick = {
                    scope.launch {
                        onInstallUpdate(updateInfo)
                    }
                },
                enabled = updateStatus in listOf(
                    UpdateStatus.AVAILABLE,
                    UpdateStatus.DOWNLOADED,
                    UpdateStatus.ERROR
                )
            ) {
                Text(buttonText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.update_later))
            }
        }
    )
}

@Composable
fun UpdateNotificationCard(
    updateInfo: UpdateInfo,
    onShowDetails: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(Res.string.update_available),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        stringResource(Res.string.update_version_ready, updateInfo.version.version),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                
                Row {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(Res.string.update_dismiss))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onShowDetails) {
                        Text(stringResource(Res.string.update_view_details))
                    }
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    
    while (value >= 1024 && unitIndex < units.size - 1) {
        value /= 1024
        unitIndex++
    }
    
    return "%.1f %s".format(value, units[unitIndex])
}
