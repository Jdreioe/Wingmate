package io.github.jdreioe.wingmate.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.application.BoardSetSpeechCacheUseCase
import io.github.jdreioe.wingmate.application.BoardSetUseCase
import io.github.jdreioe.wingmate.application.EditingAccessController
import io.github.jdreioe.wingmate.application.KeyboardPreset
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.infrastructure.BoardImportResult
import io.github.jdreioe.wingmate.infrastructure.BoardImportService
import io.github.jdreioe.wingmate.infrastructure.QuickCoreDownloadProgress
import io.github.jdreioe.wingmate.infrastructure.QuickCorePresetService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal enum class BoardWorkspaceMode { Run, Edit }

internal sealed interface BoardSetManagerRoute {
    data object Library : BoardSetManagerRoute

    data class Workspace(
        val boardSetId: String,
        val mode: BoardWorkspaceMode,
    ) : BoardSetManagerRoute
}

internal sealed interface BoardSetManagerMessage {
    data class Resource(@param:StringRes val id: Int) : BoardSetManagerMessage
    data class Dynamic(val value: String) : BoardSetManagerMessage
}

internal data class BoardSetManagerProgress(
    val fraction: Float?,
    val stage: String,
)

internal data class CreateBoardSetDraft(
    val name: String = "",
    val rowsText: String = "4",
    val columnsText: String = "8",
    val template: BoardSetTemplate = BoardSetTemplate.Blank,
    val keyboardPreset: KeyboardPreset = KeyboardPreset.Qwerty,
)

@Stable
internal data class BoardSetManagerState(
    val route: BoardSetManagerRoute = BoardSetManagerRoute.Library,
    val boardSets: List<ObfBoardSet> = emptyList(),
    val isLoading: Boolean = true,
    val isCreating: Boolean = false,
    val showCreateDialog: Boolean = false,
    val createDraft: CreateBoardSetDraft = CreateBoardSetDraft(),
    val showSettings: Boolean = false,
    val deleteTargetId: String? = null,
    val pendingProtectedDeleteId: String? = null,
    val showDeleteAccessDialog: Boolean = false,
    val statusMessage: BoardSetManagerMessage? = null,
    val quickCoreProgress: BoardSetManagerProgress? = null,
    val isQuickCoreImporting: Boolean = false,
)

internal sealed interface BoardSetManagerAction {
    data class Initialize(
        val createOnLaunch: Boolean,
        val initialBoardSetId: String?,
    ) : BoardSetManagerAction

    data object BackClicked : BoardSetManagerAction
    data object SettingsClicked : BoardSetManagerAction
    data object SettingsDismissed : BoardSetManagerAction
    data object CreateClicked : BoardSetManagerAction
    data object RetryLoad : BoardSetManagerAction
    data object CreateDismissed : BoardSetManagerAction
    data class CreateNameChanged(val name: String) : BoardSetManagerAction
    data class CreateRowsChanged(val rowsText: String) : BoardSetManagerAction
    data class CreateColumnsChanged(val columnsText: String) : BoardSetManagerAction
    data class CreateTemplateSelected(
        val template: BoardSetTemplate,
        val suggestedName: String? = null,
    ) : BoardSetManagerAction
    data class CreateKeyboardPresetSelected(val preset: KeyboardPreset) : BoardSetManagerAction
    data object ImportClicked : BoardSetManagerAction
    data class OpenClicked(val boardSetId: String) : BoardSetManagerAction
    data class EditClicked(val boardSetId: String) : BoardSetManagerAction
    data class DuplicateClicked(val boardSetId: String) : BoardSetManagerAction
    data class ToggleLockClicked(val boardSetId: String) : BoardSetManagerAction
    data class DeleteClicked(val boardSetId: String) : BoardSetManagerAction
    data object DeleteDismissed : BoardSetManagerAction
    data object DeleteConfirmed : BoardSetManagerAction
    data object DeleteAccessDismissed : BoardSetManagerAction
    data object DeleteAccessGranted : BoardSetManagerAction
    data object WorkspaceExited : BoardSetManagerAction

    data class CreateSubmitted(
        val name: String,
        val rows: Int,
        val columns: Int,
        val template: BoardSetTemplate,
        val keyboardPreset: KeyboardPreset,
        val defaultBoardName: String,
    ) : BoardSetManagerAction
}

internal sealed interface BoardSetManagerEvent {
    data object NavigateBack : BoardSetManagerEvent
}

/**
 * The persistence boundary used by the manager presentation model. Keeping it narrow makes
 * state transitions deterministic without replacing the shared board-set use cases.
 */
internal interface BoardSetManagerOperations {
    val quickCoreProgress: Flow<QuickCoreDownloadProgress>

    suspend fun listBoardSets(): List<ObfBoardSet>
    suspend fun getBoardSet(id: String): ObfBoardSet?
    suspend fun duplicateBoardSet(id: String)
    suspend fun toggleLocked(id: String)
    suspend fun deleteBoardSet(id: String)
    suspend fun cacheAll()
    suspend fun cacheBoardSet(id: String)
    suspend fun requiresDeleteUnlock(): Boolean
    suspend fun importBoardSet(): BoardImportResult
    suspend fun importQuickCore(slug: String): BoardImportResult
    suspend fun renameBoardSet(id: String, name: String): ObfBoardSet?
    suspend fun createBoardSet(name: String, rows: Int, columns: Int, rootBoardName: String): ObfBoardSet
    suspend fun createCalculatorBoardSet(name: String): ObfBoardSet
    suspend fun createKeyboardBoardSet(name: String, preset: KeyboardPreset): ObfBoardSet
}

internal class DefaultBoardSetManagerOperations(
    private val useCase: BoardSetUseCase,
    private val speechCache: BoardSetSpeechCacheUseCase,
    private val importService: BoardImportService?,
    private val quickCoreService: QuickCorePresetService?,
    private val editingAccessController: EditingAccessController?,
) : BoardSetManagerOperations {
    override val quickCoreProgress: Flow<QuickCoreDownloadProgress> =
        quickCoreService?.progress ?: emptyFlow()

    override suspend fun listBoardSets() = useCase.listBoardSets()
    override suspend fun getBoardSet(id: String) = useCase.getBoardSet(id)
    override suspend fun duplicateBoardSet(id: String) {
        useCase.duplicateBoardSet(id)
    }

    override suspend fun toggleLocked(id: String) {
        useCase.toggleLocked(id)
    }

    override suspend fun deleteBoardSet(id: String) = useCase.deleteBoardSet(id)
    override suspend fun cacheAll() = speechCache.cacheAll()
    override suspend fun cacheBoardSet(id: String) = speechCache.cacheBoardSet(id)
    override suspend fun requiresDeleteUnlock() = editingAccessController?.requiresUnlock() == true
    override suspend fun importBoardSet(): BoardImportResult =
        importService?.importBoardSetResult()
            ?: BoardImportResult.Failure(
                code = io.github.jdreioe.wingmate.infrastructure.BoardImportErrorCode.FILE_UNREADABLE,
                context = "Board import is unavailable",
            )

    override suspend fun importQuickCore(slug: String): BoardImportResult =
        quickCoreService?.importPreset(slug)
            ?: BoardImportResult.Failure(
                code = io.github.jdreioe.wingmate.infrastructure.BoardImportErrorCode.FILE_UNREADABLE,
                context = "Quick Core import is unavailable",
            )

    override suspend fun renameBoardSet(id: String, name: String) = useCase.renameBoardSet(id, name)
    override suspend fun createBoardSet(
        name: String,
        rows: Int,
        columns: Int,
        rootBoardName: String,
    ) = useCase.createBoardSet(name, rows, columns, rootBoardName)

    override suspend fun createCalculatorBoardSet(name: String) = useCase.createCalculatorBoardSet(name)
    override suspend fun createKeyboardBoardSet(name: String, preset: KeyboardPreset) =
        useCase.createKeyboardBoardSet(name, preset)
}

internal class BoardSetManagerViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val operations: BoardSetManagerOperations,
) : ViewModel() {
    private val restoredRoute = savedStateHandle.get<String>(ROUTE_BOARD_SET_ID)?.let { boardSetId ->
        BoardSetManagerRoute.Workspace(
            boardSetId = boardSetId,
            mode = savedStateHandle.get<String>(ROUTE_MODE)
                ?.let { runCatching { BoardWorkspaceMode.valueOf(it) }.getOrNull() }
                ?: BoardWorkspaceMode.Run,
        )
    } ?: BoardSetManagerRoute.Library

    private val _state = MutableStateFlow(
        BoardSetManagerState(
            route = restoredRoute,
            showCreateDialog = savedStateHandle[SHOW_CREATE_DIALOG] ?: false,
            createDraft = CreateBoardSetDraft(
                name = savedStateHandle[CREATE_NAME] ?: "",
                rowsText = savedStateHandle[CREATE_ROWS] ?: "4",
                columnsText = savedStateHandle[CREATE_COLUMNS] ?: "8",
                template = savedStateHandle.get<String>(CREATE_TEMPLATE)
                    ?.let { runCatching { BoardSetTemplate.valueOf(it) }.getOrNull() }
                    ?: BoardSetTemplate.Blank,
                keyboardPreset = savedStateHandle.get<String>(CREATE_KEYBOARD_PRESET)
                    ?.let { runCatching { KeyboardPreset.valueOf(it) }.getOrNull() }
                    ?: KeyboardPreset.Qwerty,
            ),
            showSettings = savedStateHandle[SHOW_SETTINGS] ?: false,
            deleteTargetId = savedStateHandle[DELETE_TARGET_ID],
            pendingProtectedDeleteId = savedStateHandle[PENDING_DELETE_ID],
            showDeleteAccessDialog = savedStateHandle[SHOW_DELETE_ACCESS_DIALOG] ?: false,
        )
    )
    val state: StateFlow<BoardSetManagerState> = _state.asStateFlow()

    private val _events = Channel<BoardSetManagerEvent>()
    val events: Flow<BoardSetManagerEvent> = _events.receiveAsFlow()
    private var initialized = false

    init {
        viewModelScope.launch {
            operations.quickCoreProgress.collectLatest { progress ->
                _state.update {
                    it.copy(
                        quickCoreProgress = BoardSetManagerProgress(
                            fraction = progress.fraction?.toFloat(),
                            stage = progress.stage,
                        )
                    )
                }
            }
        }
    }

    fun onAction(action: BoardSetManagerAction) {
        when (action) {
            is BoardSetManagerAction.Initialize -> initialize(action)
            BoardSetManagerAction.BackClicked -> {
                setRoute(BoardSetManagerRoute.Library)
                sendEvent(BoardSetManagerEvent.NavigateBack)
            }
            BoardSetManagerAction.SettingsClicked -> setShowSettings(true)
            BoardSetManagerAction.SettingsDismissed -> setShowSettings(false)
            BoardSetManagerAction.CreateClicked -> setShowCreateDialog(true)
            BoardSetManagerAction.RetryLoad -> refreshBoardSets()
            BoardSetManagerAction.CreateDismissed -> dismissCreateDialog()
            is BoardSetManagerAction.CreateNameChanged -> updateCreateDraft(
                _state.value.createDraft.copy(name = action.name)
            )
            is BoardSetManagerAction.CreateRowsChanged -> updateCreateDraft(
                _state.value.createDraft.copy(rowsText = action.rowsText.filter(Char::isDigit))
            )
            is BoardSetManagerAction.CreateColumnsChanged -> updateCreateDraft(
                _state.value.createDraft.copy(columnsText = action.columnsText.filter(Char::isDigit))
            )
            is BoardSetManagerAction.CreateTemplateSelected -> {
                val draft = _state.value.createDraft
                updateCreateDraft(
                    draft.copy(
                        template = action.template,
                        name = if (draft.name.isBlank()) action.suggestedName ?: draft.name else draft.name,
                    )
                )
            }
            is BoardSetManagerAction.CreateKeyboardPresetSelected -> updateCreateDraft(
                _state.value.createDraft.copy(keyboardPreset = action.preset)
            )
            BoardSetManagerAction.ImportClicked -> importBoardSet()
            is BoardSetManagerAction.OpenClicked -> openWorkspace(action.boardSetId, BoardWorkspaceMode.Run)
            is BoardSetManagerAction.EditClicked -> openWorkspace(action.boardSetId, BoardWorkspaceMode.Edit)
            is BoardSetManagerAction.DuplicateClicked -> duplicate(action.boardSetId)
            is BoardSetManagerAction.ToggleLockClicked -> toggleLock(action.boardSetId)
            is BoardSetManagerAction.DeleteClicked -> setDeleteTarget(action.boardSetId)
            BoardSetManagerAction.DeleteDismissed -> setDeleteTarget(null)
            BoardSetManagerAction.DeleteConfirmed -> confirmDelete()
            BoardSetManagerAction.DeleteAccessDismissed -> clearProtectedDelete()
            BoardSetManagerAction.DeleteAccessGranted -> deletePendingProtectedBoardSet()
            BoardSetManagerAction.WorkspaceExited -> {
                setRoute(BoardSetManagerRoute.Library)
                refreshBoardSets()
            }
            is BoardSetManagerAction.CreateSubmitted -> createBoardSet(action)
        }
    }

    private fun initialize(action: BoardSetManagerAction.Initialize) {
        if (initialized) return
        initialized = true
        if (action.createOnLaunch && restoredRoute == BoardSetManagerRoute.Library) {
            setShowCreateDialog(true)
        }
        refreshBoardSets()
        viewModelScope.launch { runCatching { operations.cacheAll() } }
        if (restoredRoute == BoardSetManagerRoute.Library && action.initialBoardSetId != null) {
            viewModelScope.launch {
                operations.getBoardSet(action.initialBoardSetId)?.let {
                    openWorkspace(it.id, BoardWorkspaceMode.Run)
                }
            }
        }
    }

    private fun refreshBoardSets() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, statusMessage = null) }
            runCatching { operations.listBoardSets() }
                .onSuccess { boardSets -> _state.update { it.copy(boardSets = boardSets) } }
                .onFailure { setStatus(BoardSetManagerMessage.Resource(R.string.board_sets_load_error)) }
            _state.update { it.copy(isLoading = false) }
        }
    }

    private fun importBoardSet() {
        viewModelScope.launch {
            when (val result = operations.importBoardSet()) {
                is BoardImportResult.Success -> {
                    operations.cacheBoardSet(result.boardSet.id)
                    setStatus(BoardSetManagerMessage.Resource(R.string.board_sets_imported))
                    refreshBoardSets()
                    openWorkspace(result.boardSet.id, BoardWorkspaceMode.Run)
                }
                BoardImportResult.Cancelled -> Unit
                is BoardImportResult.Failure -> setStatus(
                    BoardSetManagerMessage.Resource(R.string.board_sets_import_error)
                )
            }
        }
    }

    private fun duplicate(id: String) {
        viewModelScope.launch {
            runCatching { operations.duplicateBoardSet(id) }
                .onSuccess {
                    setStatus(BoardSetManagerMessage.Resource(R.string.board_sets_duplicated))
                    refreshBoardSets()
                }
                .onFailure { setStatus(BoardSetManagerMessage.Resource(R.string.board_sets_duplicate_error)) }
        }
    }

    private fun toggleLock(id: String) {
        viewModelScope.launch {
            runCatching { operations.toggleLocked(id) }
                .onSuccess { refreshBoardSets() }
                .onFailure { setStatus(BoardSetManagerMessage.Resource(R.string.board_sets_lock_error)) }
        }
    }

    private fun confirmDelete() {
        val targetId = _state.value.deleteTargetId ?: return
        setDeleteTarget(null)
        viewModelScope.launch {
            if (operations.requiresDeleteUnlock()) {
                savedStateHandle[PENDING_DELETE_ID] = targetId
                savedStateHandle[SHOW_DELETE_ACCESS_DIALOG] = true
                _state.update {
                    it.copy(
                        pendingProtectedDeleteId = targetId,
                        showDeleteAccessDialog = true,
                    )
                }
            } else {
                deleteBoardSet(targetId)
            }
        }
    }

    private fun deletePendingProtectedBoardSet() {
        val targetId = _state.value.pendingProtectedDeleteId ?: return
        clearProtectedDelete()
        deleteBoardSet(targetId)
    }

    private fun deleteBoardSet(id: String) {
        viewModelScope.launch {
            runCatching { operations.deleteBoardSet(id) }
                .onSuccess {
                    setStatus(BoardSetManagerMessage.Resource(R.string.board_sets_deleted))
                    refreshBoardSets()
                }
                .onFailure { setStatus(BoardSetManagerMessage.Resource(R.string.board_sets_delete_error)) }
        }
    }

    private fun createBoardSet(action: BoardSetManagerAction.CreateSubmitted) {
        dismissCreateDialog()
        _state.update {
            it.copy(
                isCreating = true,
                isQuickCoreImporting = action.template.quickCoreSlug != null,
            )
        }
        viewModelScope.launch {
            val result = runCatching {
                val quickCoreSlug = action.template.quickCoreSlug
                if (quickCoreSlug != null) {
                    when (val imported = operations.importQuickCore(quickCoreSlug)) {
                        is BoardImportResult.Success -> operations.renameBoardSet(
                            imported.boardSet.id,
                            action.name.trim(),
                        ) ?: imported.boardSet
                        BoardImportResult.Cancelled -> null
                        is BoardImportResult.Failure -> throw BoardSetCreationException(imported.context)
                    }
                } else {
                    when (action.template) {
                        BoardSetTemplate.Blank -> operations.createBoardSet(
                            action.name.trim(),
                            action.rows,
                            action.columns,
                            action.defaultBoardName,
                        )
                        BoardSetTemplate.Calculator -> operations.createCalculatorBoardSet(action.name.trim())
                        BoardSetTemplate.Keyboard -> operations.createKeyboardBoardSet(
                            action.name.trim(),
                            action.keyboardPreset,
                        )
                        else -> error("Quick Core presets are imported separately")
                    }
                }
            }
            result.onSuccess { created ->
                if (created != null) {
                    showCreatedBoardSet(created)
                    if (action.template == BoardSetTemplate.Blank) {
                        openWorkspace(created.id, BoardWorkspaceMode.Edit)
                    } else if (action.template.quickCoreSlug != null) {
                        openWorkspace(created.id, BoardWorkspaceMode.Run)
                    }
                }
            }.onFailure {
                setStatus(BoardSetManagerMessage.Resource(R.string.board_sets_create_error))
            }
            _state.update { it.copy(isCreating = false, isQuickCoreImporting = false) }
        }
    }

    private fun showCreatedBoardSet(created: ObfBoardSet) {
        _state.update { state ->
            state.copy(
                boardSets = (listOf(created) + state.boardSets)
                    .distinctBy { it.id }
                    .sortedByDescending { it.updatedAt },
            )
        }
    }

    private fun openWorkspace(boardSetId: String, mode: BoardWorkspaceMode) {
        setRoute(BoardSetManagerRoute.Workspace(boardSetId, mode))
    }

    private fun setRoute(route: BoardSetManagerRoute) {
        when (route) {
            BoardSetManagerRoute.Library -> {
                savedStateHandle[ROUTE_BOARD_SET_ID] = null
                savedStateHandle[ROUTE_MODE] = null
            }
            is BoardSetManagerRoute.Workspace -> {
                savedStateHandle[ROUTE_BOARD_SET_ID] = route.boardSetId
                savedStateHandle[ROUTE_MODE] = route.mode.name
            }
        }
        _state.update { it.copy(route = route) }
    }

    private fun setShowCreateDialog(show: Boolean) {
        savedStateHandle[SHOW_CREATE_DIALOG] = show
        _state.update { it.copy(showCreateDialog = show) }
    }

    private fun dismissCreateDialog() {
        setShowCreateDialog(false)
        updateCreateDraft(CreateBoardSetDraft())
    }

    private fun updateCreateDraft(draft: CreateBoardSetDraft) {
        savedStateHandle[CREATE_NAME] = draft.name
        savedStateHandle[CREATE_ROWS] = draft.rowsText
        savedStateHandle[CREATE_COLUMNS] = draft.columnsText
        savedStateHandle[CREATE_TEMPLATE] = draft.template.name
        savedStateHandle[CREATE_KEYBOARD_PRESET] = draft.keyboardPreset.name
        _state.update { it.copy(createDraft = draft) }
    }

    private fun setShowSettings(show: Boolean) {
        savedStateHandle[SHOW_SETTINGS] = show
        _state.update { it.copy(showSettings = show) }
    }

    private fun setDeleteTarget(id: String?) {
        savedStateHandle[DELETE_TARGET_ID] = id
        _state.update { it.copy(deleteTargetId = id) }
    }

    private fun clearProtectedDelete() {
        savedStateHandle[PENDING_DELETE_ID] = null
        savedStateHandle[SHOW_DELETE_ACCESS_DIALOG] = false
        _state.update {
            it.copy(
                pendingProtectedDeleteId = null,
                showDeleteAccessDialog = false,
            )
        }
    }

    private fun setStatus(message: BoardSetManagerMessage) {
        _state.update { it.copy(statusMessage = message) }
    }

    private fun sendEvent(event: BoardSetManagerEvent) {
        viewModelScope.launch { _events.send(event) }
    }

    private class BoardSetCreationException(message: String) : Exception(message)

    private companion object {
        const val ROUTE_BOARD_SET_ID = "board_set_manager.route.board_set_id"
        const val ROUTE_MODE = "board_set_manager.route.mode"
        const val SHOW_CREATE_DIALOG = "board_set_manager.dialog.create"
        const val CREATE_NAME = "board_set_manager.create.name"
        const val CREATE_ROWS = "board_set_manager.create.rows"
        const val CREATE_COLUMNS = "board_set_manager.create.columns"
        const val CREATE_TEMPLATE = "board_set_manager.create.template"
        const val CREATE_KEYBOARD_PRESET = "board_set_manager.create.keyboard_preset"
        const val SHOW_SETTINGS = "board_set_manager.dialog.settings"
        const val DELETE_TARGET_ID = "board_set_manager.dialog.delete_target_id"
        const val PENDING_DELETE_ID = "board_set_manager.dialog.pending_delete_id"
        const val SHOW_DELETE_ACCESS_DIALOG = "board_set_manager.dialog.delete_access"
    }
}
