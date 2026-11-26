package io.github.jdreioe.wingmate.application.bloc

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.extensions.coroutines.states as coroutinesStates
import io.github.jdreioe.wingmate.application.usecase.AddPhraseUseCase
import io.github.jdreioe.wingmate.application.usecase.DeletePhraseUseCase
import io.github.jdreioe.wingmate.application.usecase.GetPhrasesAndCategoriesUseCase
import io.github.jdreioe.wingmate.application.usecase.UpdatePhraseUseCase
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.PhraseRepository
import io.github.jdreioe.wingmate.domain.phraseSubtree
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

class PhraseListStoreFactory(
    private val storeFactory: StoreFactory,
    private val getPhrasesAndCategoriesUseCase: GetPhrasesAndCategoriesUseCase,
    private val addPhraseUseCase: AddPhraseUseCase,
    private val deletePhraseUseCase: DeletePhraseUseCase,
    private val updatePhraseUseCase: UpdatePhraseUseCase,
    private val phraseRepository: PhraseRepository
) {
    fun create(): PhraseListStore =
        object : PhraseListStore, Store<PhraseListStore.Intent, PhraseListStore.State, Nothing> by storeFactory.create(
            name = "PhraseListStore",
            initialState = PhraseListStore.State(),
            bootstrapper = SimpleBootstrapper(Unit),
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl
        ) {
            override val states: Flow<PhraseListStore.State>
                get() = coroutinesStates
        }

    private sealed class Msg {
        data object Loading : Msg()
        data class PhrasesAndCategoriesLoaded(val phrases: List<Phrase>, val categories: List<Phrase>) : Msg()
        data class DeletionCompleted(
            val phrases: List<Phrase>,
            val categories: List<Phrase>,
            val undo: PhraseListStore.DeletedPhraseUndo?,
        ) : Msg()
        data class UndoRestoreStarted(val undoId: Long) : Msg()
        data class UndoRestoreFailed(val undoId: Long, val error: String) : Msg()
        data class UndoRestored(val phrases: List<Phrase>, val categories: List<Phrase>) : Msg()
        data class UndoDismissed(val undoId: Long) : Msg()
        data class CategorySelected(val categoryId: String?) : Msg()
        data class ErrorOccurred(val error: String) : Msg()
        data class PhraseUpdated(val phrase: Phrase) : Msg()
    }

    private inner class ExecutorImpl : CoroutineExecutor<PhraseListStore.Intent, Unit, PhraseListStore.State, Msg, Nothing>() {
        private val mutationMutex = Mutex()
        private var undoSequence = 0L

        override fun executeAction(action: Unit) {
            loadPhrasesAndCategories()
        }

        override fun executeIntent(intent: PhraseListStore.Intent) {
            when (intent) {
                PhraseListStore.Intent.Refresh -> loadPhrasesAndCategories()
                is PhraseListStore.Intent.AddPhrase -> addPhrase(intent, state().selectedCategoryId)
                is PhraseListStore.Intent.AddCategory -> addCategory(intent.name, state().selectedCategoryId)
                is PhraseListStore.Intent.SelectCategory -> dispatch(Msg.CategorySelected(intent.categoryId))
                is PhraseListStore.Intent.DeletePhrase -> deleteNode(intent.phraseId, "Failed to delete phrase")
                is PhraseListStore.Intent.DeleteCategory -> deleteNode(intent.categoryId, "Failed to delete category")
                is PhraseListStore.Intent.UpdatePhrase -> updatePhrase(intent.id, intent.text, intent.name, intent.imageUrl)
                is PhraseListStore.Intent.UpdatePhraseRecording -> updatePhraseRecording(intent.id, intent.recordingPath)
                is PhraseListStore.Intent.MovePhrase -> movePhrase(intent.phraseId, intent.targetId)
                is PhraseListStore.Intent.RestorePhrases -> restorePhrases(intent.phrases, emptyList())
                is PhraseListStore.Intent.UndoLastDeletion -> undoLastDeletion(intent.undoId)
                is PhraseListStore.Intent.DismissDeletionUndo -> dispatch(Msg.UndoDismissed(intent.undoId))
            }
        }

        private suspend fun reloadUnlocked(): Pair<List<Phrase>, List<Phrase>> {
            val (phrases, folderPhrases) = getPhrasesAndCategoriesUseCase()
            // Store state holds plain Phrases, so unwrap the folder-Phrases.
            return phrases to folderPhrases.map { it.phrase }
        }

        private fun loadPhrasesAndCategories() {
            dispatch(Msg.Loading)
            scope.launch {
                mutationMutex.withLock {
                    try {
                        val (phrases, categories) = reloadUnlocked()
                        dispatch(Msg.PhrasesAndCategoriesLoaded(phrases, categories))
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        dispatch(Msg.ErrorOccurred(e.message ?: "Failed to load data"))
                    }
                }
            }
        }

        private fun addPhrase(intent: PhraseListStore.Intent.AddPhrase, categoryId: String?) {
            scope.launch {
                mutationMutex.withLock {
                    try {
                        addPhraseUseCase(
                            text = intent.text,
                            categoryId = categoryId,
                            name = intent.name,
                            imageUrl = intent.imageUrl,
                            recordingPath = intent.recordingPath,
                        )
                        val (phrases, categories) = reloadUnlocked()
                        dispatch(Msg.PhrasesAndCategoriesLoaded(phrases, categories))
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        dispatch(Msg.ErrorOccurred(e.message ?: "Failed to add phrase"))
                    }
                }
            }
        }

        private fun addCategory(name: String, parentCategoryId: String?) {
            scope.launch {
                mutationMutex.withLock {
                    try {
                        val trimmed = name.trim()
                        if (trimmed.isBlank()) {
                            dispatch(Msg.ErrorOccurred("Category name cannot be empty"))
                            return@withLock
                        }

                        val folderId = newId()
                        phraseRepository.add(
                            Phrase(
                                id = folderId,
                                text = trimmed,
                                linkedBoardId = folderId,
                                parentId = parentCategoryId,
                                createdAt = 0L,
                                isGridItem = false
                            )
                        )
                        val (phrases, categories) = reloadUnlocked()
                        dispatch(Msg.PhrasesAndCategoriesLoaded(phrases, categories))
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        dispatch(Msg.ErrorOccurred(e.message ?: "Failed to add category"))
                    }
                }
            }
        }

        private fun deleteNode(rootId: String, failureMessage: String) {
            scope.launch {
                mutationMutex.withLock {
                    try {
                        val beforeAll = phraseRepository.getAll()
                        val removed = phraseSubtree(beforeAll, rootId)
                        val indexById = beforeAll.mapIndexed { index, phrase -> phrase.id to index }.toMap()
                        val originalIndices = removed.map { indexById[it.id] ?: beforeAll.size }
                        deletePhraseUseCase(rootId)
                        val (phrases, categories) = reloadUnlocked()
                        val undo = removed.takeIf { it.isNotEmpty() }?.let {
                            PhraseListStore.DeletedPhraseUndo(
                                id = ++undoSequence,
                                phrases = it,
                                originalIndices = originalIndices,
                                includesSubItems = it.size > 1,
                            )
                        }
                        dispatch(Msg.DeletionCompleted(phrases, categories, undo))
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        dispatch(Msg.ErrorOccurred(e.message ?: failureMessage))
                    }
                }
            }
        }

        private fun updatePhrase(id: String, text: String?, name: String?, imageUrl: String?) {
            scope.launch {
                mutationMutex.withLock {
                    try {
                        val updated = updatePhraseUseCase(id, text, name, imageUrl, null)
                        val (phrases, categories) = reloadUnlocked()
                        dispatch(Msg.PhrasesAndCategoriesLoaded(phrases, categories))
                        dispatch(Msg.PhraseUpdated(updated))
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        dispatch(Msg.ErrorOccurred(e.message ?: "Failed to update phrase"))
                    }
                }
            }
        }

        private fun updatePhraseRecording(id: String, recordingPath: String?) {
            scope.launch {
                mutationMutex.withLock {
                    try {
                        // The dedicated recording intent always sets the field: a null
                        // path from the facade (and "") is an explicit clear, matching
                        // the native editors that send nil to remove a recording.
                        val updated = updatePhraseUseCase(id, null, null, null, recordingPath ?: "")
                        val (phrases, categories) = reloadUnlocked()
                        dispatch(Msg.PhrasesAndCategoriesLoaded(phrases, categories))
                        dispatch(Msg.PhraseUpdated(updated))
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        dispatch(Msg.ErrorOccurred(e.message ?: "Failed to update recording path"))
                    }
                }
            }
        }

        private fun movePhrase(phraseId: String, targetId: String) {
            scope.launch {
                mutationMutex.withLock {
                    try {
                        // Indices are into the whole repository, which interleaves Phrases and Categories.
                        val all = phraseRepository.getAll()
                        val from = all.indexOfFirst { it.id == phraseId }
                        val to = all.indexOfFirst { it.id == targetId }
                        if (from >= 0 && to >= 0 && from != to) phraseRepository.move(from, to)
                        val (phrases, categories) = reloadUnlocked()
                        dispatch(Msg.PhrasesAndCategoriesLoaded(phrases, categories))
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        dispatch(Msg.ErrorOccurred(e.message ?: "Failed to move phrase"))
                    }
                }
            }
        }

        private fun undoLastDeletion(undoId: Long) {
            if (state().lastDeletedUndo?.id == undoId) {
                dispatch(Msg.UndoRestoreStarted(undoId))
            }
            scope.launch {
                mutationMutex.withLock {
                    val undo = state().lastDeletedUndo ?: return@withLock
                    if (undo.id != undoId) return@withLock
                    restorePhrasesUnlocked(undo.phrases, undo.originalIndices, undoId = undoId)
                }
            }
        }

        private fun restorePhrases(phrases: List<Phrase>, originalIndices: List<Int>) {
            if (phrases.isEmpty()) return
            scope.launch {
                mutationMutex.withLock {
                    restorePhrasesUnlocked(phrases, originalIndices, undoId = null)
                }
            }
        }

        private suspend fun restorePhrasesUnlocked(
            phrases: List<Phrase>,
            originalIndices: List<Int>,
            undoId: Long?,
        ) {
            try {
                val baseIndex = phraseRepository.getAll().size
                val entries = phrases.mapIndexed { index, phrase ->
                    phrase to (originalIndices.getOrNull(index) ?: (baseIndex + index))
                }
                phraseRepository.restoreAtIndices(entries)
                val (loadedPhrases, categories) = reloadUnlocked()
                dispatch(Msg.UndoRestored(loadedPhrases, categories))
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                val message = e.message ?: "Failed to restore phrase"
                if (undoId != null) {
                    dispatch(Msg.UndoRestoreFailed(undoId, message))
                } else {
                    dispatch(Msg.ErrorOccurred(message))
                }
            }
        }
    }

    private object ReducerImpl : Reducer<PhraseListStore.State, Msg> {
        override fun PhraseListStore.State.reduce(msg: Msg): PhraseListStore.State =
            when (msg) {
                Msg.Loading -> copy(isLoading = true, error = null)
                is Msg.PhrasesAndCategoriesLoaded -> copy(
                    phrases = msg.phrases,
                    categories = msg.categories,
                    isLoading = false,
                    error = null,
                )
                is Msg.DeletionCompleted -> copy(
                    phrases = msg.phrases,
                    categories = msg.categories,
                    isLoading = false,
                    error = null,
                    lastDeletedUndo = msg.undo,
                )
                is Msg.UndoRestoreStarted -> if (lastDeletedUndo?.id == msg.undoId) {
                    copy(
                        lastDeletedUndo = lastDeletedUndo.copy(
                            restoreStatus = PhraseListStore.UndoRestoreStatus.Restoring
                        ),
                    )
                } else {
                    this
                }
                is Msg.UndoRestoreFailed -> if (lastDeletedUndo?.id == msg.undoId) {
                    copy(
                        error = msg.error,
                        isLoading = false,
                        lastDeletedUndo = lastDeletedUndo.copy(
                            restoreStatus = PhraseListStore.UndoRestoreStatus.Failed
                        ),
                    )
                } else {
                    copy(error = msg.error, isLoading = false)
                }
                is Msg.UndoRestored -> copy(
                    phrases = msg.phrases,
                    categories = msg.categories,
                    isLoading = false,
                    error = null,
                    lastDeletedUndo = null,
                )
                is Msg.UndoDismissed -> if (
                    lastDeletedUndo?.id == msg.undoId &&
                    lastDeletedUndo.restoreStatus != PhraseListStore.UndoRestoreStatus.Restoring
                ) {
                    copy(lastDeletedUndo = null)
                } else {
                    this
                }
                is Msg.CategorySelected -> copy(selectedCategoryId = msg.categoryId)
                is Msg.ErrorOccurred -> copy(error = msg.error, isLoading = false)
                is Msg.PhraseUpdated -> copy(phrases = phrases.map { if (it.id == msg.phrase.id) msg.phrase else it })
            }
    }

    private fun newId(): String {
        val alphabet = "0123456789abcdef"
        return buildString(32) {
            repeat(32) {
                append(alphabet[Random.nextInt(alphabet.length)])
            }
        }
    }
}
