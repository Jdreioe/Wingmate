package io.github.jdreioe.wingmate.application.bloc

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import io.github.jdreioe.wingmate.application.usecase.AddCategoryUseCase
import io.github.jdreioe.wingmate.application.usecase.AddPhraseUseCase
import io.github.jdreioe.wingmate.application.usecase.DeletePhraseUseCase
import io.github.jdreioe.wingmate.application.usecase.GetPhrasesAndCategoriesUseCase
import io.github.jdreioe.wingmate.domain.Phrase
import kotlinx.coroutines.launch

class PhraseListStoreFactory(
    private val storeFactory: StoreFactory,
    private val getPhrasesAndCategoriesUseCase: GetPhrasesAndCategoriesUseCase,
    private val addPhraseUseCase: AddPhraseUseCase,
    private val addCategoryUseCase: AddCategoryUseCase,
    private val deletePhraseUseCase: DeletePhraseUseCase
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
                is PhraseListStore.Intent.AddCategory -> addCategory(intent.name)
                is PhraseListStore.Intent.SelectCategory -> dispatch(Msg.CategorySelected(intent.categoryId))
                is PhraseListStore.Intent.DeletePhrase -> deletePhrase(intent.phraseId)
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

        private fun addCategory(name: String) {
            scope.launch {
                try {
                    addCategoryUseCase(name)
                    loadPhrasesAndCategories()
                } catch (e: Exception) {
                    dispatch(Msg.ErrorOccurred(e.message ?: "Failed to add category"))
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
    }

    private object ReducerImpl : Reducer<PhraseListStore.State, Msg> {
        override fun PhraseListStore.State.reduce(msg: Msg): PhraseListStore.State =
            when (msg) {
                is Msg.PhrasesAndCategoriesLoaded -> copy(phrases = msg.phrases, categories = msg.categories, isLoading = false)
                is Msg.CategorySelected -> copy(selectedCategoryId = msg.categoryId)
                is Msg.PhraseAdded -> copy(phrases = phrases + msg.phrase)
                is Msg.CategoryAdded -> copy(categories = categories + msg.category)
                is Msg.PhraseDeleted -> copy(phrases = phrases.filterNot { it.id == msg.phraseId })
                is Msg.LoadingStarted -> copy(isLoading = true)
                is Msg.ErrorOccurred -> copy(error = msg.error, isLoading = false)
            }
    }
}

