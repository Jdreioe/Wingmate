package io.github.jdreioe.wingmate.ui.nodes

import io.github.jdreioe.wingmate.domain.nodes.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import io.github.jdreioe.wingmate.domain.CommunicationPlaybackStatus
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.ui.AppTheme
import kotlin.math.roundToInt

@Composable
internal fun NodeWorkspaceRoot(onBack: () -> Unit, viewModel: NodeWorkspaceViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    NodeWorkspaceScreen(state, viewModel::onAction, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NodeWorkspaceScreen(state: NodeWorkspaceState, onAction: (NodeAction) -> Unit, onBack: () -> Unit) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun finishInput(action: NodeAction) {
        onAction(action)
        focus.clearFocus()
        keyboard?.hide()
    }
    Scaffold(topBar = {
        TopAppBar(title = {
            Text(state.library.active.name.ifEmpty { stringResource(R.string.nodes_title) }, maxLines = 1)
        }, navigationIcon = {
            TextButton(onClick = onBack) { Text(stringResource(R.string.nodes_back)) }
        }, actions = {
            TextButton(onClick = { onAction(NodeAction.ToggleCode) }, enabled = state.ready) { Text("SSML") }
            TextButton(onClick = { onAction(NodeAction.ShowWorkspaces(true)) }, enabled = state.ready) {
                Text(stringResource(R.string.nodes_workspaces))
            }
        })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 12.dp)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val storageText = when (state.storageStatus) {
                    NodeStorageStatus.Loading -> R.string.nodes_loading
                    NodeStorageStatus.Saved -> R.string.nodes_saved
                    NodeStorageStatus.Saving -> R.string.nodes_saving
                    NodeStorageStatus.LoadFailed -> R.string.nodes_load_failed
                    NodeStorageStatus.SaveFailed -> R.string.nodes_save_failed
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(storageText), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    if (state.storageStatus == NodeStorageStatus.LoadFailed || state.storageStatus == NodeStorageStatus.SaveFailed) {
                        TextButton(onClick = { onAction(NodeAction.RetryStorage) }) { Text(stringResource(R.string.common_retry)) }
                    }
                }
                NodeMessageBar(state, ::finishInput)
                NodeWorkspaceErrors(state)
                NodeWordPicker(state, onAction, ::finishInput, Modifier.fillMaxWidth())
                androidx.compose.runtime.key(state.library.activeId) { NodeCanvas(state, onAction, Modifier.weight(1f).fillMaxWidth()) }
                NodeCanvasActions(state, onAction)
            }
        }
    }
    if (state.showPause) AlertDialog(
        onDismissRequest = { onAction(NodeAction.ShowPause(false)) },
        title = { Text(stringResource(R.string.nodes_add_pause)) },
        text = { Column {
            OutlinedTextField(state.pauseInput, { onAction(NodeAction.PauseInput(it)) }, label = { Text(stringResource(R.string.nodes_pause_ms)) }, singleLine = true)
            state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { Button(onClick = { finishInput(NodeAction.AddPause) }) { Text(stringResource(R.string.nodes_add_pause)) } },
        dismissButton = { TextButton(onClick = { onAction(NodeAction.ShowPause(false)) }) { Text(stringResource(R.string.nodes_close)) } },
    )
    if (state.showCode) AlertDialog(
        onDismissRequest = { onAction(NodeAction.ToggleCode) },
        title = { Text("SSML") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.nodes_ssml_help), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(state.document.draft, { onAction(NodeAction.Draft(it)) }, Modifier.fillMaxWidth().height(200.dp),
                label = { Text(stringResource(R.string.nodes_ssml_code)) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { Button(onClick = { finishInput(NodeAction.ApplySsml) }, enabled = state.dirty) { Text(stringResource(R.string.nodes_apply)) } },
        dismissButton = { Row {
            TextButton(onClick = { onAction(NodeAction.RevertSsml) }, enabled = state.dirty) { Text(stringResource(R.string.nodes_revert)) }
            TextButton(onClick = { onAction(NodeAction.ToggleCode) }) { Text(stringResource(R.string.nodes_close)) }
        } },
    )
    if (state.showDictionary) AlertDialog(
        onDismissRequest = { onAction(NodeAction.ShowDictionary(false)) },
        title = { Text(stringResource(if (state.replacingWord) R.string.nodes_choose_replacement else R.string.nodes_dictionary)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(state.input, { onAction(NodeAction.Input(it)) }, label = { Text(stringResource(R.string.nodes_search)) }, singleLine = true)
            if (state.vocabulary.isEmpty()) Text(stringResource(R.string.nodes_no_matches))
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(state.vocabulary.sortedBy { it.key }, key = { it.key }) { word ->
                    TextButton(onClick = { finishInput(NodeAction.UseWord(word.key)) }, modifier = Modifier.fillMaxWidth()) {
                        Text(word.text, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                        Text(stringResource(R.string.nodes_frequency, state.frequencies[word.key] ?: 0), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        } },
        confirmButton = { TextButton(onClick = { onAction(NodeAction.ShowDictionary(false)) }) { Text(stringResource(R.string.nodes_close)) } },
        dismissButton = { TextButton(onClick = { finishInput(NodeAction.AddWords) }, enabled = state.input.isNotBlank() && (!state.replacingWord || !state.input.trim().contains(Regex("\\s")) )) {
            Text(stringResource(if (state.replacingWord) R.string.nodes_replace_new else R.string.nodes_add))
        } },
    )
    if (state.showWorkspaces) AlertDialog(
        onDismissRequest = { onAction(NodeAction.ShowWorkspaces(false)) },
        title = { Text(stringResource(R.string.nodes_workspaces)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LazyColumn(Modifier.heightIn(max = 240.dp)) {
                items(state.library.workspaces, key = { it.id }) { workspace ->
                    TextButton(onClick = { onAction(NodeAction.OpenWorkspace(workspace.id)) }, modifier = Modifier.fillMaxWidth()) {
                        Text(workspace.name.ifEmpty { stringResource(R.string.nodes_title) })
                    }
                }
            }
            OutlinedTextField(state.workspaceName, { onAction(NodeAction.WorkspaceName(it)) }, label = { Text(stringResource(R.string.nodes_workspace_name)) }, singleLine = true)
            TextButton(onClick = { onAction(NodeAction.RenameWorkspace) }, enabled = state.workspaceName.isNotBlank()) { Text(stringResource(R.string.nodes_rename)) }
        } },
        confirmButton = { Button(onClick = { onAction(NodeAction.CreateWorkspace) }, enabled = state.workspaceName.isNotBlank() && state.library.workspaces.size < 100) { Text(stringResource(R.string.nodes_create_workspace)) } },
        dismissButton = { TextButton(onClick = { onAction(NodeAction.ShowWorkspaces(false)) }) { Text(stringResource(R.string.nodes_close)) } },
    )
}

@Composable
private fun NodeMessageBar(state: NodeWorkspaceState, onAction: (NodeAction) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Text(state.preview.ifEmpty { stringResource(R.string.nodes_empty) }, Modifier.fillMaxWidth().heightIn(max = 64.dp).verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onAction(NodeAction.Speak) }, enabled = state.ready && !state.dirty && !state.resolvingVoice && state.sentence.any { it.content is SentenceContent.Word }) {
                    Text(stringResource(R.string.nodes_speak))
                }
                OutlinedButton(onClick = { onAction(NodeAction.Stop) }, enabled = state.resolvingVoice || state.playback != CommunicationPlaybackStatus.Idle || state.queuedSpeech > 0) {
                    Text(stringResource(R.string.nodes_stop))
                }
                val status = when {
                    state.resolvingVoice || state.playback == CommunicationPlaybackStatus.Preparing -> R.string.nodes_preparing
                    state.playback == CommunicationPlaybackStatus.Playing -> R.string.nodes_playing
                    state.playback == CommunicationPlaybackStatus.Paused -> R.string.nodes_paused
                    else -> R.string.nodes_ready
                }
                Text(stringResource(status), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelMedium)
            }
            if (state.queuedSpeech > 0) Text(stringResource(R.string.nodes_queued, state.queuedSpeech), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun NodeWorkspaceErrors(state: NodeWorkspaceState) {
    state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    if (state.dirty) Text(stringResource(R.string.nodes_draft_pending), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun NodeWordPicker(state: NodeWorkspaceState, onAction: (NodeAction) -> Unit, finishInput: (NodeAction) -> Unit, modifier: Modifier) {
    val editing = state.ready && !state.dirty
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(state.input, { onAction(NodeAction.Input(it)) }, Modifier.weight(1f), enabled = editing,
                label = { Text(stringResource(R.string.nodes_search)) }, singleLine = true)
            TextButton(onClick = { finishInput(NodeAction.AddWords) }, enabled = editing && state.input.isNotBlank()) {
                Text(stringResource(R.string.nodes_add))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.nodes_word_picker), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { onAction(NodeAction.ShowDictionary(true)) }, enabled = editing) {
                Text(stringResource(R.string.nodes_browse, state.library.vocabulary.size))
            }
        }
        if (state.vocabulary.isEmpty()) {
            Text(stringResource(if (state.input.isBlank()) R.string.nodes_dictionary_empty else R.string.nodes_no_matches), style = MaterialTheme.typography.bodySmall)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.vocabulary, key = { it.key }) { word ->
                NodeVocabularyButton(word, state.frequencies[word.key] ?: 0, editing, { finishInput(NodeAction.UseWord(word.key)) })
            }
        }
    }
}

@Composable
private fun NodeVocabularyButton(word: NodeVocabularyWord, count: Int, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val growth = (kotlin.math.ln(count.coerceAtLeast(1).toFloat()) * 2f).coerceAtMost(6f)
    val frequencyDescription = stringResource(R.string.nodes_frequency, count)
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = (48f + growth * 2f).dp)
        .semantics { contentDescription = word.text + ", " + frequencyDescription }) {
        Text(word.text, fontSize = (16f + growth).sp)
    }
}

@Composable
private fun NodeCanvasActions(state: NodeWorkspaceState, onAction: (NodeAction) -> Unit) {
    val editing = state.ready && !state.dirty
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        TextButton(onClick = { onAction(NodeAction.BeginReplace) }, enabled = editing && state.document.selected != null) { Text(stringResource(R.string.nodes_replace)) }
        TextButton(onClick = { onAction(NodeAction.ShowPause(true)) }, enabled = editing) { Text(stringResource(R.string.nodes_add_pause)) }
        TextButton(onClick = { onAction(NodeAction.Delete) }, enabled = editing && state.document.selected != null) { Text(stringResource(R.string.nodes_delete)) }
        TextButton(onClick = { onAction(NodeAction.Disconnect) }, enabled = editing && state.document.selected != null) { Text(stringResource(R.string.nodes_disconnect)) }
        TextButton(onClick = { onAction(NodeAction.Undo) }, enabled = editing && state.canUndo) { Text(stringResource(R.string.nodes_undo)) }
        TextButton(onClick = { onAction(NodeAction.NewMessage) }, enabled = editing && state.sentence.isNotEmpty()) { Text(stringResource(R.string.nodes_new_message)) }
        if (state.connectionSource != null) TextButton(onClick = { onAction(NodeAction.CancelConnection) }) { Text(stringResource(R.string.nodes_cancel_link)) }
    }
}

@Composable
private fun NodeCanvas(state: NodeWorkspaceState, onAction: (NodeAction) -> Unit, modifier: Modifier) {
    val density = LocalDensity.current.density
    val lineColor = MaterialTheme.colorScheme.primary
    val dotColor = MaterialTheme.colorScheme.outlineVariant
    val canvasDescription = stringResource(R.string.nodes_canvas)
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest).border(1.dp, dotColor)
        .semantics { contentDescription = canvasDescription }
        .horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState())) {
        Box(Modifier.size(2000.dp, 4000.dp)) {
            Canvas(Modifier.matchParentSize()) {
                for (x in 0..2000 step 24) for (y in 0..4000 step 24) {
                    drawCircle(dotColor, 1.dp.toPx(), Offset(x * density, y * density))
                }
                fun link(from: Offset, to: Offset) {
                    val path = Path().apply {
                        moveTo(from.x, from.y)
                        cubicTo(from.x + 70 * density, from.y, to.x - 70 * density, to.y, to.x, to.y)
                    }
                    drawPath(path, lineColor, style = Stroke(3.dp.toPx()))
                }
                state.document.graph.nodes.forEach { node ->
                    state.document.graph.nodes.find { it.id == node.next }?.let { next ->
                        link(Offset((node.x + state.width(node) - 24) * density, (node.y + state.height(node) - 28) * density), Offset((next.x + 24) * density, (next.y + state.height(next) - 28) * density))
                    }
                }
                state.document.graph.nodes.find { it.id == state.connectionSource }?.let { source ->
                    link(Offset((source.x + state.width(source) - 24) * density, (source.y + state.height(source) - 28) * density), Offset((state.dragX ?: source.x) * density, (state.dragY ?: source.y) * density))
                }
            }
            state.document.graph.nodes.forEach { node ->
                androidx.compose.runtime.key(node.id) {
                    val label = when (val content = node.content) {
                        is SentenceContent.Word -> content.text
                        is SentenceContent.Pause -> stringResource(R.string.nodes_pause_label, content.milliseconds)
                    }
                    val growth = 0f
                    val selected = state.document.selected == node.id
                    Column(Modifier.offset { IntOffset((node.x * density).roundToInt(), (node.y * density).roundToInt()) }
                        .width(state.width(node).dp).height(state.height(node).dp)
                        .semantics { this.selected = selected }
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                        .border(if (selected) 2.dp else 1.dp, if (selected) lineColor else dotColor, RoundedCornerShape(12.dp))) {
                        Box(Modifier.fillMaxWidth().height((state.height(node) - 52f).dp)
                            .clickable(enabled = !state.dirty) { onAction(NodeAction.Select(node.id)) }
                            .pointerInput(node.id, state.dirty, density) {
                                if (!state.dirty) detectDragGestures(
                                    onDragStart = { onAction(NodeAction.BeginMove) },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        onAction(NodeAction.Move(node.id, amount.x / density, amount.y / density))
                                    },
                                )
                            }.padding(12.dp), contentAlignment = Alignment.Center) {
                            Text(label, maxLines = 2, style = MaterialTheme.typography.titleMedium.copy(fontSize = (16f + growth).sp))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            val inputDescription = stringResource(R.string.nodes_input_port, label)
                            TextButton(onClick = { onAction(NodeAction.ConnectTo(node.id)) }, enabled = !state.dirty && state.connectionSource != null,
                                modifier = Modifier.size(48.dp).semantics { contentDescription = inputDescription }, contentPadding = PaddingValues(0.dp)) { Text("●") }

                            val outputDescription = stringResource(R.string.nodes_output_port, label)
                            TextButton(onClick = { onAction(NodeAction.ConnectFrom(node.id)) }, enabled = !state.dirty,
                                modifier = Modifier.size(48.dp).semantics { contentDescription = outputDescription }
                                    .pointerInput(node.id, state.dirty, density) {
                                        if (!state.dirty) detectDragGestures(
                                            onDragStart = { onAction(NodeAction.ConnectFrom(node.id)) },
                                            onDragCancel = { onAction(NodeAction.CancelConnection) },
                                            onDragEnd = { onAction(NodeAction.DropConnection) },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                onAction(NodeAction.DragConnection(amount.x / density, amount.y / density))
                                            },
                                        )
                                    }, contentPadding = PaddingValues(0.dp)) { Text("→") }
                        }
                    }
                }
            }
        }
    }
}

@Preview(widthDp = 800, heightDp = 800)
@Composable
private fun NodeWorkspacePreview() {
    val graph = SentenceGraph.fromContents(listOf(SentenceContent.Word("I"), SentenceContent.Word("want"), SentenceContent.Pause(500), SentenceContent.Word("coffee")))
    AppTheme { NodeWorkspaceScreen(NodeWorkspaceState(library = NodeWorkspaceLibrary().withDocument(NodeDocument(graph, 1, graph.ssml(1))).withVocabulary(listOf("I", "want", "coffee"))), {}, {}) }
}
