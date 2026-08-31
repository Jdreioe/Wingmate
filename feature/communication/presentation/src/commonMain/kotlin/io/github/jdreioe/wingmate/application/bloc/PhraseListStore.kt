package io.github.jdreioe.wingmate.application.bloc

import com.arkivanov.mvikotlin.core.store.Store
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.CategoryItem
import io.github.jdreioe.wingmate.domain.Voice
import kotlinx.coroutines.flow.Flow

interface PhraseListStore : Store<PhraseListStore.Intent, PhraseListStore.State, Nothing> {
    /**
     * The store's state as a plain [Flow], so clients observe it without
     * importing MVIKotlin types. This member intentionally shadows the
     * MVIKotlin coroutines `states` extension for typed receivers.
     */
    val states: Flow<State>

    sealed class Intent {
        data object Refresh : Intent()
        data class AddPhrase(
            val text: String,
            val name: String? = null,
            val imageUrl: String? = null,
            val recordingPath: String? = null
        ) : Intent()
        data class AddCategory(val name: String) : Intent()
        data class SelectCategory(val categoryId: String?) : Intent()
        data class DeletePhrase(val phraseId: String) : Intent()
    data class DeleteCategory(val categoryId: String) : Intent()
    data class UpdatePhrase(
        val id: String,
        val text: String?,
        val name: String?,
        val imageUrl: String? = null
    ) : Intent()
    data class UpdatePhraseRecording(val id: String, val recordingPath: String?) : Intent()

    /**
     * Full phrase edit following the shared update contract: a null field
     * keeps the existing value, an explicit blank string removes it, and
     * a non-blank value replaces it.
     */
    data class UpdatePhraseDetails(
        val id: String,
        val text: String? = null,
        val name: String? = null,
        val imageUrl: String? = null,
        val recordingPath: String? = null,
        val parentId: String? = null,
        val linkedBoardId: String? = null,
        val isHidden: Boolean? = null
    ) : Intent()
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
