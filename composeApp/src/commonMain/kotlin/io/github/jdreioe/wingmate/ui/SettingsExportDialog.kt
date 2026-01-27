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
import org.koin.core.context.GlobalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

@Composable
fun SettingsExportDialog(
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    
    val userDataManager = remember { GlobalContext.get().get<UserDataManager>() }
    val shareService = remember { GlobalContext.get().get<ShareService>() }

    var statusMessage by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf("") }
    var isExporting by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }

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
                    "Data Management", 
                    style = MaterialTheme.typography.headlineSmall
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    "Export or import your speech history and trained model data as JSON.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // === EXPORT SECTION ===
                Text("Export", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = {
                        isExporting = true
                        statusMessage = "Exporting..."
                        scope.launch(Dispatchers.Default) {
                            try {
                                val json = userDataManager.exportData()
                                try {
                                    // Try native share first
                                    shareService.shareText(json)
                                    statusMessage = "Export ready (shared)"
                                } catch (e: Exception) {
                                    // Fallback to clipboard
                                    clipboardManager.setText(AnnotatedString(json))
                                    statusMessage = "Copied to clipboard!"
                                }
                            } catch (e: Exception) {
                                statusMessage = "Export failed: ${e.message}"
                            } finally {
                                isExporting = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isExporting
                ) {
                    Text(if (isExporting) "Exporting..." else "Share / Copy History")
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // === IMPORT SECTION ===
                Divider()
                Spacer(modifier = Modifier.height(24.dp))
                
                Text("Import", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = importText,
                    onValueChange = { importText = it },
                    label = { Text("Paste JSON here") },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    maxLines = 10
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = {
                        if (importText.isBlank()) return@Button
                        isImporting = true
                        statusMessage = "Importing..."
                        scope.launch(Dispatchers.Default) {
                            try {
                                userDataManager.importData(importText)
                                statusMessage = "Import successful! Restart app to see changes."
                                importText = ""
                            } catch (e: Exception) {
                                statusMessage = "Import failed: Invalid JSON"
                            } finally {
                                isImporting = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isImporting && importText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (isImporting) "Importing..." else "Overwrite History")
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
                    Text("Close")
                }
            }
        }
    }
}
