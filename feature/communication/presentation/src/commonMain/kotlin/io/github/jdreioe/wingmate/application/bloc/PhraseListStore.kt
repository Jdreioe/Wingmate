package io.github.jdreioe.wingmate.application.bloc

import com.arkivanov.mvikotlin.core.store.Store
import io.github.jdreioe.wingmate.domain.Phrase
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
    /** Moves a Phrase or Category to [targetId]'s place in the repository order. */
    data class MovePhrase(val phraseId: String, val targetId: String) : Intent()
    /** Restores previously deleted Phrases (and Categories) in parent-first order for undo. */
    data class RestorePhrases(val phrases: List<Phrase>) : Intent()
    /** Undoes the confirmed deletion identified by [undoId], restoring items to their original indices. */
    data class UndoLastDeletion(val undoId: Long) : Intent()
    /** Clears the confirmed deletion undo entry identified by [undoId] without deleting any recording files. */
    data class DismissDeletionUndo(val undoId: Long) : Intent()
    }

    /** A confirmed deletion captured with original repository indices for ordered undo. */
    data class DeletedPhraseUndo(
        val id: Long,
        val phrases: List<Phrase>,
        val originalIndices: List<Int>,
        val includesSubItems: Boolean,
    )

    data class State(
        val phrases: List<Phrase> = emptyList(),
        val categories: List<Phrase> = emptyList(),
        val selectedCategoryId: String? = null,
        val isLoading: Boolean = false,
        val error: String? = null,
        val lastDeletedUndo: DeletedPhraseUndo? = null,
    )
}
