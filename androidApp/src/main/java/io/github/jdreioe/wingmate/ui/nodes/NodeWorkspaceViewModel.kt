package io.github.jdreioe.wingmate.ui.nodes

import io.github.jdreioe.wingmate.domain.nodes.*
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jdreioe.wingmate.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.UUID
import com.hojmoseit.wingmate.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal enum class NodeStorageStatus { Loading, Saved, Saving, LoadFailed, SaveFailed }

internal data class NodeWorkspaceState(
    val library: NodeWorkspaceLibrary = NodeWorkspaceLibrary(),
    val input: String = "",
    val pauseInput: String = "500",
    val connectionSource: Int? = null,
    val dragX: Float? = null,
    val dragY: Float? = null,
    val error: Int? = null,
    val canUndo: Boolean = false,
    val storageStatus: NodeStorageStatus = NodeStorageStatus.Saved,
    val showWorkspaces: Boolean = false,
    val workspaceName: String = "",
    val showCode: Boolean = false,
    val showPause: Boolean = false,
    val showDictionary: Boolean = false,
    val replacingWord: Boolean = false,
    val playback: CommunicationPlaybackStatus = CommunicationPlaybackStatus.Idle,
    val queuedSpeech: Int = 0,
    val resolvingVoice: Boolean = false,
) {
    val document get() = library.active.document
    val frequencies get() = library.frequencies
    val ready get() = storageStatus != NodeStorageStatus.Loading && storageStatus != NodeStorageStatus.LoadFailed
    val vocabulary get() = library.vocabulary.filter { it.text.contains(input.trim(), ignoreCase = true) }
        .sortedWith(compareByDescending<NodeVocabularyWord> { frequencies[it.key] ?: 0 }.thenBy { it.key })
    val preview get() = sentence.joinToString(" ") {
        when (val content = it.content) {
            is SentenceContent.Word -> content.text
            is SentenceContent.Pause -> "[${content.milliseconds} ms]"
        }
    }
    val dirty get() = document.draft != document.graph.ssml(document.selected)
    val sentence get() = document.graph.sentence(document.selected)
    fun frequency(node: SentenceNode) = (node.content as? SentenceContent.Word)?.text?.let { frequencies[wordKey(it)] } ?: 0
    fun growth(node: SentenceNode) = (kotlin.math.ln(frequency(node).coerceAtLeast(1).toFloat()) * 5f).coerceAtMost(14f)
    fun width(node: SentenceNode) = 216f
    fun height(node: SentenceNode) = 108f
}

internal sealed interface NodeAction {
    data class Input(val text: String) : NodeAction
    data class PauseInput(val text: String) : NodeAction
    data object AddWords : NodeAction
    data object AddPause : NodeAction
    data class ShowPause(val show: Boolean) : NodeAction
    data class Select(val id: Int) : NodeAction
    data class Move(val id: Int, val dx: Float, val dy: Float) : NodeAction
    data object BeginMove : NodeAction
    data class ConnectFrom(val id: Int) : NodeAction
    data class ConnectTo(val id: Int) : NodeAction
    data class DragConnection(val dx: Float, val dy: Float) : NodeAction
    data object DropConnection : NodeAction
    data object CancelConnection : NodeAction
    data object Disconnect : NodeAction
    data object Delete : NodeAction
    data class Draft(val text: String) : NodeAction
    data object ApplySsml : NodeAction
    data object RevertSsml : NodeAction
    data object Undo : NodeAction
    data class UseWord(val key: String) : NodeAction
    data class ReplaceWord(val key: String) : NodeAction
    data object BeginReplace : NodeAction
    data object NewMessage : NodeAction
    data object Speak : NodeAction
    data object Stop : NodeAction
    data object RetryStorage : NodeAction
    data object ToggleCode : NodeAction
    data class ShowDictionary(val show: Boolean) : NodeAction
    data class ShowWorkspaces(val show: Boolean) : NodeAction
    data class WorkspaceName(val value: String) : NodeAction
    data object CreateWorkspace : NodeAction
    data object RenameWorkspace : NodeAction
    data class OpenWorkspace(val id: String) : NodeAction
}

internal class NodeWorkspaceViewModel(
    private val savedState: SavedStateHandle,
    private val storage: NodeWorkspaceStorage? = null,
    private val communicationSession: CommunicationSession? = null,
    private val selectedVoice: suspend () -> Voice? = { null },
) : ViewModel() {
    private val initial = savedState.get<String>("nodeDocument")?.let {
        runCatching { Json.decodeFromString<NodeDocument>(it) }.getOrNull()
    } ?: NodeDocument()
    private var frequency = TypedWordFrequency(savedState.get<String>("nodeFrequencies")?.let {
        runCatching { Json.decodeFromString<Map<String, Int>>(it) }.getOrNull()
    } ?: emptyMap(), savedState.get<Int>("nodeCreditedStart")?.let { start ->
        savedState.get<String>("nodeCreditedWord")?.let { start to it }
    })
    private val initialLibrary = NodeWorkspaceLibrary(frequencies = frequency.counts)
        .withDocument(initial).withVocabulary(initial.graph.nodes.mapNotNull { (it.content as? SentenceContent.Word)?.text })
    private val mutableState = MutableStateFlow(NodeWorkspaceState(
        library = initialLibrary,
        storageStatus = if (storage == null) NodeStorageStatus.Saved else NodeStorageStatus.Loading,
    ))
    val state = mutableState.asStateFlow()
    private val undo = ArrayDeque<NodeDocument>()
    private val saveRequests = Channel<Unit>(Channel.CONFLATED)
    private val pendingInput = ArrayDeque<() -> Unit>()
    private var revision = 0L
    private var voiceJob: Job? = null

    init {
        if (storage != null) viewModelScope.launch {
            load()
            for (request in saveRequests) {
                if (!state.value.ready) continue
                val version = revision
                val snapshot = state.value.library
                mutableState.update { it.copy(storageStatus = NodeStorageStatus.Saving) }
                try {
                    storage.save(snapshot)
                    if (version == revision) mutableState.update { it.copy(storageStatus = NodeStorageStatus.Saved) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.update { it.copy(storageStatus = NodeStorageStatus.SaveFailed) }
                }
            }
        }
        communicationSession?.let { session ->
            viewModelScope.launch {
                session.state.collect { playback ->
                    mutableState.update { it.copy(playback = playback.playbackStatus, queuedSpeech = playback.queuedSpeechCount) }
                }
            }
        }
    }

    private suspend fun load() {
        mutableState.update { it.copy(storageStatus = NodeStorageStatus.Loading) }
        try {
            val loaded = storage?.load() ?: initialLibrary
            frequency = TypedWordFrequency(loaded.frequencies, loaded.creditedStart?.let { start -> loaded.creditedWord?.let { start to it } })
            mutableState.update { it.copy(library = loaded, storageStatus = NodeStorageStatus.Saved) }
            while (pendingInput.isNotEmpty()) pendingInput.removeFirst().invoke()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.update { it.copy(storageStatus = NodeStorageStatus.LoadFailed) }
        }
    }

    private fun library(value: NodeWorkspaceLibrary) {
        revision++
        mutableState.update { it.copy(library = value, error = null,
            storageStatus = if (storage == null) NodeStorageStatus.Saved else NodeStorageStatus.Saving) }
        if (storage != null) saveRequests.trySend(Unit)
    }

    fun recordTyping(old: String, new: String) {
        if (!state.value.ready) { pendingInput.add { recordTyping(old, new) }; return }
        frequency.edit(old, new)
        saveFrequency()
    }

    private fun saveFrequency() {
        val current = state.value.library
        if (frequency.counts == current.frequencies && frequency.creditedTail?.first == current.creditedStart &&
            frequency.creditedTail?.second == current.creditedWord) return
        savedState["nodeFrequencies"] = Json.encodeToString(frequency.counts)
        savedState["nodeCreditedStart"] = frequency.creditedTail?.first
        savedState["nodeCreditedWord"] = frequency.creditedTail?.second
        library(state.value.library.copy(frequencies = frequency.counts,
            creditedStart = frequency.creditedTail?.first, creditedWord = frequency.creditedTail?.second))
    }

    fun convertWord(word: String, message: String) {
        if (!state.value.ready) { pendingInput.add { convertWord(word, message) }; return }
        frequency.finishForConversion(message, word)
        saveFrequency()
        library(state.value.library.withVocabulary(listOf(word)))
        mutableState.update { it.copy(input = word) }
    }

    private fun speak() {
        val current = state.value
        if (current.dirty || current.resolvingVoice || current.sentence.none { it.content is SentenceContent.Word }) return
        val session = communicationSession ?: return
        val message = current.document.graph.toMessage(current.document.selected)
        val segments = current.document.graph.toSpeechSegments(current.document.selected)
        mutableState.update { it.copy(resolvingVoice = true, error = null) }
        voiceJob = viewModelScope.launch {
            try {
                session.accept(CommunicationAction.SpeakMessage(message, selectedVoice(), segments))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(error = R.string.nodes_speech_error) }
            } finally {
                mutableState.update { it.copy(resolvingVoice = false) }
            }
        }
    }

    private fun checkpoint() {
        undo.addLast(state.value.document)
        if (undo.size > 30) undo.removeFirst()
        mutableState.update { it.copy(canUndo = true) }
    }

    private fun document(value: NodeDocument) {
        savedState["nodeDocument"] = Json.encodeToString(value)
        library(state.value.library.withDocument(value).withVocabulary(value.graph.nodes.mapNotNull { (it.content as? SentenceContent.Word)?.text }))
    }

    private fun graph(value: SentenceGraph, selected: Int? = state.value.document.selected) {
        document(NodeDocument(value, selected, value.ssml(selected)))
        mutableState.update { it.copy(connectionSource = null, dragX = null, dragY = null) }
    }

    fun onAction(action: NodeAction) {
        val state = state.value
        val doc = state.document
        if (action == NodeAction.Stop) {
            voiceJob?.cancel()
            communicationSession?.accept(CommunicationAction.Stop)
            return
        }
        if (action == NodeAction.RetryStorage) {
            if (state.storageStatus == NodeStorageStatus.LoadFailed) viewModelScope.launch { load() }
            else saveRequests.trySend(Unit)
            return
        }
        if (!state.ready) return
        // An unapplied code draft must never disappear because of a canvas action.
        if (state.dirty && action !is NodeAction.Draft && action != NodeAction.ApplySsml && action != NodeAction.RevertSsml &&
            action !is NodeAction.ShowWorkspaces && action !is NodeAction.OpenWorkspace && action !is NodeAction.WorkspaceName &&
            action !is NodeAction.ShowDictionary && action != NodeAction.CreateWorkspace && action != NodeAction.RenameWorkspace && action != NodeAction.ToggleCode) return
        when (action) {
            is NodeAction.Input -> mutableState.update { it.copy(input = action.text) }
            is NodeAction.PauseInput -> mutableState.update { it.copy(pauseInput = action.text) }
            NodeAction.Speak -> speak()
            NodeAction.Stop, NodeAction.RetryStorage -> Unit
            is NodeAction.ShowDictionary -> mutableState.update { it.copy(showDictionary = action.show, replacingWord = false) }
            NodeAction.ToggleCode -> mutableState.update { it.copy(showCode = !it.showCode) }
            is NodeAction.ShowWorkspaces -> mutableState.update { it.copy(showWorkspaces = action.show, workspaceName = it.library.active.name) }
            is NodeAction.WorkspaceName -> mutableState.update { it.copy(workspaceName = action.value.take(80)) }
            NodeAction.CreateWorkspace -> {
                if (state.workspaceName.isBlank() || state.library.workspaces.size >= 100) return
                val workspace = SavedNodeWorkspace(UUID.randomUUID().toString(), state.workspaceName.trim())
                library(state.library.copy(activeId = workspace.id, workspaces = state.library.workspaces + workspace))
                resetWorkspaceUi()
            }
            NodeAction.RenameWorkspace -> {
                if (state.workspaceName.isBlank()) return
                library(state.library.copy(workspaces = state.library.workspaces.map {
                    if (it.id == state.library.activeId) it.copy(name = state.workspaceName.trim()) else it
                }))
                mutableState.update { it.copy(showWorkspaces = false) }
            }
            is NodeAction.OpenWorkspace -> {
                if (state.library.workspaces.none { it.id == action.id }) return
                library(state.library.copy(activeId = action.id))
                resetWorkspaceUi()
            }
            NodeAction.BeginReplace -> mutableState.update { it.copy(replacingWord = true, showDictionary = true, input = "") }
            NodeAction.NewMessage -> {
                checkpoint()
                graph(SentenceGraph(), null)
            }
            is NodeAction.UseWord -> {
                val word = state.library.vocabulary.find { it.key == action.key } ?: return
                if (state.replacingWord) {
                    onAction(NodeAction.ReplaceWord(action.key))
                    mutableState.update { it.copy(showDictionary = false, replacingWord = false) }
                } else add(listOf(SentenceContent.Word(word.text)))
            }
            is NodeAction.ReplaceWord -> {
                val word = state.library.vocabulary.find { it.key == action.key } ?: return
                val selected = doc.selected ?: return
                checkpoint()
                graph(doc.graph.replace(selected, SentenceContent.Word(word.text)))
            }
            NodeAction.AddWords -> {
                val words = state.input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (words.isEmpty()) return
                if (state.replacingWord && words.size == 1 && doc.selected != null) {
                    checkpoint()
                    graph(doc.graph.replace(doc.selected, SentenceContent.Word(words.single())))
                    mutableState.update { it.copy(showDictionary = false, replacingWord = false) }
                } else if (!state.replacingWord) add(words.map { SentenceContent.Word(it) })
                mutableState.update { it.copy(input = "") }
            }
            is NodeAction.ShowPause -> mutableState.update { it.copy(showPause = action.show) }
            NodeAction.AddPause -> {
                val time = state.pauseInput.toIntOrNull()
                if (time == null || time !in 1..10000) {
                    mutableState.update { it.copy(error = R.string.nodes_pause_error) }
                } else {
                    add(listOf(SentenceContent.Pause(time)))
                    mutableState.update { it.copy(showPause = false) }
                }
            }
            is NodeAction.Select -> graph(doc.graph, action.id)
            NodeAction.BeginMove -> checkpoint()
            is NodeAction.Move -> {
                val moved = doc.graph.copy(nodes = doc.graph.nodes.map {
                    if (it.id == action.id) it.copy(x = (it.x + action.dx).coerceIn(0f, 1700f), y = (it.y + action.dy).coerceIn(0f, 3860f)) else it
                })
                document(doc.copy(graph = moved))
            }
            is NodeAction.ConnectFrom -> {
                val node = doc.graph.nodes.find { it.id == action.id } ?: return
                mutableState.update { it.copy(connectionSource = action.id, dragX = node.x + state.width(node) - 24f, dragY = node.y + state.height(node) - 28f, error = null) }
            }
            is NodeAction.DragConnection -> mutableState.update {
                it.copy(dragX = it.dragX?.plus(action.dx), dragY = it.dragY?.plus(action.dy))
            }
            NodeAction.DropConnection -> {
                val target = doc.graph.nodes.find {
                    it.id != state.connectionSource && (state.dragX ?: -1f) in it.x..(it.x + 48f) &&
                        (state.dragY ?: -1f) in (it.y + state.height(it) - 52f)..(it.y + state.height(it) - 4f)
                }
                if (target != null) connect(target.id) else onAction(NodeAction.CancelConnection)
            }
            is NodeAction.ConnectTo -> connect(action.id)
            NodeAction.CancelConnection -> mutableState.update { it.copy(connectionSource = null, dragX = null, dragY = null) }
            NodeAction.Disconnect -> {
                checkpoint()
                graph(doc.graph.copy(nodes = doc.graph.nodes.map {
                    if (it.id == doc.selected || it.next == doc.selected) it.copy(next = null) else it
                }))
            }
            NodeAction.Delete -> {
                val id = doc.selected ?: return
                checkpoint()
                graph(doc.graph.remove(id), null)
            }
            is NodeAction.Draft -> document(doc.copy(draft = action.text.take(20_000)))
            NodeAction.ApplySsml -> {
                val contents = parseSentenceSsml(doc.draft)
                if (contents == null) {
                    mutableState.update { it.copy(error = R.string.nodes_ssml_error) }
                    return
                }
                val replacedIds = doc.graph.sentence(doc.selected).map { it.id }.toSet()
                val remaining = doc.graph.nodes.filterNot { it.id in replacedIds }
                if (remaining.size + contents.size > 100) {
                    mutableState.update { it.copy(error = R.string.nodes_limit) }
                    return
                }
                checkpoint()
                val replacement = SentenceGraph.fromContents(contents, (doc.graph.nodes.maxOfOrNull { it.id } ?: 0) + 1)
                graph(SentenceGraph(replacement.nodes + remaining), replacement.nodes.firstOrNull()?.id ?: remaining.firstOrNull()?.id)
            }
            NodeAction.RevertSsml -> graph(doc.graph)
            NodeAction.Undo -> {
                val previous = undo.removeLastOrNull() ?: return
                document(previous)
                mutableState.update { it.copy(canUndo = undo.isNotEmpty(), connectionSource = null, dragX = null, dragY = null) }
            }
        }
    }

    private fun add(contents: List<SentenceContent>) {
        val doc = state.value.document
        if (doc.graph.nodes.size + contents.size > 100) {
            mutableState.update { it.copy(error = R.string.nodes_limit) }
            return
        }
        checkpoint()
        val appended = doc.graph.append(contents, doc.selected)
        graph(appended, appended.nodes.last().id)
    }

    private fun resetWorkspaceUi() {
        undo.clear()
        mutableState.update { it.copy(canUndo = false, input = "", showWorkspaces = false,
            connectionSource = null, dragX = null, dragY = null) }
    }

    private fun connect(target: Int) {
        val doc = state.value.document
        val source = state.value.connectionSource ?: return
        val connected = doc.graph.connect(source, target)
        if (connected == null) {
            mutableState.update { it.copy(error = R.string.nodes_connection_error, connectionSource = null, dragX = null, dragY = null) }
        } else {
            checkpoint()
            graph(connected, source)
        }
    }
}
