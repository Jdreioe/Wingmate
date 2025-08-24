package io.github.jdreioe.wingmate.application.bloc

import com.arkivanov.mvikotlin.core.store.Store
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.CategoryItem
import io.github.jdreioe.wingmate.domain.Voice

interface PhraseListStore : Store<PhraseListStore.Intent, PhraseListStore.State, Nothing> {
    sealed class Intent {
        data class AddPhrase(val text: String) : Intent()
        data class AddCategory(val name: String) : Intent()
        data class SelectCategory(val categoryId: String?) : Intent()
        data class DeletePhrase(val phraseId: String) : Intent()
    data class DeleteCategory(val categoryId: String) : Intent()
    data class UpdatePhrase(val id: String, val text: String?, val name: String?) : Intent()
    data class MoveCategory(val fromIndex: Int, val toIndex: Int) : Intent()
    data class MovePhrase(val fromIndex: Int, val toIndex: Int) : Intent()
    }

    data class State(
        val phrases: List<Phrase> = emptyList(),
        val categories: List<Phrase> = emptyList(),
        val selectedCategoryId: String? = null,
        val isLoading: Boolean = false,
        val error: String? = null
    )
}
