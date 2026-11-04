package io.github.jdreioe.wingmate.ui

import android.text.format.Formatter
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.domain.PiperDownloadFailure
import io.github.jdreioe.wingmate.domain.PiperVoiceState
import org.koin.compose.getKoin

/** The voice picker for [io.github.jdreioe.wingmate.domain.TtsEngine.PIPER]: download and choose offline voices. */
@Composable
internal fun PiperVoicesPage(onVoiceChosen: () -> Unit, modifier: Modifier = Modifier) {
    val koin = getKoin()
    val viewModel: PiperVoicesViewModel = viewModel(
        factory = remember(koin) {
            viewModelFactory {
                initializer {
                    PiperVoicesViewModel(
                        DefaultPiperVoiceOperations(
                            installer = koin.get(),
                            synthesizer = koin.get(),
                            voiceUseCase = koin.get(),
                            settingsUseCase = koin.get(),
                        )
                    )
                }
            }
        }
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnChosen by rememberUpdatedState(onVoiceChosen)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                PiperVoicesEvent.VoiceChosen -> currentOnChosen()
            }
        }
    }
    DisposableEffect(viewModel) { onDispose { viewModel.stopSample() } }
    PiperVoicesContent(state = state, onAction = viewModel::onAction, modifier = modifier)
}

@Composable
private fun PiperVoicesContent(
    state: PiperVoicesUiState,
    onAction: (PiperVoicesAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = state as? PiperVoicesUiState.Ready
    if (ready == null) {
        Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val context = LocalContext.current
    var deleting by remember { mutableStateOf<PiperVoiceRow?>(null) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.piper_description),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (ready.selectFailed) {
            Text(stringResource(R.string.voice_save_failed), color = MaterialTheme.colorScheme.error)
        }

        if (ready.onDevice.isNotEmpty()) {
            SettingsGroup(title = stringResource(R.string.piper_on_device)) {
                ready.onDevice.forEachIndexed { index, row ->
                    PiperVoiceRowItem(row, ready, onAction, onDelete = { deleting = row })
                    if (index < ready.onDevice.lastIndex) SettingsGroupDivider()
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = ready.languageFilter == null,
                onClick = { onAction(PiperVoicesAction.LanguageFiltered(null)) },
                label = { Text(stringResource(R.string.voice_all_languages)) },
            )
            ready.languages.forEach { language ->
                FilterChip(
                    selected = ready.languageFilter == language,
                    onClick = { onAction(PiperVoicesAction.LanguageFiltered(language)) },
                    label = { Text(localizedLocaleDisplayName(language)) },
                )
            }
        }

        SettingsGroup(title = stringResource(R.string.piper_available)) {
            if (ready.onDevice.isEmpty()) {
                Text(
                    stringResource(
                        R.string.piper_first_download_note,
                        Formatter.formatShortFileSize(context, ready.sharedDataBytes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            if (ready.available.isEmpty()) {
                Text(
                    stringResource(R.string.piper_none_for_language),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            ready.available.forEachIndexed { index, row ->
                PiperVoiceRowItem(row, ready, onAction, onDelete = {})
                if (index < ready.available.lastIndex) SettingsGroupDivider()
            }
        }

        Text(
            stringResource(R.string.piper_attribution),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    deleting?.let { row ->
        val name = row.pack.toVoice().displayName.orEmpty()
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.piper_delete_title, name)) },
            text = {
                Text(stringResource(R.string.piper_delete_message, Formatter.formatShortFileSize(context, row.pack.sizeBytes)))
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onAction(PiperVoicesAction.Delete(row.pack.key))
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun PiperVoiceRowItem(
    row: PiperVoiceRow,
    ready: PiperVoicesUiState.Ready,
    onAction: (PiperVoicesAction) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val pack = row.pack
    val key = pack.key
    val name = pack.toVoice().displayName.orEmpty()
    val size = Formatter.formatShortFileSize(context, pack.sizeBytes)
    // Spoken button labels name the voice, since every row has the same buttons.
    val downloadLabel = stringResource(R.string.piper_download_voice, name)
    val cancelLabel = stringResource(R.string.piper_cancel_voice, name)
    val useLabel = stringResource(R.string.piper_use_voice, name)
    val deleteLabel = stringResource(R.string.piper_delete_voice, name)
    val playing = ready.testingKey == key
    val playLabel = stringResource(if (playing) R.string.piper_stop_sample else R.string.piper_play_sample, name)

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { heading() })
                Text(
                    stringResource(R.string.piper_details, localizedLocaleDisplayName(pack.languageTag), size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (ready.selectedKey == key) {
                    Text(stringResource(R.string.voice_selected), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                }
                if (ready.unusableKey == key) {
                    Text(stringResource(R.string.piper_unusable), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                if (ready.testFailedKey == key) {
                    Text(
                        stringResource(R.string.piper_sample_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            // Hear the voice's recorded sample, before or after download, like the other voice lists.
            FilledTonalIconButton(
                onClick = { onAction(PiperVoicesAction.Test(key)) },
                modifier = Modifier.padding(start = 8.dp).size(56.dp).semantics { contentDescription = playLabel },
            ) {
                Icon(if (playing) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
            }
        }

        when (val state = row.state) {
            PiperVoiceState.NotInstalled -> Button(
                onClick = { onAction(PiperVoicesAction.Download(key)) },
                modifier = Modifier.semantics { contentDescription = downloadLabel },
            ) { Text(stringResource(R.string.piper_download)) }

            is PiperVoiceState.Downloading -> {
                LinearProgressIndicator(
                    progress = { (state.downloadedBytes.toFloat() / state.totalBytes.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                Text(
                    stringResource(
                        R.string.piper_progress,
                        Formatter.formatShortFileSize(context, state.downloadedBytes),
                        Formatter.formatShortFileSize(context, state.totalBytes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = { onAction(PiperVoicesAction.Cancel(key)) },
                    modifier = Modifier.semantics { contentDescription = cancelLabel },
                ) { Text(stringResource(R.string.common_cancel)) }
            }

            is PiperVoiceState.Incomplete -> {
                Text(
                    stringResource(
                        when (state.failure) {
                            null -> R.string.piper_interrupted
                            PiperDownloadFailure.Network -> R.string.piper_failed_network
                            PiperDownloadFailure.Damaged -> R.string.piper_failed_damaged
                            PiperDownloadFailure.Storage -> R.string.piper_failed_storage
                        }
                    ),
                    color = if (state.failure == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onAction(PiperVoicesAction.Download(key)) },
                        modifier = Modifier.semantics { contentDescription = downloadLabel },
                    ) {
                        Text(stringResource(if (state.downloadedBytes > 0) R.string.piper_resume else R.string.piper_try_again))
                    }
                    OutlinedButton(
                        onClick = { onAction(PiperVoicesAction.Cancel(key)) },
                        modifier = Modifier.semantics { contentDescription = cancelLabel },
                    ) { Text(stringResource(R.string.common_cancel)) }
                }
            }

            PiperVoiceState.Installed -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ready.selectedKey != key) {
                    val preparing = ready.preparingKey == key
                    Button(
                        onClick = { onAction(PiperVoicesAction.Use(key)) },
                        enabled = ready.preparingKey == null,
                        modifier = Modifier.semantics { contentDescription = useLabel },
                    ) { Text(stringResource(if (preparing) R.string.piper_loading else R.string.piper_use)) }
                }
                OutlinedButton(
                    onClick = onDelete,
                    enabled = ready.preparingKey != key,
                    modifier = Modifier.semantics { contentDescription = deleteLabel },
                ) { Text(stringResource(R.string.common_delete)) }
            }
        }
    }
}
