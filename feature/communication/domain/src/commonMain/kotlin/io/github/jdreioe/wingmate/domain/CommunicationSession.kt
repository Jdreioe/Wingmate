package io.github.jdreioe.wingmate.domain

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data class CommunicationSessionSnapshot(
    val activeMessage: Message = Message(),
    val heldMessage: Message? = null,
)

enum class CommunicationPersistenceStatus {
    Loading,
    Saved,
    Saving,
    Failed,
}

enum class CommunicationPlaybackStatus {
    Idle,
    Preparing,
    Playing,
    Paused,
}

enum class CommunicationFailureKind {
    Persistence,
    Playback,
}

data class CommunicationFailure(
    val id: Long,
    val kind: CommunicationFailureKind,
)

data class CommunicationSessionState(
    val snapshot: CommunicationSessionSnapshot = CommunicationSessionSnapshot(),
    val revision: Long = 0,
    val isInitialized: Boolean = false,
    val persistenceStatus: CommunicationPersistenceStatus = CommunicationPersistenceStatus.Loading,
    val playbackStatus: CommunicationPlaybackStatus = CommunicationPlaybackStatus.Idle,
    val currentSpeechRequestId: Long? = null,
    val queuedSpeechCount: Int = 0,
    val lastFailure: CommunicationFailure? = null,
) {
    val activeMessage: Message get() = snapshot.activeMessage
    val heldMessage: Message? get() = snapshot.heldMessage
}

sealed interface CommunicationAction {
    data class ReplaceRange(
        val range: TextSpan,
        val replacement: String,
        val languageTag: String? = null,
        val mathMode: Boolean = false,
    ) : CommunicationAction

    data class InsertPart(
        val cursor: Int,
        val part: MessagePart,
    ) : CommunicationAction

    data class AppendPart(
        val part: MessagePart,
        val spellingMode: Boolean,
    ) : CommunicationAction

    data class ReplaceMessage(val message: Message) : CommunicationAction
    data class RemoveLastPart(val spellingMode: Boolean) : CommunicationAction
    data class ToggleLanguage(val range: TextSpan, val languageTag: String) : CommunicationAction
    data class ImportIfEmpty(val snapshot: CommunicationSessionSnapshot) : CommunicationAction
    data object Clear : CommunicationAction
    data object SwapHeldMessage : CommunicationAction

    data class SpeakActive(
        val voice: Voice?,
        val cacheAudio: Boolean = true,
    ) : CommunicationAction

    data class SpeakPart(
        val part: MessagePart,
        val voice: Voice?,
        val cacheAudio: Boolean = false,
        val rateOverride: Double? = null,
    ) : CommunicationAction

    data object Pause : CommunicationAction
    data object Resume : CommunicationAction
    data object Stop : CommunicationAction
    data object RetryPersistence : CommunicationAction
    data object DismissFailure : CommunicationAction
}

enum class CommunicationStorageError {
    Unavailable,
    InvalidData,
    WriteFailed,
}

sealed interface CommunicationStorageResult<out T> {
    data class Success<T>(val value: T) : CommunicationStorageResult<T>
    data class Failure(val error: CommunicationStorageError) : CommunicationStorageResult<Nothing>
}

interface CommunicationSessionDataSource {
    suspend fun load(): CommunicationStorageResult<CommunicationSessionSnapshot>
    suspend fun save(snapshot: CommunicationSessionSnapshot): CommunicationStorageResult<Unit>
}

interface CommunicationSession {
    val state: StateFlow<CommunicationSessionState>
    fun accept(action: CommunicationAction)
    suspend fun reloadAfterRestore()
}
