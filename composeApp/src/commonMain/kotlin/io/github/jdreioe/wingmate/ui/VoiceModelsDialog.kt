package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.chatterbox.ChatterboxModel
import io.github.jdreioe.wingmate.domain.chatterbox.ModelRepository
import io.github.jdreioe.wingmate.domain.chatterbox.ModelDownloader
import io.github.jdreioe.wingmate.domain.chatterbox.ModelInstallationStatus
import io.github.jdreioe.wingmate.infrastructure.chatterbox.OfficialModelRegistry
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun VoiceModelsDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    modelRepository: ModelRepository = koinInject(),
    modelDownloader: ModelDownloader = koinInject(),
) {
    if (!show) return

    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<ChatterboxModel>>(emptyList()) }
    var activeModel by remember { mutableStateOf<ChatterboxModel?>(null) }
    var loading by remember { mutableStateOf(true) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }

    LaunchedEffect(show) {
        loading = true
        val installed = modelRepository.list()
        val official = OfficialModelRegistry.models
        val merged = official.map { officialModel ->
            val status = modelDownloader.installationStatus(officialModel.id)
            (installed.find { it.id == officialModel.id } ?: officialModel).copy(
                isInstalled = status is ModelInstallationStatus.Installed,
                storagePath = (status as? ModelInstallationStatus.Installed)?.storagePath,
            )
        }
        models = merged
        activeModel = modelRepository.getActive()
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .fillMaxHeight(0.8f),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Memory, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text("Voice Models", style = MaterialTheme.typography.headlineSmall)
            }
        },
        text = {
            if (loading) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(models, key = { it.id }) { model ->
                        ModelCard(
                            model = model,
                            isActive = activeModel?.id == model.id,
                            isDownloading = downloading == model.id,
                            progress = progress,
                            onActivate = {
                                scope.launch {
                                    if (modelDownloader.installationStatus(model.id) is ModelInstallationStatus.Installed) {
                                        modelRepository.setActive(model)
                                        activeModel = model
                                    }
                                }
                            },
                            onDownload = {
                                scope.launch {
                                    downloading = model.id
                                    progress = 0f
                                    modelDownloader.downloadModel(model.id) { progress = it }.onSuccess {
                                        val status = modelDownloader.installationStatus(model.id)
                                        val saved = model.copy(
                                            isInstalled = status is ModelInstallationStatus.Installed,
                                            storagePath = (status as? ModelInstallationStatus.Installed)?.storagePath,
                                        )
                                        modelRepository.save(saved)
                                        models = models.map { if (it.id == model.id) saved else it }
                                    }
                                    downloading = null
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun ModelCard(
    model: ChatterboxModel,
    isActive: Boolean,
    isDownloading: Boolean,
    progress: Float,
    onActivate: () -> Unit,
    onDownload: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isActive) {
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        } else {
            CardDefaults.elevatedCardColors()
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "v${model.version}  |  ${formatSize(model.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (model.languages.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = model.languages.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            if (isDownloading) {
                Column(horizontalAlignment = Alignment.End) {
                    CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(24.dp))
                    Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                }
            } else if (model.isInstalled) {
                if (isActive) {
                    FilledTonalButton(onClick = onActivate) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Active")
                    }
                } else {
                    OutlinedButton(onClick = onActivate) {
                        Text("Activate")
                    }
                }
            } else {
                FilledTonalButton(onClick = onDownload) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Download")
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }
}
