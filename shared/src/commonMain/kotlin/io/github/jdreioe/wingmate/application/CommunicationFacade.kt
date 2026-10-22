package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.application.bloc.PhraseListStore
import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import io.github.jdreioe.wingmate.domain.loggingClassName
import kotlinx.coroutines.CancellationException

enum class HistoryStatus {
    Success,

    /** History could not be read; retrying may help. */
    PersistenceFailure,
}

/** History as Phrases for the History Page. A failure carries no Phrases, unlike an empty History. */
data class HistoryResult(
    val status: HistoryStatus,
    val phrases: List<Phrase> = emptyList(),
) {
    val isSuccess: Boolean get() = status == HistoryStatus.Success
    val isRetryable: Boolean get() = status == HistoryStatus.PersistenceFailure
}

/**
 * A feature-scoped native boundary around the phrase list store and the spoken
 * history. The single [PhraseListStore] instance injected here is the shared
 * source of truth for phrase editing, so native intents and observed state
 * always target the same store.
 */
class CommunicationFacade(
    private val phraseListStore: PhraseListStore,
    private val saidTextRepository: SaidTextRepository,
) {
    fun phraseListStore(): PhraseListStore = phraseListStore

    fun refreshPhrases() {
        phraseListStore.accept(PhraseListStore.Intent.Refresh)
    }

    /** The store persists asynchronously and reports failures through its state. */
    fun updatePhraseRecording(phraseId: String, recordingPath: String?) {
        phraseListStore.accept(PhraseListStore.Intent.UpdatePhraseRecording(id = phraseId, recordingPath = recordingPath))
    }

    /** Visible History in repository order, mapped as read-only Phrases for the History Page. */
    suspend fun history(): HistoryResult {
        val said = try {
            saidTextRepository.list()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            OperationalLogger.warn("history.list", "failed", exceptionClass = failure.loggingClassName())
            return HistoryResult(HistoryStatus.PersistenceFailure)
        }
        return HistoryResult(
            status = HistoryStatus.Success,
            phrases = said.filter { it.visibleInHistory }.map { s ->
                val createdAt = s.createdAt ?: s.date ?: 0L
                Phrase(
                    id = "history-" + (s.id?.toString() ?: createdAt.toString()),
                    text = s.saidText ?: "",
                    name = null,
                    backgroundColor = "#00000000",
                    parentId = null,
                    createdAt = createdAt,
                    recordingPath = s.audioFilePath,
                )
            },
        )
    }
}
