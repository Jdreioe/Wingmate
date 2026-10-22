package io.github.jdreioe.wingmate.ui

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import io.github.jdreioe.wingmate.domain.PhraseRecordingService
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.infrastructure.ImageCacher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Recording lifecycle in the phrase editor. Transitional states block duplicate recorder calls. */
enum class PhraseRecordingStatus { Idle, Starting, Recording, Stopping }

/** User-facing media failures. Deliberately carries no exception text or file paths. */
enum class PhraseMediaError { RecordingUnavailable, StartFailed, FinalizeFailed, PlaybackFailed }

/** Image and recording state supplied to [AddPhraseDialogContent]. */
data class PhraseMediaState(
    val imageUrl: String = "",
    val recordingPath: String = "",
    val recordingSupported: Boolean = false,
    val recordingStatus: PhraseRecordingStatus = PhraseRecordingStatus.Idle,
    val error: PhraseMediaError? = null,
)

/**
 * Owns the phrase editor's image and recording media so the form content never touches
 * services. Create one per draft at the dialog root, run its suspend operations in the
 * dialog's scope, and call [release] when the dialog leaves composition so an active
 * recorder is never orphaned.
 */
class PhraseMediaController(
    private val recordings: PhraseRecordingService?,
    private val speech: SpeechService?,
    private val images: ImageCacher?,
    /** Outlives the dialog; only used to stop a recorder left running at disposal. */
    private val releaseScope: CoroutineScope,
    /** Phrase or draft ID used to name recording files. Never phrase text. */
    private val recordingHint: String,
    imageUrl: String = "",
    recordingPath: String = "",
) {
    private val _state = MutableStateFlow(
        PhraseMediaState(
            imageUrl = imageUrl,
            recordingPath = recordingPath,
            recordingSupported = recordings?.isSupported == true,
        )
    )
    val state: StateFlow<PhraseMediaState> = _state.asStateFlow()

    fun setImage(url: String) = _state.update { it.copy(imageUrl = url) }

    fun clearImage() = setImage("")

    /** Uses a locally cached copy of a chosen symbol, falling back to its remote URL. */
    suspend fun selectSymbol(url: String) {
        val cacher = images ?: return setImage(url)
        setImage(attempt { cacher.getCachedImagePath(url) }.getOrDefault(url))
    }

    /** Starts recording. Callers must have microphone permission. */
    suspend fun startRecording() {
        if (_state.value.recordingStatus != PhraseRecordingStatus.Idle) return
        val service = recordings?.takeIf { it.isSupported }
            ?: return _state.update { it.copy(error = PhraseMediaError.RecordingUnavailable) }
        _state.update { it.copy(recordingStatus = PhraseRecordingStatus.Starting, error = null) }
        val result = try {
            attempt { service.startRecording(recordingHint).getOrThrow() }
        } catch (e: CancellationException) {
            stopAfterCancellation(service)
            throw e
        }
        _state.update {
            if (result.isSuccess) it.copy(recordingStatus = PhraseRecordingStatus.Recording)
            else it.copy(recordingStatus = PhraseRecordingStatus.Idle, error = PhraseMediaError.StartFailed)
        }
    }

    /**
     * Finishes an active recording so it becomes the draft's recording. Returns false when
     * the draft is not ready to save: finalizing failed (the error is shown) or the recorder
     * is still starting or stopping.
     */
    suspend fun finishRecording(): Boolean {
        when (_state.value.recordingStatus) {
            PhraseRecordingStatus.Idle -> return true
            PhraseRecordingStatus.Starting, PhraseRecordingStatus.Stopping -> return false
            PhraseRecordingStatus.Recording -> Unit
        }
        val service = recordings ?: return true
        _state.update { it.copy(recordingStatus = PhraseRecordingStatus.Stopping, error = null) }
        val result = try {
            attempt { service.stopRecording().getOrThrow() }
        } catch (e: CancellationException) {
            stopAfterCancellation(service)
            throw e
        }
        _state.update { current ->
            result.fold(
                onSuccess = { current.copy(recordingStatus = PhraseRecordingStatus.Idle, recordingPath = it) },
                onFailure = { current.copy(recordingStatus = PhraseRecordingStatus.Idle, error = PhraseMediaError.FinalizeFailed) },
            )
        }
        return result.isSuccess
    }

    /** Stops an active recording without keeping it, before the dialog is cancelled. */
    suspend fun discardRecording() {
        if (_state.value.recordingStatus != PhraseRecordingStatus.Recording) return
        val service = recordings ?: return
        // Releasing the microphone must finish even if the dialog closes meanwhile.
        withContext(NonCancellable) { attempt { service.stopRecording() } }
        _state.update { it.copy(recordingStatus = PhraseRecordingStatus.Idle) }
    }

    fun clearRecording() = _state.update { it.copy(recordingPath = "", error = null) }

    suspend fun playRecording(textForHistory: String?) {
        val path = _state.value.recordingPath.takeIf { it.isNotBlank() } ?: return
        _state.update { it.copy(error = null) }
        val played = attempt { speech?.speakRecordedAudio(path, textForHistory) }.getOrNull() == true
        if (!played) _state.update { it.copy(error = PhraseMediaError.PlaybackFailed) }
    }

    /**
     * Stops a recorder left running when the dialog is disposed. Operations still in flight
     * stop their own recorder when the dialog scope cancels them.
     */
    fun release() {
        val service = recordings ?: return
        if (_state.value.recordingStatus != PhraseRecordingStatus.Recording) return
        _state.update { it.copy(recordingStatus = PhraseRecordingStatus.Idle) }
        releaseScope.launch { attempt { service.stopRecording() } }
    }

    /**
     * Releases a recorder that may still be running after its start or stop was cancelled.
     * Cleanup failures are swallowed so the original cancellation keeps propagating.
     */
    private suspend fun stopAfterCancellation(service: PhraseRecordingService) {
        _state.update { it.copy(recordingStatus = PhraseRecordingStatus.Idle) }
        withContext(NonCancellable) { attempt { if (service.isRecording()) service.stopRecording() else null } }
    }

    companion object {
        /** Restores the chosen image and finished recording; an unfinished recording is not restorable. */
        fun saver(create: (imageUrl: String, recordingPath: String) -> PhraseMediaController): Saver<PhraseMediaController, Any> =
            listSaver(
                save = { listOf(it.state.value.imageUrl, it.state.value.recordingPath) },
                restore = { create(it[0], it[1]) },
            )
    }
}

/** Like [runCatching], but lets coroutine cancellation propagate. */
private inline fun <T> attempt(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
