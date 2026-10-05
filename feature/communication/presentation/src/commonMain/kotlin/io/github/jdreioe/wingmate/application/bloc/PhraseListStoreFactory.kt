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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
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
        data class CategorySelected(val categoryId: String?) : Msg()
        data class ErrorOccurred(val error: String) : Msg()
        data class PhraseUpdated(val phrase: Phrase) : Msg()
    }

    private inner class ExecutorImpl : CoroutineExecutor<PhraseListStore.Intent, Unit, PhraseListStore.State, Msg, Nothing>() {
        override fun executeAction(action: Unit, getState: () -> PhraseListStore.State) {
            loadPhrasesAndCategories()
        }

        override fun executeIntent(intent: PhraseListStore.Intent, getState: () -> PhraseListStore.State) {
            when (intent) {
                PhraseListStore.Intent.Refresh -> loadPhrasesAndCategories()
                is PhraseListStore.Intent.AddPhrase -> addPhrase(intent, getState().selectedCategoryId)
                is PhraseListStore.Intent.AddCategory -> addCategory(intent.name, getState().selectedCategoryId)
                is PhraseListStore.Intent.SelectCategory -> dispatch(Msg.CategorySelected(intent.categoryId))
                is PhraseListStore.Intent.DeletePhrase -> deletePhrase(intent.phraseId)
                is PhraseListStore.Intent.DeleteCategory -> deleteCategory(intent.categoryId)
                is PhraseListStore.Intent.UpdatePhrase -> updatePhrase(intent.id, intent.text, intent.name, intent.imageUrl)
                is PhraseListStore.Intent.UpdatePhraseRecording -> updatePhraseRecording(intent.id, intent.recordingPath)
                is PhraseListStore.Intent.MovePhrase -> movePhrase(intent.phraseId, intent.targetId)
            }
        }

        private fun loadPhrasesAndCategories() {
            dispatch(Msg.Loading)
            scope.launch {
                try {
                    val (phrases, folderPhrases) = getPhrasesAndCategoriesUseCase()
                    // Store state holds plain Phrases, so unwrap the folder-Phrases.
                    dispatch(Msg.PhrasesAndCategoriesLoaded(phrases, folderPhrases.map { it.phrase }))
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    dispatch(Msg.ErrorOccurred(e.message ?: "Failed to load data"))
                }
            }
        }

        private fun addPhrase(intent: PhraseListStore.Intent.AddPhrase, categoryId: String?) {
            scope.launch {
                try {
                    addPhraseUseCase(
                        text = intent.text,
                        categoryId = categoryId,
                        name = intent.name,
                        imageUrl = intent.imageUrl,
                        recordingPath = intent.recordingPath,
                    )
                    loadPhrasesAndCategories()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    dispatch(Msg.ErrorOccurred(e.message ?: "Failed to add phrase"))
                }
            }
        }

        private fun addCategory(name: String, parentCategoryId: String?) {
            scope.launch {
                try {
                    val trimmed = name.trim()
                    if (trimmed.isBlank()) {
                        dispatch(Msg.ErrorOccurred("Category name cannot be empty"))
                        return@launch
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
                    loadPhrasesAndCategories()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    dispatch(Msg.ErrorOccurred(e.message ?: "Failed to add category"))
                }
            }
        }

        private fun deletePhrase(phraseId: String) {
            scope.launch {
                try {
                    deletePhraseUseCase(phraseId)
                    loadPhrasesAndCategories()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    dispatch(Msg.ErrorOccurred(e.message ?: "Failed to delete phrase"))
                }
            }
        }

        private fun deleteCategory(categoryId: String) {
            scope.launch {
                try {
                    deletePhraseUseCase(categoryId)
                    loadPhrasesAndCategories()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    dispatch(Msg.ErrorOccurred(e.message ?: "Failed to delete category"))
                }
            }
        }

        private fun updatePhrase(id: String, text: String?, name: String?, imageUrl: String?) {
            scope.launch {
                try {
                    val updated = updatePhraseUseCase(id, text, name, imageUrl, null)
                    loadPhrasesAndCategories()
                    dispatch(Msg.PhraseUpdated(updated))
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    dispatch(Msg.ErrorOccurred(e.message ?: "Failed to update phrase"))
                }
            }
        }

        private fun updatePhraseRecording(id: String, recordingPath: String?) {
            scope.launch {
                try {
                    // The dedicated recording intent always sets the field: a null
                    // path from the facade (and "") is an explicit clear, matching
                    // the native editors that send nil to remove a recording.
                    val updated = updatePhraseUseCase(id, null, null, null, recordingPath ?: "")
                    loadPhrasesAndCategories()
                    dispatch(Msg.PhraseUpdated(updated))
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    dispatch(Msg.ErrorOccurred(e.message ?: "Failed to update recording path"))
                }
            }
        }

        private fun movePhrase(phraseId: String, targetId: String) {
            scope.launch {
                try {
                    // Indices are into the whole repository, which interleaves Phrases and Categories.
                    val all = phraseRepository.getAll()
                    val from = all.indexOfFirst { it.id == phraseId }
                    val to = all.indexOfFirst { it.id == targetId }
                    if (from >= 0 && to >= 0 && from != to) phraseRepository.move(from, to)
                    loadPhrasesAndCategories()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    dispatch(Msg.ErrorOccurred(e.message ?: "Failed to move phrase"))
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
