package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.application.bloc.PhraseListStore
import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import io.github.jdreioe.wingmate.domain.loggingClassName
import kotlinx.coroutines.CancellationException

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

    fun updatePhraseRecording(phraseId: String, recordingPath: String?) {
        try {
            phraseListStore.accept(PhraseListStore.Intent.UpdatePhraseRecording(id = phraseId, recordingPath = recordingPath))
        } catch (t: Throwable) {
            OperationalLogger.warn("swift_bridge.phrase_recording_update", "failed", exceptionClass = t.loggingClassName())
        }
    }

    /** Returns the said items mapped as Phrase objects for easy Swift UI rendering. */
    suspend fun listHistoryAsPhrases(): List<Phrase> {
        return try {
            val said = saidTextRepository.list().filter { it.visibleInHistory }
            val now = 0L
            said.map { s ->
                Phrase(
                    id = "history-" + (s.id?.toString() ?: (s.createdAt ?: s.date ?: now).toString()),
                    text = s.saidText ?: "",
                    name = null,
                    backgroundColor = "#00000000",
                    parentId = null,
                    createdAt = (s.createdAt ?: s.date ?: now),
                    recordingPath = s.audioFilePath,
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
