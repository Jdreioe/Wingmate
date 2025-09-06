package io.github.jdreioe.wingmate.application.bloc

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import io.github.jdreioe.wingmate.application.usecase.AddPhraseUseCase
import io.github.jdreioe.wingmate.application.usecase.DeletePhraseUseCase
import io.github.jdreioe.wingmate.application.usecase.GetPhrasesAndCategoriesUseCase
import io.github.jdreioe.wingmate.application.usecase.UpdatePhraseUseCase
import io.github.jdreioe.wingmate.application.usecase.MovePhraseUseCase
import io.github.jdreioe.wingmate.application.usecase.GetAllItemsUseCase
import io.github.jdreioe.wingmate.domain.Phrase
import kotlinx.coroutines.launch

class PhraseListStoreFactory(
    private val storeFactory: StoreFactory,
    private val getPhrasesAndCategoriesUseCase: GetPhrasesAndCategoriesUseCase,
    private val addPhraseUseCase: AddPhraseUseCase,
    private val deletePhraseUseCase: DeletePhraseUseCase,
    private val updatePhraseUseCase: UpdatePhraseUseCase,
    private val movePhraseUseCase: MovePhraseUseCase,
    private val getAllItemsUseCase: GetAllItemsUseCase
) {
    fun create(): PhraseListStore =
        object : PhraseListStore, Store<PhraseListStore.Intent, PhraseListStore.State, Nothing> by storeFactory.create(
            name = "PhraseListStore",
            initialState = PhraseListStore.State(),
            bootstrapper = SimpleBootstrapper(Unit),
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl
        ) {}

    private sealed class Msg {
        data class PhrasesAndCategoriesLoaded(val phrases: List<Phrase>, val categories: List<Phrase>) : Msg()
        data class CategorySelected(val categoryId: String?) : Msg()
        data class PhraseAdded(val phrase: Phrase) : Msg()
        data class CategoryAdded(val category: Phrase) : Msg()
        data class PhraseDeleted(val phraseId: String) : Msg()
    data class CategoryDeleted(val categoryId: String) : Msg()
    data class PhrasesReordered(val list: List<Phrase>) : Msg()
    data class CategoriesReordered(val list: List<Phrase>) : Msg()
    data class PhraseUpdated(val phrase: Phrase) : Msg()
        data object LoadingStarted : Msg()
        data class ErrorOccurred(val error: String) : Msg()
    }

    private inner class ExecutorImpl : CoroutineExecutor<PhraseListStore.Intent, Unit, PhraseListStore.State, Msg, Nothing>() {
        override fun executeAction(action: Unit, getState: () -> PhraseListStore.State) {
            loadPhrasesAndCategories()
        }

        override fun executeIntent(intent: PhraseListStore.Intent, getState: () -> PhraseListStore.State) {
            when (intent) {
                is PhraseListStore.Intent.AddPhrase -> addPhrase(intent.text, getState().selectedCategoryId)
                is PhraseListStore.Intent.AddCategory -> { /* no-op after model unification */ }
                is PhraseListStore.Intent.SelectCategory -> dispatch(Msg.CategorySelected(intent.categoryId))
                is PhraseListStore.Intent.DeletePhrase -> deletePhrase(intent.phraseId)
                is PhraseListStore.Intent.DeleteCategory -> { /* no-op */ }
                is PhraseListStore.Intent.UpdatePhrase -> updatePhrase(intent.id, intent.text, intent.name)
                is PhraseListStore.Intent.UpdatePhraseRecording -> updatePhraseRecording(intent.id, intent.recordingPath)
                is PhraseListStore.Intent.MovePhrase -> movePhrase(intent.fromIndex, intent.toIndex)
                is PhraseListStore.Intent.MoveCategory -> moveCategory(intent.fromIndex, intent.toIndex)
            }
        }

        private fun loadPhrasesAndCategories() {
            scope.launch {
                try {
                    dispatch(Msg.LoadingStarted)
                    val (phrases, categories) = getPhrasesAndCategoriesUseCase()
                    dispatch(Msg.PhrasesAndCategoriesLoaded(phrases, categories))
                } catch (e: Exception) {
                    dispatch(Msg.ErrorOccurred(e.message ?: "Failed to load data"))
                }
            }
        }

        private fun addPhrase(text: String, categoryId: String?) {
            scope.launch {
                try {
                    addPhraseUseCase(text, categoryId)
                    loadPhrasesAndCategories()
                } catch (e: Exception) {
                    dispatch(Msg.ErrorOccurred(e.message ?: "Failed to add phrase"))
                }
            }
        }


        private fun deletePhrase(phraseId: String) {
            scope.launch {
                try {
                    deletePhraseUseCase(phraseId)
                    // Either optimistically update or reload; keep it simple and reload
                    loadPhrasesAndCategories()
                } catch (e: Exception) {
                    dispatch(Msg.ErrorOccurred(e.message ?: "Failed to delete phrase"))
                }
            }
        }


        private fun updatePhrase(id: String, text: String?, name: String?) {
            scope.launch {
                try {
                    val updated = updatePhraseUseCase(id, text, name, null)
                    // Just reload to keep ordering consistent
                    loadPhrasesAndCategories()
                    dispatch(Msg.PhraseUpdated(updated))
                } catch (e: Exception) {
                    dispatch(Msg.ErrorOccurred(e.message ?: "Failed to update phrase"))
                }
            }
        }

        private fun updatePhraseRecording(id: String, recordingPath: String?) {
            scope.launch {
                try {
                    val updated = updatePhraseUseCase(id, null, null, recordingPath)
                    loadPhrasesAndCategories()
                    dispatch(Msg.PhraseUpdated(updated))
                } catch (e: Exception) {
                    dispatch(Msg.ErrorOccurred(e.message ?: "Failed to update recording path"))
                }
            }
        }

    private fun movePhrase(fromIndex: Int, toIndex: Int) {
            scope.launch {
                try {
            // Persist using repository move; indices refer to full ordered list
            movePhraseUseCase(fromIndex, toIndex)
                    loadPhrasesAndCategories()
                } catch (e: Exception) {
                    dispatch(Msg.ErrorOccurred(e.message ?: "Failed to move phrase"))
                }
            }
        }

        private fun moveCategory(fromIndex: Int, toIndex: Int) {
            scope.launch {
                try {
                    // Categories are stored as phrases; for now, reorder in memory and reload
                    loadPhrasesAndCategories()
                } catch (e: Exception) {
                    dispatch(Msg.ErrorOccurred(e.message ?: "Failed to move category"))
                }
            }
        }
    }

    private object ReducerImpl : Reducer<PhraseListStore.State, Msg> {
        override fun PhraseListStore.State.reduce(msg: Msg): PhraseListStore.State =
            when (msg) {
                is Msg.PhrasesAndCategoriesLoaded -> copy(phrases = msg.phrases, categories = msg.categories, isLoading = false)
                is Msg.CategorySelected -> copy(selectedCategoryId = msg.categoryId)
                is Msg.PhraseAdded -> copy(phrases = phrases + msg.phrase)
                is Msg.CategoryAdded -> copy(categories = categories + msg.category)
                is Msg.PhraseDeleted -> copy(phrases = phrases.filterNot { it.id == msg.phraseId })
                is Msg.CategoryDeleted -> copy(
                    categories = categories.filterNot { it.id == msg.categoryId },
                    selectedCategoryId = selectedCategoryId?.takeIf { it != msg.categoryId }
                )
                is Msg.PhrasesReordered -> copy(phrases = msg.list)
                is Msg.CategoriesReordered -> copy(categories = msg.list)
                is Msg.PhraseUpdated -> copy(phrases = phrases.map { if (it.id == msg.phrase.id) msg.phrase else it })
                is Msg.LoadingStarted -> copy(isLoading = true)
                is Msg.ErrorOccurred -> copy(error = msg.error, isLoading = false)
            }
    }
}

