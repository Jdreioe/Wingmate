package io.github.jdreioe.wingmate.ui

import androidx.compose.runtime.Stable
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jdreioe.wingmate.application.*
import io.github.jdreioe.wingmate.application.usecase.DeletePhraseUseCase
import io.github.jdreioe.wingmate.domain.*
import io.github.jdreioe.wingmate.domain.obf.BoardActivationBehavior
import io.github.jdreioe.wingmate.domain.obf.BoardSetGraph
import io.github.jdreioe.wingmate.domain.obf.ObfButtonActionEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

internal enum class TypingDialog { Settings, AddPhrase, EditPhrase, AddCategory, CategoryMenu, DeleteCategory, Reset, Unlock }
internal enum class TypingLoadStatus { Loading, Ready, Failed }

private const val LIKELY_NEXT_LIMIT = 8

/** History and Likely next are read-only Pages of History entries. */
private val TypingPageSelection.showsHistory: Boolean
    get() = this == TypingPageSelection.History || this == TypingPageSelection.LikelyNext

@Stable
internal data class PhraseScreenState(
    val phrases: List<Phrase> = emptyList(),
    val phraseStatus: TypingLoadStatus = TypingLoadStatus.Loading,
    val communication: CommunicationSessionState = CommunicationSessionState(),
    val settings: Settings = Settings(),
    val template: BoardSetGraph? = null,
    val templateStatus: TypingLoadStatus = TypingLoadStatus.Loading,
    val categories: List<CategoryItem> = emptyList(),
    val categoryStatus: TypingLoadStatus = TypingLoadStatus.Loading,
    val history: List<SaidText> = emptyList(),
    /** Experimental Likely next suggestions, ranked from [history]. */
    val likelyNext: List<SaidText> = emptyList(),
    val historyFailed: Boolean = false,
    val boardFocusedCell: Pair<Int, Int>? = null,
    val selectedPage: TypingPageSelection = TypingPageSelection.AllPhrases,
    val lastPhraseCategoryId: String? = null,
    val dialog: TypingDialog? = null,
    val dialogPhraseId: String? = null,
    val phraseDraft: Phrase? = null,
    val dialogCategoryId: String? = null,
    val categoryName: String = "",
    val mutationInProgress: Boolean = false,
    val failedMutation: PhraseScreenAction? = null,
    val input: TextFieldValue = TextFieldValue(),
    val mathMode: Boolean = false,
    val voice: Voice? = null,
    val predictions: PredictionResult = PredictionResult(),
) {
    val mutationFailed: Boolean get() = failedMutation != null
    val selectedCategory: CategoryItem? get() = (selectedPage as? TypingPageSelection.Category)?.category
    val dialogPhrase: Phrase? get() = phraseDraft ?: phrases.find { it.id == dialogPhraseId }
    val dialogCategory: CategoryItem? get() = categories.find { it.id == dialogCategoryId }
    val isHistory: Boolean get() = settings.historyVisible && selectedPage == TypingPageSelection.History
    val likelyNextEnabled: Boolean get() = settings.historyVisible && settings.likelyNextPageEnabled
    val isLikelyNext: Boolean get() = likelyNextEnabled && selectedPage == TypingPageSelection.LikelyNext
    val showLikelyNext: Boolean get() = likelyNextEnabled && (likelyNext.isNotEmpty() || isLikelyNext)
    val likelyNextRowPhrases: List<Phrase> get() =
        if (!likelyNextEnabled || historyFailed) emptyList()
        else likelyNext.take(4).mapIndexed { index, item -> item.toTypingHistoryPhrase(index) }
    val vocabularyReady: Boolean get() = templateStatus == TypingLoadStatus.Ready &&
        categoryStatus == TypingLoadStatus.Ready && phraseStatus == TypingLoadStatus.Ready && !mutationInProgress
    val visiblePhrases: List<Phrase> get() = if (isHistory || isLikelyNext) emptyList() else phrases.filter {
        it.isGridPhrase() && (selectedCategory == null || it.parentId == selectedCategory?.id)
    }
    val compactPhrases: List<Phrase> get() = phrases.filter {
        it.isGridPhrase() && (lastPhraseCategoryId == null || it.parentId == lastPhraseCategoryId)
    }
    val secondaryRanges: List<TextRange> get() = communication.activeMessage.languageSpans
        .filter { it.languageTag == settings.secondaryLanguage }
        .map { TextRange(it.range.start, it.range.endExclusive) }
    val hasSecondaryLanguage: Boolean get() = settings.secondaryLanguage.isNotBlank() &&
        settings.secondaryLanguage != settings.primaryLanguage &&
        voice?.supportedLanguages.orEmpty().distinct().size > 1 &&
        settings.secondaryLanguage in voice?.supportedLanguages.orEmpty()
}

internal sealed interface PhraseScreenAction {
    data object RetryLoad : PhraseScreenAction
    data object RetryHistory : PhraseScreenAction
    data object RetryMutation : PhraseScreenAction
    data class EditInput(val value: TextFieldValue) : PhraseScreenAction
    data class MathMode(val enabled: Boolean) : PhraseScreenAction
    data class FocusCell(val cell: Pair<Int, Int>?) : PhraseScreenAction
    data class SelectPage(val page: TypingPageSelection) : PhraseScreenAction
    data class ActivatePhrase(val phrase: Phrase) : PhraseScreenAction
    data class ActivateLikelyNext(val phrase: Phrase) : PhraseScreenAction
    data class Control(val effect: ObfButtonActionEffect) : PhraseScreenAction
    data class RequestDialog(val dialog: TypingDialog, val phraseId: String? = null, val categoryId: String? = null) : PhraseScreenAction
    data object DismissDialog : PhraseScreenAction
    data object Unlocked : PhraseScreenAction
    data class CategoryName(val name: String) : PhraseScreenAction
    data object SaveCategory : PhraseScreenAction
    data class MoveCategory(val categoryId: String, val target: Int) : PhraseScreenAction
    data object DeleteCategory : PhraseScreenAction
    data class SavePhrase(val phrase: Phrase) : PhraseScreenAction
    data class DeletePhrase(val id: String) : PhraseScreenAction
    data class MovePhrase(val movedId: String, val targetId: String) : PhraseScreenAction
    data class UndoDelete(val deletionId: Long) : PhraseScreenAction
    data class ForgetDelete(val deletionId: Long) : PhraseScreenAction
    data object Reset : PhraseScreenAction
    data class CompleteWord(val word: String) : PhraseScreenAction
    data class InsertPrediction(val text: String) : PhraseScreenAction
}

internal sealed interface PhraseScreenEvent {
    data class Deleted(val deletionId: Long) : PhraseScreenEvent
    data object ContentUnavailable : PhraseScreenEvent
}

/** The Android adapter owns I/O; the ViewModel can be exercised without Koin or a device. */
internal interface PhraseScreenOperations {
    val communication: StateFlow<CommunicationSessionState>
    val settings: StateFlow<Settings>
    fun communicate(action: CommunicationAction)
    suspend fun phrases(): List<Phrase>
    suspend fun savePhrase(phrase: Phrase, editing: Boolean)
    suspend fun deletePhrase(id: String)
    suspend fun movePhrase(from: Int, to: Int)
    suspend fun template(columns: Int, reset: Boolean = false): BoardSetGraph
    suspend fun categories(): List<CategoryItem>
    suspend fun addCategory(category: CategoryItem): CategoryItem
    suspend fun moveCategory(from: Int, to: Int)
    suspend fun deleteCategory(id: String)
    suspend fun history(): List<SaidText>
    suspend fun voice(): Voice?
    suspend fun requiresUnlock(): Boolean
    fun predictions(text: String): Flow<PredictionResult>
    fun report(name: String, vararg properties: Pair<String, String>)
}

internal class DefaultPhraseScreenOperations(
    private val session: CommunicationSession,
    settingsManager: SettingsStateManager,
    private val typing: TypingScreenUseCase,
    private val category: CategoryUseCase,
    private val deletePhraseUseCase: DeletePhraseUseCase,
    private val phraseUseCase: PhraseUseCase,
    private val saidRepository: SaidTextRepository,
    private val voices: VoiceUseCase,
    private val editingAccess: EditingAccessController?,
    private val prediction: TextPredictionService?,
    private val reporter: FeatureUsageReporter,
) : PhraseScreenOperations {
    override val communication = session.state
    override val settings = settingsManager.settings
    override fun communicate(action: CommunicationAction) { session.accept(action) }
    override suspend fun phrases() = withContext(Dispatchers.IO) { phraseUseCase.list() }
    override suspend fun savePhrase(phrase: Phrase, editing: Boolean) = withContext(Dispatchers.IO) {
        if (editing) phraseUseCase.update(phrase) else phraseUseCase.add(phrase)
        report(if (editing) FeatureUsageEvents.PHRASE_EDITED else FeatureUsageEvents.PHRASE_ADDED,
            "has_category" to (!phrase.parentId.isNullOrBlank()).toString(),
            "has_recording" to (!phrase.recordingPath.isNullOrBlank()).toString())
    }
    override suspend fun deletePhrase(id: String) = withContext(Dispatchers.IO) {
        deletePhraseUseCase(id)
        report(FeatureUsageEvents.PHRASE_DELETED, "source" to "typing")
    }
    override suspend fun movePhrase(from: Int, to: Int) = withContext(Dispatchers.IO) {
        phraseUseCase.move(from, to)
        report(FeatureUsageEvents.PHRASE_MOVED, "from_index" to from.toString(), "to_index" to to.toString())
    }
    override suspend fun template(columns: Int, reset: Boolean): BoardSetGraph = withContext(Dispatchers.IO) {
        if (reset) typing.reset(columns) else typing.getOrCreate(columns)
    }
    override suspend fun categories() = withContext(Dispatchers.IO) { category.list() }
    override suspend fun addCategory(category: CategoryItem) = withContext(Dispatchers.IO) { this@DefaultPhraseScreenOperations.category.add(category) }
    override suspend fun moveCategory(from: Int, to: Int) = withContext(Dispatchers.IO) { category.move(from, to) }
    override suspend fun deleteCategory(id: String) = withContext(Dispatchers.IO) {
        deletePhraseUseCase(id)
        report(FeatureUsageEvents.CATEGORY_DELETED, "source" to "typing")
    }
    override suspend fun history() = withContext(Dispatchers.IO) { saidRepository.list() }
    override suspend fun voice() = withContext(Dispatchers.IO) { voices.selected() }
    override suspend fun requiresUnlock() = editingAccess?.requiresUnlock() == true
    override fun predictions(text: String) = prediction?.predictions(text, 5, 4) ?: flowOf(PredictionResult())
    override fun report(name: String, vararg properties: Pair<String, String>) { reporter.reportEvent(name, *properties) }
}

internal class PhraseScreenViewModel(
    private val savedState: SavedStateHandle,
    private val operations: PhraseScreenOperations,
) : ViewModel() {
    private val _state = MutableStateFlow(PhraseScreenState(
        settings = operations.settings.value,
        communication = operations.communication.value,
        input = TextFieldValue(operations.communication.value.activeMessage.displayText,
            TextRange(savedState["cursor"] ?: operations.communication.value.activeMessage.displayText.length,
                savedState["cursorEnd"] ?: operations.communication.value.activeMessage.displayText.length)),
        mathMode = savedState["math"] ?: false,
        categoryName = savedState["categoryName"] ?: "",
        lastPhraseCategoryId = savedState["lastCategory"],
        dialog = savedState.get<String>("dialog")?.let { saved ->
            val name = if (saved == TypingDialog.Unlock.name) savedState["pendingDialog"] else saved
            TypingDialog.entries.find { it.name == name && it != TypingDialog.Unlock }
        },
        dialogPhraseId = savedState["dialogPhrase"],
        phraseDraft = savedState.get<String>("phraseDraft")?.let { encoded ->
            try { Json.decodeFromString<Phrase>(encoded) } catch (_: IllegalArgumentException) { null }
        },
        dialogCategoryId = savedState["dialogCategory"],
    ))
    val state = _state.asStateFlow()
    private val eventChannel = Channel<PhraseScreenEvent>(Channel.UNLIMITED)
    val events = eventChannel.receiveAsFlow()
    private data class PendingMutation(val action: PhraseScreenAction, val dialog: TypingDialog?)
    private var pendingAction: PendingMutation? = null
    private var authorizationJob: Job? = null
    private var selectionRestored = false
    private var nextDeletionId = 0L
    private val deletedPhrases = mutableMapOf<Long, List<Phrase>>()
    private val mutations = Mutex()
    private var templateJob: Job? = null
    private var historyJob: Job? = null
    private var predictionJob: Job? = null
    private var voiceJob: Job? = null
    private var categoryJob: Job? = null
    private var phraseJob: Job? = null

    init {
        viewModelScope.launch {
            operations.settings.collect { settings ->
                val previous = state.value.settings
                if (!supportsMathMode(settings.ttsEngine)) savedState["math"] = false
                _state.update { it.copy(settings = settings, mathMode = it.mathMode && supportsMathMode(settings.ttsEngine)) }
                if (!settings.historyVisible && state.value.selectedPage == TypingPageSelection.History) selectPage(TypingPageSelection.AllPhrases)
                if (!state.value.likelyNextEnabled && state.value.selectedPage == TypingPageSelection.LikelyNext) selectPage(TypingPageSelection.AllPhrases)
                if (previous.gridColumns != settings.gridColumns || state.value.template == null) loadTemplate()
                if (previous.historyVisible != settings.historyVisible ||
                    previous.likelyNextPageEnabled != settings.likelyNextPageEnabled) loadHistory(clearCurrent = true)
                loadVoice()
            }
        }
        viewModelScope.launch {
            var previousRequest: Long? = null
            var previousText: String? = null
            operations.communication.collect { communication ->
                val text = communication.activeMessage.displayText
                _state.update { current ->
                    val input = when {
                        // The persisted Message can arrive after this ViewModel was created.
                        !selectionRestored && communication.isInitialized -> TextFieldValue(
                            text,
                            TextRange(savedState["cursor"] ?: text.length, savedState["cursorEnd"] ?: text.length),
                        )
                        current.input.text == text -> current.input
                        else -> TextFieldValue(text, TextRange(current.input.selection.start.coerceIn(0, text.length)))
                    }
                    current.copy(communication = communication, input = input)
                }
                if (communication.isInitialized) selectionRestored = true
                if (text != previousText) { previousText = text; predict(text) }
                if (previousRequest != null && communication.currentSpeechRequestId == null) loadHistory()
                previousRequest = communication.currentSpeechRequestId
            }
        }
        loadPhrases()
        loadCategories()
        loadHistory()
    }

    fun onAction(action: PhraseScreenAction) {
        when (action) {
            PhraseScreenAction.RetryLoad -> { loadPhrases(); loadTemplate(); loadCategories(); loadVoice() }
            PhraseScreenAction.RetryHistory -> loadHistory()
            PhraseScreenAction.RetryMutation -> state.value.failedMutation?.let(::onAction)
            is PhraseScreenAction.EditInput -> editInput(action.value)
            is PhraseScreenAction.MathMode -> {
                savedState["math"] = action.enabled && supportsMathMode(state.value.settings.ttsEngine)
                _state.update { it.copy(mathMode = savedState["math"] ?: false) }
            }
            is PhraseScreenAction.FocusCell -> _state.update { it.copy(boardFocusedCell = action.cell) }
            is PhraseScreenAction.SelectPage -> selectPage(action.page)
            is PhraseScreenAction.ActivatePhrase -> activatePhrase(action.phrase)
            is PhraseScreenAction.ActivateLikelyNext -> {
                if (action.phrase in state.value.likelyNextRowPhrases) activatePhrase(action.phrase, "likely_next")
            }
            is PhraseScreenAction.Control -> control(action.effect)
            is PhraseScreenAction.RequestDialog -> {
                if (action.dialog == TypingDialog.Settings) {
                    operations.report(FeatureUsageEvents.SETTINGS_UPDATED, "action" to "open_app_settings")
                    showDialog(action)
                }
                else authorize(action) { showDialog(action) }
            }
            PhraseScreenAction.DismissDialog -> {
                if (!state.value.mutationInProgress) {
                    val refreshHistory = state.value.dialog == TypingDialog.Settings
                    authorizationJob?.cancel()
                    val previousDialog = pendingAction?.dialog.takeIf { state.value.dialog == TypingDialog.Unlock }
                    pendingAction = null
                    savedState["pendingDialog"] = null
                    setDialog(previousDialog)
                    loadVoice()
                    if (refreshHistory) loadHistory(clearCurrent = true)
                }
            }
            PhraseScreenAction.Unlocked -> {
                val pending = pendingAction ?: return
                pendingAction = null
                savedState["pendingDialog"] = null
                setDialog(pending.dialog)
                onAction(pending.action)
            }
            is PhraseScreenAction.CategoryName -> {
                savedState["categoryName"] = action.name
                _state.update { it.copy(categoryName = action.name) }
            }
            PhraseScreenAction.SaveCategory -> authorize(action) { mutate(action) {
                val name = state.value.categoryName.trim()
                if (name.isBlank()) return@mutate
                val added = operations.addCategory(CategoryItem(id = "", name = name, selectedLanguage = state.value.settings.primaryLanguage))
                _state.update { it.copy(categories = it.categories + added) }
                selectPage(TypingPageSelection.Category(added))
                loadCategories()
                savedState["categoryName"] = ""
                _state.update { it.copy(categoryName = "") }
                setDialog(null)
            } }
            is PhraseScreenAction.MoveCategory -> authorize(action) {
                mutate(action) {
                    val from = state.value.categories.indexOfFirst { it.id == action.categoryId }
                    if (from >= 0 && action.target in state.value.categories.indices) operations.moveCategory(from, action.target)
                    loadCategories()
                    setDialog(null)
                }
            }
            PhraseScreenAction.DeleteCategory -> authorize(action) {
                val id = state.value.dialogCategoryId ?: return@authorize
                mutate(action) {
                    operations.deleteCategory(id)
                    loadCategories()
                    setDialog(null)
                }
            }
            is PhraseScreenAction.SavePhrase -> {
                savedState["phraseDraft"] = Json.encodeToString(action.phrase)
                _state.update { it.copy(phraseDraft = action.phrase) }
                authorize(action) {
                    val editing = state.value.dialog == TypingDialog.EditPhrase
                    mutate(action) { operations.savePhrase(action.phrase, editing); setDialog(null) }
                }
            }
            is PhraseScreenAction.DeletePhrase -> authorize(action) {
                val removed = phraseSubtree(state.value.phrases, action.id)
                if (removed.isNotEmpty()) mutate(action) {
                    operations.deletePhrase(action.id)
                    val deletionId = ++nextDeletionId
                    deletedPhrases[deletionId] = removed
                    setDialog(null)
                    eventChannel.trySend(PhraseScreenEvent.Deleted(deletionId))
                }
            }
            is PhraseScreenAction.MovePhrase -> authorize(action) {
                val items = state.value.phrases
                val from = items.indexOfFirst { it.id == action.movedId }
                val to = items.indexOfFirst { it.id == action.targetId }
                if (from >= 0 && to >= 0 && from != to) mutate(action) { operations.movePhrase(from, to) }
            }
            is PhraseScreenAction.UndoDelete -> authorize(action) {
                mutate(action) {
                    // Each snackbar owns a batch. Retries resume after the last restored node.
                    while (true) {
                        val remaining = deletedPhrases[action.deletionId].orEmpty()
                        val phrase = remaining.firstOrNull() ?: break
                        operations.savePhrase(phrase, editing = false)
                        deletedPhrases[action.deletionId] = remaining.drop(1)
                    }
                    deletedPhrases.remove(action.deletionId)
                }
            }
            is PhraseScreenAction.ForgetDelete -> { deletedPhrases.remove(action.deletionId) }
            PhraseScreenAction.Reset -> authorize(action) {
                mutate(action) {
                    val template = operations.template(state.value.settings.gridColumns, reset = true)
                    _state.update { it.copy(template = template, templateStatus = TypingLoadStatus.Ready) }
                    setDialog(null)
                }
            }
            is PhraseScreenAction.CompleteWord -> {
                val result = TextEditingPolicy.completeWord(state.value.input.text, state.value.input.selection.start, action.word)
                editInput(TextFieldValue(result.text, TextRange(result.cursor)))
            }
            is PhraseScreenAction.InsertPrediction -> {
                val result = TextEditingPolicy.insert(state.value.input.text, state.value.input.selection.start, action.text)
                editInput(TextFieldValue(result.text, TextRange(result.cursor)))
            }
        }
    }

    private fun editInput(value: TextFieldValue) {
        selectionRestored = true
        operations.communicate(Message.fromTextDiff(operations.communication.value.activeMessage.displayText, value.text, state.value.mathMode))
        savedState["cursor"] = value.selection.start
        savedState["cursorEnd"] = value.selection.end
        _state.update { it.copy(input = value) }
    }

    private fun activatePhrase(phrase: Phrase, source: String? = null) {
        val current = state.value
        val message = operations.communication.value.activeMessage
        val cursor = current.input.selection.start.coerceIn(0, message.displayText.length)
        val activation = message.activatePhrase(phrase, cursor,
            current.template?.boardSet?.screenSettings?.activationBehavior ?: BoardActivationBehavior.SpeakOnly,
            current.settings.speechPolicy)
        if (activation.message != message) {
            operations.communicate(CommunicationAction.ReplaceMessage(activation.message))
            val text = activation.message.displayText
            _state.update { it.copy(input = TextFieldValue(text, TextRange((cursor + phrase.text.length).coerceAtMost(text.length)))) }
            val insertionSource = source ?: when {
                current.isHistory -> "history"
                current.isLikelyNext -> "likely_next"
                else -> "typing_screen"
            }
            operations.report(FeatureUsageEvents.PHRASE_INSERTED, "source" to insertionSource)
        }
        if (activation.shouldSpeak) {
            if (phrase.linkedBoardId != null) selectPage(TypingPageSelection.Category(CategoryItem(phrase.id, phrase.text, isFolder = true)))
            else {
                operations.communicate(CommunicationAction.SpeakPart(
                    MessagePart(phrase.text, phrase.name?.ifBlank { null } ?: phrase.text,
                        source = MessagePartSource.Phrase(phrase.id), recordingPath = phrase.recordingPath), current.voice))
                operations.report(FeatureUsageEvents.PHRASE_PLAYED, "source" to "grid", "used_recording" to (phrase.recordingPath != null).toString())
            }
        }
    }

    private fun control(effect: ObfButtonActionEffect) {
        val current = state.value
        val input = current.input
        val range = TextEditingPolicy.normalize(TextSpan(input.selection.start, input.selection.end), input.text.length)
        fun replace(text: String, cursor: Int) = editInput(TextFieldValue(text, TextRange(cursor)))
        when (effect) {
            is ObfButtonActionEffect.AppendText -> replace(input.text.replaceRange(range.start, range.endExclusive, effect.text), range.start + effect.text.length)
            is ObfButtonActionEffect.WrapSelection -> {
                val selected = input.text.substring(range.start, range.endExclusive)
                val replacement = effect.prefix + selected + effect.suffix
                replace(input.text.replaceRange(range.start, range.endExclusive, replacement),
                    range.start + if (selected.isEmpty()) effect.prefix.length else replacement.length)
            }
            ObfButtonActionEffect.Backspace -> when {
                range.start != range.endExclusive -> replace(input.text.removeRange(range.start, range.endExclusive), range.start)
                range.start > 0 -> replace(input.text.removeRange(range.start - 1, range.start), range.start - 1)
                else -> Unit
            }
            ObfButtonActionEffect.Clear -> replace("", 0)
            ObfButtonActionEffect.Speak -> if (input.text.isNotBlank()) {
                operations.communicate(CommunicationAction.SpeakActive(current.voice?.copy(mathMode = current.mathMode)))
                operations.report(FeatureUsageEvents.PLAYBACK_PLAY, "source" to "input", "has_secondary_ranges" to current.secondaryRanges.isNotEmpty().toString())
            }
            ObfButtonActionEffect.Pause -> transport(CommunicationAction.Pause, FeatureUsageEvents.PLAYBACK_PAUSE)
            ObfButtonActionEffect.Resume -> transport(CommunicationAction.Resume, FeatureUsageEvents.PLAYBACK_RESUME)
            ObfButtonActionEffect.Stop -> transport(CommunicationAction.Stop, FeatureUsageEvents.PLAYBACK_STOP)
            ObfButtonActionEffect.ToggleSecondaryLanguage -> if (current.hasSecondaryLanguage && range.start != range.endExclusive) {
                operations.communicate(CommunicationAction.ToggleLanguage(range, current.settings.secondaryLanguage))
                operations.report(FeatureUsageEvents.PLAYBACK_SECONDARY_TOGGLE, "enabled" to (!TextEditingPolicy.isFullyCovered(range,
                    current.secondaryRanges.map { TextSpan(it.start, it.end) }, input.text.length)).toString())
            }
            ObfButtonActionEffect.SwapHeldMessage -> {
                operations.communicate(CommunicationAction.SwapHeldMessage)
                val text = operations.communication.value.activeMessage.displayText
                _state.update { it.copy(input = TextFieldValue(text, TextRange(text.length))) }
                operations.report(FeatureUsageEvents.PLAYBACK_ON_THAT_THOUGHT, "action" to if (current.communication.heldMessage != null) "resume" else "pin")
            }
            ObfButtonActionEffect.NativeKeyboard -> Unit
            ObfButtonActionEffect.Home, ObfButtonActionEffect.Predictions -> Unit
            is ObfButtonActionEffect.Unsupported -> eventChannel.trySend(PhraseScreenEvent.ContentUnavailable)
        }
    }

    private fun transport(action: CommunicationAction, event: String) {
        operations.communicate(action)
        operations.report(event, "source" to "input")
    }

    private fun selectPage(page: TypingPageSelection) {
        savedState["pageKind"] = when (page) {
            TypingPageSelection.AllPhrases -> "all"
            TypingPageSelection.History -> "history"
            TypingPageSelection.LikelyNext -> "likely_next"
            is TypingPageSelection.Category -> "category"
        }
        savedState["categoryPageId"] = (page as? TypingPageSelection.Category)?.category?.id
        if (!page.showsHistory) savedState["lastCategory"] = (page as? TypingPageSelection.Category)?.category?.id
        _state.update { it.copy(selectedPage = page, boardFocusedCell = it.boardFocusedCell.takeIf { _ -> it.selectedPage == page }, lastPhraseCategoryId = if (page.showsHistory) it.lastPhraseCategoryId else (page as? TypingPageSelection.Category)?.category?.id) }
        if (page.showsHistory) loadHistory()
    }

    private fun setDialog(dialog: TypingDialog?, phraseId: String? = state.value.dialogPhraseId, categoryId: String? = state.value.dialogCategoryId) {
        savedState["dialog"] = dialog?.name
        savedState["dialogPhrase"] = phraseId
        savedState["dialogCategory"] = categoryId
        if (dialog == null) savedState["phraseDraft"] = null
        _state.update { it.copy(dialog = dialog, dialogPhraseId = phraseId, dialogCategoryId = categoryId,
            phraseDraft = if (dialog == null) null else it.phraseDraft, failedMutation = null) }
    }

    private fun showDialog(action: PhraseScreenAction.RequestDialog) {
        savedState["phraseDraft"] = null
        _state.update { it.copy(phraseDraft = null) }
        setDialog(action.dialog, action.phraseId, action.categoryId)
    }

    private fun authorize(action: PhraseScreenAction, block: () -> Unit) {
        if (!state.value.vocabularyReady && action !is PhraseScreenAction.UndoDelete) {
            eventChannel.trySend(PhraseScreenEvent.ContentUnavailable)
            return
        }
        authorizationJob?.cancel()
        authorizationJob = viewModelScope.launch {
            try {
                if (operations.requiresUnlock()) {
                    pendingAction = PendingMutation(action, state.value.dialog)
                    savedState["pendingDialog"] = state.value.dialog?.name
                    setDialog(TypingDialog.Unlock)
                }
                else block()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { eventChannel.trySend(PhraseScreenEvent.ContentUnavailable) }
        }
    }

    private fun mutate(action: PhraseScreenAction, block: suspend () -> Unit) {
        viewModelScope.launch {
            mutations.withLock {
                _state.update { it.copy(mutationInProgress = true, failedMutation = null) }
                try { block() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { _state.update { it.copy(failedMutation = action) } }
                finally {
                    loadPhrases()
                    _state.update { it.copy(mutationInProgress = false) }
                }
            }
        }
    }

    private fun loadPhrases() {
        phraseJob?.cancel()
        phraseJob = viewModelScope.launch {
            _state.update { it.copy(phraseStatus = TypingLoadStatus.Loading) }
            try {
                val phrases = operations.phrases()
                _state.update { it.copy(phrases = phrases, phraseStatus = TypingLoadStatus.Ready) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { it.copy(phraseStatus = TypingLoadStatus.Failed) } }
        }
    }

    private fun loadTemplate() {
        templateJob?.cancel()
        templateJob = viewModelScope.launch {
            _state.update { it.copy(templateStatus = TypingLoadStatus.Loading) }
            try {
                val graph = operations.template(state.value.settings.gridColumns)
                _state.update { it.copy(template = graph, templateStatus = TypingLoadStatus.Ready) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { it.copy(templateStatus = TypingLoadStatus.Failed) } }
        }
    }

    private fun loadCategories() {
        categoryJob?.cancel()
        categoryJob = viewModelScope.launch {
            _state.update { it.copy(categoryStatus = TypingLoadStatus.Loading) }
            try { refreshCategories() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { it.copy(categoryStatus = TypingLoadStatus.Failed) } }
        }
    }

    private suspend fun refreshCategories() {
        val categories = operations.categories()
        val page = when (savedState.get<String>("pageKind")) {
            "history" -> if (state.value.settings.historyVisible) TypingPageSelection.History else TypingPageSelection.AllPhrases
            "likely_next" -> if (state.value.likelyNextEnabled) TypingPageSelection.LikelyNext else TypingPageSelection.AllPhrases
            "category" -> categories.find { it.id == savedState.get<String>("categoryPageId") }?.let { TypingPageSelection.Category(it) } ?: TypingPageSelection.AllPhrases
            else -> TypingPageSelection.AllPhrases
        }
        _state.update { it.copy(categories = categories, categoryStatus = TypingLoadStatus.Ready) }
        selectPage(page)
    }

    private fun loadHistory(clearCurrent: Boolean = false) {
        historyJob?.cancel()
        if (clearCurrent) _state.update { it.copy(history = emptyList(), likelyNext = emptyList()) }
        historyJob = viewModelScope.launch {
            try {
                val history = operations.history().filter { it.visibleInHistory }.sortedByDescending { it.date ?: it.createdAt ?: 0L }
                val likelyNext = likelyNextMessages(history, LIKELY_NEXT_LIMIT)
                _state.update { it.copy(history = history, likelyNext = likelyNext, historyFailed = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { it.copy(historyFailed = true) } }
        }
    }

    private fun loadVoice() {
        voiceJob?.cancel()
        voiceJob = viewModelScope.launch {
            try { val voice = operations.voice(); _state.update { it.copy(voice = voice) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The session can still use its default voice. */ }
        }
    }

    private fun predict(text: String) {
        predictionJob?.cancel()
        _state.update { it.copy(predictions = PredictionResult()) }
        if (text.isBlank()) return
        predictionJob = viewModelScope.launch {
            delay(250)
            try { operations.predictions(text).collect { predictions -> _state.update { it.copy(predictions = predictions) } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Predictions are optional; typing remains available. */ }
        }
    }
}
