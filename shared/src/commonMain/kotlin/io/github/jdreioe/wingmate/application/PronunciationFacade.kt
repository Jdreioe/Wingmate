package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository
import io.github.jdreioe.wingmate.domain.PronunciationEntry
import io.github.jdreioe.wingmate.domain.loggingClassName
import kotlinx.coroutines.CancellationException

enum class PronunciationStatus {
    Success,

    /** The word or how it should sound is blank; nothing was saved. */
    ValidationFailure,

    /** The dictionary could not be read or written; retrying may help. */
    PersistenceFailure,
}

/** The outcome of a dictionary change. Failures keep the dictionary unchanged. */
data class PronunciationResult(val status: PronunciationStatus) {
    val isSuccess: Boolean get() = status == PronunciationStatus.Success
    val isRetryable: Boolean get() = status == PronunciationStatus.PersistenceFailure
}

/** The dictionary's entries, or why they could not be read. */
data class PronunciationListResult(
    val status: PronunciationStatus,
    val entries: List<PronunciationEntry> = emptyList(),
) {
    val isSuccess: Boolean get() = status == PronunciationStatus.Success
}

/**
 * Native boundary to the pronunciation dictionary. Coroutine cancellation still
 * propagates; words and phonemes never reach logs.
 */
class PronunciationFacade(
    private val repository: PronunciationDictionaryRepository,
) {
    suspend fun list(): PronunciationListResult =
        when (val entries = persist { repository.getAll() }) {
            null -> PronunciationListResult(PronunciationStatus.PersistenceFailure)
            else -> PronunciationListResult(PronunciationStatus.Success, entries)
        }

    suspend fun add(word: String, phoneme: String, alphabet: String): PronunciationResult {
        val entry = PronunciationEntry(word.trim(), phoneme.trim(), alphabet.trim())
        if (entry.word.isEmpty() || entry.phoneme.isEmpty() || entry.alphabet.isEmpty()) {
            return PronunciationResult(PronunciationStatus.ValidationFailure)
        }
        return changeResult(persist { repository.add(entry) } != null)
    }

    suspend fun delete(word: String): PronunciationResult =
        changeResult(persist { repository.delete(word) } != null)

    private fun changeResult(saved: Boolean) = PronunciationResult(
        if (saved) PronunciationStatus.Success else PronunciationStatus.PersistenceFailure,
    )

    /** Runs a repository call, returning null after logging a privacy-safe failure. */
    private suspend fun <T : Any> persist(block: suspend () -> T): T? = try {
        block()
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        OperationalLogger.warn("pronunciation.storage", "failed", exceptionClass = failure.loggingClassName())
        null
    }
}
