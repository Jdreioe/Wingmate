package io.github.jdreioe.wingmate.ui

import androidx.compose.runtime.Stable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jdreioe.wingmate.domain.obf.BoardSetGraph
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.withHomeFieldsBottomLeft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * User-visible workspace state is independent from rendering, so navigation, communication,
 * editing, and recovery transitions remain deterministic and survive configuration changes.
 */
@Stable
internal data class BoardWorkspaceState(
    val contentStatus: BoardWorkspaceContentStatus = BoardWorkspaceContentStatus.Loading,
    val savedGraph: BoardSetGraph? = null,
    val editSession: BoardSetEditSession? = null,
    val mode: BoardWorkspaceMode = BoardWorkspaceMode.Run,
    val selectedBoardId: String? = null,
    val boardStack: List<String> = emptyList(),
    val showFinishDialog: Boolean = false,
    val showEditingAccessDialog: Boolean = false,
    val isSaving: Boolean = false,
    val isFullscreen: Boolean = false,
    val isExporting: Boolean = false,
    val statusMessage: String? = null,
    val loadRequestId: Int = 0,
) {
    val activeGraph: BoardSetGraph? get() = editSession?.draft ?: savedGraph
    val canNavigateBack: Boolean get() = boardStack.isNotEmpty()

    fun selectBoard(boardId: String): BoardWorkspaceState =
        copy(selectedBoardId = boardId, boardStack = emptyList())

    fun openBoard(boardId: String): BoardWorkspaceState =
        copy(
            selectedBoardId = boardId,
            boardStack = selectedBoardId?.let { boardStack + it } ?: boardStack,
        )

    fun navigateBack(): BoardWorkspaceState =
        boardStack.lastOrNull()?.let { previous ->
            copy(selectedBoardId = previous, boardStack = boardStack.dropLast(1))
        } ?: this

    fun goHome(rootBoardId: String): BoardWorkspaceState =
        copy(selectedBoardId = rootBoardId, boardStack = emptyList())

    fun withPosition(
        selectedBoardId: String?,
        boardStack: List<String>,
    ): BoardWorkspaceState = copy(
        selectedBoardId = selectedBoardId,
        boardStack = boardStack,
    )
}

internal sealed interface BoardWorkspaceContentStatus {
    data object Loading : BoardWorkspaceContentStatus
    data object Empty : BoardWorkspaceContentStatus
    data object Ready : BoardWorkspaceContentStatus
    data class RecoverableFailure(val message: String) : BoardWorkspaceContentStatus
}

internal sealed interface BoardWorkspaceAction {
    data class Initialize(val graph: BoardSetGraph?, val startInEditMode: Boolean) : BoardWorkspaceAction
    data class LoadFailed(val message: String) : BoardWorkspaceAction
    data object RetryLoad : BoardWorkspaceAction
    data class SelectBoard(val boardId: String) : BoardWorkspaceAction
    data class OpenBoard(val boardId: String) : BoardWorkspaceAction
    data object BackClicked : BoardWorkspaceAction
    data class GoHome(val rootBoardId: String) : BoardWorkspaceAction
    data class RestorePosition(
        val selectedBoardId: String?,
        val boardStack: List<String>,
    ) : BoardWorkspaceAction
    data object StartEditing : BoardWorkspaceAction
    data object EditingAccessRequired : BoardWorkspaceAction
    data object EditingAccessDismissed : BoardWorkspaceAction
    data class ApplyEdit(val graph: BoardSetGraph) : BoardWorkspaceAction
    data object UndoEdit : BoardWorkspaceAction
    data object FinishEditingClicked : BoardWorkspaceAction
    data object KeepEditing : BoardWorkspaceAction
    data object DiscardEdits : BoardWorkspaceAction
    data object SaveStarted : BoardWorkspaceAction
    data class SaveSucceeded(val graph: BoardSetGraph) : BoardWorkspaceAction
    data class SaveFailed(val message: String) : BoardWorkspaceAction
    data class FullscreenChanged(val isFullscreen: Boolean) : BoardWorkspaceAction
    data object ExportStarted : BoardWorkspaceAction
    data class ExportFinished(val message: String) : BoardWorkspaceAction
    data class StatusChanged(val message: String?) : BoardWorkspaceAction
}

internal sealed interface BoardWorkspaceEvent {
    data object NavigateToLibrary : BoardWorkspaceEvent
}

/**
 * Saves only the small transient values needed after process recreation. The graph remains in
 * this ViewModel across configuration changes but is reloaded from the shared use case after
 * process death, keeping persisted board content in one source of truth.
 */
internal class BoardWorkspaceViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(
        BoardWorkspaceState(
            selectedBoardId = savedStateHandle[SELECTED_BOARD_ID],
            boardStack = savedStateHandle.get<ArrayList<String>>(BOARD_STACK)?.toList().orEmpty(),
        )
    )
    val state: StateFlow<BoardWorkspaceState> = _state.asStateFlow()
    private val _events = Channel<BoardWorkspaceEvent>()
    val events = _events.receiveAsFlow()

    fun onAction(action: BoardWorkspaceAction) {
        val updated = when (action) {
            is BoardWorkspaceAction.Initialize -> {
                val current = _state.value
                if (current.savedGraph != null) {
                    current
                } else if (action.graph == null) {
                    current.copy(contentStatus = BoardWorkspaceContentStatus.Empty)
                } else {
                    val graph = action.graph.withHomeFieldsBottomLeft()
                    val availableBoardIds = graph.boardsById.keys
                    val positioned = if (current.selectedBoardId !in availableBoardIds) {
                        current.selectBoard(graph.boardSet.rootBoardId)
                    } else {
                        current.copy(boardStack = current.boardStack.filter { it in availableBoardIds })
                    }
                    positioned.copy(
                        contentStatus = BoardWorkspaceContentStatus.Ready,
                        savedGraph = graph,
                        editSession = if (action.startInEditMode && !graph.boardSet.isLocked) {
                            BoardSetEditSession(graph, graph)
                        } else {
                            null
                        },
                        mode = if (action.startInEditMode && !graph.boardSet.isLocked) {
                            BoardWorkspaceMode.Edit
                        } else {
                            BoardWorkspaceMode.Run
                        },
                        statusMessage = null,
                    )
                }
            }
            is BoardWorkspaceAction.LoadFailed -> _state.value.copy(
                contentStatus = BoardWorkspaceContentStatus.RecoverableFailure(action.message),
                statusMessage = action.message,
            )
            BoardWorkspaceAction.RetryLoad -> _state.value.copy(
                contentStatus = BoardWorkspaceContentStatus.Loading,
                statusMessage = null,
                loadRequestId = _state.value.loadRequestId + 1,
            )
            is BoardWorkspaceAction.SelectBoard -> _state.value.selectBoard(action.boardId)
            is BoardWorkspaceAction.OpenBoard -> _state.value.openBoard(action.boardId)
            BoardWorkspaceAction.BackClicked -> handleBack()
            is BoardWorkspaceAction.GoHome -> _state.value.goHome(action.rootBoardId)
            is BoardWorkspaceAction.RestorePosition -> _state.value.withPosition(
                selectedBoardId = action.selectedBoardId,
                boardStack = action.boardStack,
            )
            BoardWorkspaceAction.StartEditing -> {
                val graph = _state.value.savedGraph ?: return
                _state.value.goHome(graph.boardSet.rootBoardId).copy(
                    editSession = BoardSetEditSession(graph, graph),
                    mode = BoardWorkspaceMode.Edit,
                    showEditingAccessDialog = false,
                )
            }
            BoardWorkspaceAction.EditingAccessRequired -> _state.value.copy(
                showEditingAccessDialog = true
            )
            BoardWorkspaceAction.EditingAccessDismissed -> _state.value.copy(
                showEditingAccessDialog = false
            )
            is BoardWorkspaceAction.ApplyEdit -> {
                val session = _state.value.editSession ?: return
                _state.value.copy(editSession = session.apply(action.graph))
            }
            BoardWorkspaceAction.UndoEdit -> _state.value.copy(
                editSession = _state.value.editSession?.undo()
            )
            BoardWorkspaceAction.FinishEditingClicked -> finishEditing()
            BoardWorkspaceAction.KeepEditing -> _state.value.copy(showFinishDialog = false)
            BoardWorkspaceAction.DiscardEdits -> {
                val graph = _state.value.savedGraph ?: return
                _state.value.goHome(graph.boardSet.rootBoardId).copy(
                    editSession = null,
                    mode = BoardWorkspaceMode.Run,
                    showFinishDialog = false,
                )
            }
            BoardWorkspaceAction.SaveStarted -> _state.value.copy(
                showFinishDialog = false,
                isSaving = true,
            )
            is BoardWorkspaceAction.SaveSucceeded -> _state.value.copy(
                contentStatus = BoardWorkspaceContentStatus.Ready,
                savedGraph = action.graph.withHomeFieldsBottomLeft(),
                editSession = null,
                mode = BoardWorkspaceMode.Run,
                isSaving = false,
                statusMessage = null,
            )
            is BoardWorkspaceAction.SaveFailed -> _state.value.copy(
                contentStatus = BoardWorkspaceContentStatus.RecoverableFailure(action.message),
                mode = BoardWorkspaceMode.Edit,
                isSaving = false,
                statusMessage = action.message,
            )
            is BoardWorkspaceAction.FullscreenChanged -> _state.value.copy(
                isFullscreen = action.isFullscreen
            )
            BoardWorkspaceAction.ExportStarted -> _state.value.copy(
                isExporting = true,
                statusMessage = null,
            )
            is BoardWorkspaceAction.ExportFinished -> _state.value.copy(
                isExporting = false,
                statusMessage = action.message,
            )
            is BoardWorkspaceAction.StatusChanged -> _state.value.copy(
                statusMessage = action.message
            )
        }
        if (updated != _state.value) {
            _state.update { updated }
            savedStateHandle[SELECTED_BOARD_ID] = updated.selectedBoardId
            savedStateHandle[BOARD_STACK] = ArrayList(updated.boardStack)
        }
    }

    /** One-release import path for sentence state saved before CommunicationSession owned it. */
    fun consumeLegacySentenceButtons(): List<ObfButton> {
        val encoded = savedStateHandle.get<String>(SENTENCE_BUTTONS) ?: return emptyList()
        savedStateHandle.remove<String>(SENTENCE_BUTTONS)
        return runCatching { json.decodeFromString<List<ObfButton>>(encoded) }.getOrDefault(emptyList())
    }

    private fun handleBack(): BoardWorkspaceState {
        val current = _state.value
        return when {
            current.mode == BoardWorkspaceMode.Edit -> finishEditing()
            current.canNavigateBack -> current.navigateBack()
            else -> {
                viewModelScope.launch { _events.send(BoardWorkspaceEvent.NavigateToLibrary) }
                current
            }
        }
    }

    private fun finishEditing(): BoardWorkspaceState {
        val current = _state.value
        val session = current.editSession ?: return current
        return if (session.isDirty) {
            current.copy(showFinishDialog = true)
        } else {
            current.copy(editSession = null, mode = BoardWorkspaceMode.Run)
        }
    }

    private companion object {
        const val SELECTED_BOARD_ID = "board_workspace.selected_board_id"
        const val BOARD_STACK = "board_workspace.board_stack"
        const val SENTENCE_BUTTONS = "board_workspace.sentence_buttons"
    }
}

/** Holds an editing draft and its undo history without any Compose or persistence dependency. */
internal data class BoardSetEditSession(
    val original: BoardSetGraph,
    val draft: BoardSetGraph,
    val undoStack: List<BoardSetGraph> = emptyList(),
) {
    val isDirty: Boolean get() = draft != original

    fun apply(updated: BoardSetGraph): BoardSetEditSession {
        val normalized = updated.withHomeFieldsBottomLeft()
        if (normalized == draft) return this
        return copy(draft = normalized, undoStack = undoStack + draft)
    }

    fun undo(): BoardSetEditSession {
        val previous = undoStack.lastOrNull() ?: return this
        return copy(draft = previous, undoStack = undoStack.dropLast(1))
    }
}
