package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.CommunicationAction
import io.github.jdreioe.wingmate.domain.CommunicationFailure
import io.github.jdreioe.wingmate.domain.CommunicationFailureKind
import io.github.jdreioe.wingmate.domain.CommunicationPersistenceStatus
import io.github.jdreioe.wingmate.domain.CommunicationPlaybackStatus
import io.github.jdreioe.wingmate.domain.CommunicationSession
import io.github.jdreioe.wingmate.domain.CommunicationSessionDataSource
import io.github.jdreioe.wingmate.domain.CommunicationSessionSnapshot
import io.github.jdreioe.wingmate.domain.CommunicationSessionState
import io.github.jdreioe.wingmate.domain.CommunicationStorageResult
import io.github.jdreioe.wingmate.domain.Message
import io.github.jdreioe.wingmate.domain.MessagePart
import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.SpeechPlaybackStatus
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.withLanguageOverride
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Owns the current communication and serializes persistence and playback behind
 * one state/action interface. Callers never wait for either side effect.
 */
class QueuedCommunicationSession(
    private val dataSource: CommunicationSessionDataSource,
    private val speechService: SpeechService,
    private val saidTextRepository: SaidTextRepository,
    private val currentSettings: () -> Settings,
    private val scope: CoroutineScope,
) : CommunicationSession {
    private val mutableState = MutableStateFlow(CommunicationSessionState())
    override val state: StateFlow<CommunicationSessionState> = mutableState.asStateFlow()

    private val initialization = CompletableDeferred<Unit>()
    private val saveRequests = Channel<Unit>(Channel.CONFLATED)
    private val speechRequests = Channel<SpeechRequest>(Channel.UNLIMITED)
    private val stopGeneration = MutableStateFlow(0L)
    private val pauseRequested = MutableStateFlow(false)
    private var speechRequestSequence = 0L
    private var failureSequence = 0L

    init {
        scope.launch {
            loadInitialSnapshot()
            initialization.complete(Unit)
        }
        scope.launch {
            initialization.await()
            for (ignored in saveRequests) persistLatestSnapshot()
        }
        scope.launch {
            for (request in speechRequests) play(request)
        }
    }

    override fun accept(action: CommunicationAction) {
        when (action) {
            is CommunicationAction.ReplaceRange -> mutateSnapshot { snapshot ->
                snapshot.copy(
                    activeMessage = snapshot.activeMessage.replaceRange(
                        start = action.range.start,
                        endExclusive = action.range.endExclusive,
                        replacement = action.replacement.takeIf(String::isNotEmpty)?.let { text ->
                            MessagePart(
                                displayText = text,
                                languageTag = action.languageTag,
                                mathMode = action.mathMode,
                            )
                        },
                    )
                )
            }
            is CommunicationAction.InsertPart -> mutateSnapshot { snapshot ->
                snapshot.copy(activeMessage = snapshot.activeMessage.insertPart(action.cursor, action.part))
            }
            is CommunicationAction.AppendPart -> mutateSnapshot { snapshot ->
                snapshot.copy(
                    activeMessage = snapshot.activeMessage.appendPart(action.part, action.spellingMode)
                )
            }
            is CommunicationAction.ReplaceMessage -> mutateSnapshot { snapshot ->
                snapshot.copy(activeMessage = action.message.immutableSnapshot())
            }
            is CommunicationAction.RemoveLastPart -> mutateSnapshot { snapshot ->
                snapshot.copy(
                    activeMessage = snapshot.activeMessage.removeLastPart(action.spellingMode)
                )
            }
            is CommunicationAction.ToggleLanguage -> mutateSnapshot { snapshot ->
                snapshot.copy(
                    activeMessage = snapshot.activeMessage.toggleLanguage(action.range, action.languageTag)
                )
            }
            is CommunicationAction.ImportIfEmpty -> {
                if (state.value.isInitialized) {
                    mutateSnapshot { current ->
                        if (current.activeMessage.parts.isEmpty() && current.heldMessage == null) {
                            action.snapshot.immutableSnapshot()
                        } else {
                            current
                        }
                    }
                }
            }
            CommunicationAction.Clear -> mutateSnapshot { snapshot ->
                snapshot.copy(activeMessage = Message())
            }
            CommunicationAction.SwapHeldMessage -> mutateSnapshot { snapshot ->
                val heldMessage = snapshot.heldMessage
                if (heldMessage == null) {
                    CommunicationSessionSnapshot(
                        activeMessage = Message(),
                        heldMessage = snapshot.activeMessage,
                    )
                } else {
                    CommunicationSessionSnapshot(
                        activeMessage = heldMessage,
                        heldMessage = snapshot.activeMessage,
                    )
                }
            }
            is CommunicationAction.SpeakActive -> {
                val message = state.value.activeMessage
                enqueueSpeech(message, action.voice, action.cacheAudio, recordHistory = true)
            }
            is CommunicationAction.SpeakPart -> enqueueSpeech(
                message = Message(parts = listOf(action.part)),
                voice = action.voice,
                cacheAudio = action.cacheAudio,
                recordHistory = false,
                rateOverride = action.rateOverride,
            )
            CommunicationAction.Pause -> pause()
            CommunicationAction.Resume -> resume()
            CommunicationAction.Stop -> stop()
            CommunicationAction.RetryPersistence -> saveRequests.trySend(Unit)
            CommunicationAction.DismissFailure -> mutableState.update { current ->
                if (current.lastFailure?.kind == CommunicationFailureKind.Playback) {
                    current.copy(lastFailure = null)
                } else {
                    current
                }
            }
        }
    }

    override suspend fun reloadAfterRestore() {
        when (val loaded = dataSource.load()) {
            is CommunicationStorageResult.Success -> mutableState.update { current ->
                current.copy(
                    snapshot = loaded.value.immutableSnapshot(),
                    revision = current.revision + 1,
                    isInitialized = true,
                    persistenceStatus = CommunicationPersistenceStatus.Saved,
                    lastFailure = current.lastFailure?.takeUnless {
                        it.kind == CommunicationFailureKind.Persistence
                    },
                )
            }
            is CommunicationStorageResult.Failure -> markPersistenceFailure()
        }
    }

    private suspend fun loadInitialSnapshot() {
        when (val loaded = dataSource.load()) {
            is CommunicationStorageResult.Success -> mutableState.update { current ->
                if (current.revision == 0L) {
                    current.copy(
                        snapshot = loaded.value.immutableSnapshot(),
                        isInitialized = true,
                        persistenceStatus = CommunicationPersistenceStatus.Saved,
                    )
                } else {
                    current.copy(isInitialized = true)
                }
            }
            is CommunicationStorageResult.Failure -> {
                mutableState.update { it.copy(isInitialized = true) }
                markPersistenceFailure()
            }
        }
        if (state.value.revision > 0L) saveRequests.trySend(Unit)
    }

    private fun mutateSnapshot(
        transform: (CommunicationSessionSnapshot) -> CommunicationSessionSnapshot,
    ) {
        var changed = false
        mutableState.update { current ->
            val updated = transform(current.snapshot)
            if (updated == current.snapshot) {
                current
            } else {
                changed = true
                current.copy(
                    snapshot = updated,
                    revision = current.revision + 1,
                    persistenceStatus = CommunicationPersistenceStatus.Saving,
                )
            }
        }
        if (changed) saveRequests.trySend(Unit)
    }

    private suspend fun persistLatestSnapshot() {
        val revision = state.value.revision
        val snapshot = state.value.snapshot
        mutableState.update { current ->
            current.copy(persistenceStatus = CommunicationPersistenceStatus.Saving)
        }
        when (dataSource.save(snapshot)) {
            is CommunicationStorageResult.Success -> mutableState.update { current ->
                if (current.revision == revision) {
                    current.copy(
                        persistenceStatus = CommunicationPersistenceStatus.Saved,
                        lastFailure = current.lastFailure?.takeUnless {
                            it.kind == CommunicationFailureKind.Persistence
                        },
                    )
                } else {
                    saveRequests.trySend(Unit)
                    current
                }
            }
            is CommunicationStorageResult.Failure -> markPersistenceFailure()
        }
    }

    private fun markPersistenceFailure() {
        val failure = CommunicationFailure(++failureSequence, CommunicationFailureKind.Persistence)
        mutableState.update { current ->
            current.copy(
                isInitialized = true,
                persistenceStatus = CommunicationPersistenceStatus.Failed,
                lastFailure = failure,
            )
        }
    }

    private fun enqueueSpeech(
        message: Message,
        voice: Voice?,
        cacheAudio: Boolean,
        recordHistory: Boolean,
        rateOverride: Double? = null,
    ) {
        if (message.spokenText.isBlank() && message.parts.none { it.recordingPath != null }) return
        val settings = currentSettings()
        val request = SpeechRequest(
            id = ++speechRequestSequence,
            stopGeneration = stopGeneration.value,
            message = message.immutableSnapshot(),
            voice = voice.withLanguageOverride(settings.primaryLanguage)?.copy(
                supportedLanguages = voice?.supportedLanguages?.toList(),
            ),
            cacheAudio = cacheAudio,
            rateOverride = rateOverride,
            recordHistory = recordHistory,
            visibleInHistory = settings.historyVisible,
            primaryLanguage = settings.primaryLanguage,
        )
        mutableState.update { current ->
            current.copy(queuedSpeechCount = current.queuedSpeechCount + 1)
        }
        if (speechRequests.trySend(request).isFailure) {
            mutableState.update { current ->
                current.copy(queuedSpeechCount = (current.queuedSpeechCount - 1).coerceAtLeast(0))
            }
            markPlaybackFailure()
        }
    }

    private suspend fun play(request: SpeechRequest) {
        val generation = request.stopGeneration
        if (generation != stopGeneration.value) {
            mutableState.update { current ->
                current.copy(queuedSpeechCount = (current.queuedSpeechCount - 1).coerceAtLeast(0))
            }
            return
        }
        mutableState.update { current ->
            current.copy(
                playbackStatus = CommunicationPlaybackStatus.Preparing,
                currentSpeechRequestId = request.id,
                queuedSpeechCount = (current.queuedSpeechCount - 1).coerceAtLeast(0),
            )
        }
        try {
            awaitResume(generation)
            playMessage(request, generation)
            if (generation == stopGeneration.value && request.recordHistory) {
                val now = Clock.System.now().toEpochMilliseconds()
                saidTextRepository.add(
                    SaidText(
                        date = now,
                        saidText = request.message.spokenText,
                        voiceName = request.voice?.name ?: request.voice?.displayName,
                        pitch = request.voice?.pitch,
                        speed = request.voice?.rate,
                        createdAt = now,
                        primaryLanguage = request.voice?.selectedLanguage
                            ?.takeIf(String::isNotBlank)
                            ?: request.voice?.primaryLanguage
                            ?: request.primaryLanguage,
                        visibleInHistory = request.visibleInHistory,
                    )
                )
            }
        } catch (failure: CancellationException) {
            if (!currentCoroutineContext().isActive) throw failure
            if (generation == stopGeneration.value) markPlaybackFailure()
        } catch (_: Throwable) {
            if (generation == stopGeneration.value) markPlaybackFailure()
        } finally {
            mutableState.update { current ->
                if (current.currentSpeechRequestId == request.id) {
                    current.copy(
                        playbackStatus = CommunicationPlaybackStatus.Idle,
                        currentSpeechRequestId = null,
                    )
                } else {
                    current
                }
            }
        }
    }

    private suspend fun playMessage(request: SpeechRequest, generation: Long) {
        val pending = mutableListOf<SpeechChunk>()

        suspend fun flushPending() {
            if (pending.isEmpty()) return
            awaitResume(generation)
            val first = pending.first()
            val voice = request.voice
                .withLanguageOverride(first.languageTag ?: request.primaryLanguage)
                ?.copy(mathMode = request.voice?.mathMode == true || first.mathMode)
            val segments = pending.map { SpeechSegment(it.text, languageTag = it.languageTag) }
            mutableState.update { current ->
                current.copy(playbackStatus = CommunicationPlaybackStatus.Playing)
            }
            if (segments.any { !it.languageTag.isNullOrBlank() }) {
                speechService.speakSegmentsWithoutHistory(
                    segments = segments,
                    voice = voice,
                    pitch = voice?.pitch,
                    rate = request.rateOverride ?: voice?.rate,
                    cacheAudio = request.cacheAudio,
                )
            } else {
                speechService.speakWithoutHistory(
                    text = pending.joinToString("") { it.text },
                    voice = voice,
                    pitch = voice?.pitch,
                    rate = request.rateOverride ?: voice?.rate,
                    cacheAudio = request.cacheAudio,
                )
            }
            pending.clear()
            awaitPlayback()
            ensureNotStopped(generation)
        }

        for (chunk in request.message.speechChunks()) {
            ensureNotStopped(generation)
            if (pending.isNotEmpty() && pending.first().mathMode != chunk.mathMode) flushPending()
            val recordingPath = chunk.recordingPath
            if (recordingPath == null) {
                pending += chunk
                continue
            }
            flushPending()
            awaitResume(generation)
            mutableState.update { current ->
                current.copy(playbackStatus = CommunicationPlaybackStatus.Playing)
            }
            val played = speechService.speakRecordedAudio(
                audioFilePath = recordingPath,
                textForHistory = null,
                voice = request.voice,
            )
            if (!played) {
                pending += chunk.copy(recordingPath = null)
            } else {
                awaitPlayback()
                ensureNotStopped(generation)
            }
        }
        flushPending()
    }

    private suspend fun awaitPlayback() {
        var activePlaybackMillis = 0L
        while (speechService.isPlaying() || speechService.isPaused()) {
            val isPaused = speechService.isPaused()
            mutableState.update { current ->
                current.copy(
                    playbackStatus = if (isPaused) {
                        CommunicationPlaybackStatus.Paused
                    } else {
                        CommunicationPlaybackStatus.Playing
                    }
                )
            }
            delay(20)
            if (!isPaused) activePlaybackMillis += 20
            check(activePlaybackMillis < 120_000) { "Speech playback timed out" }
        }
        check(speechService.playbackState().status != SpeechPlaybackStatus.FAILED) {
            "Speech playback failed"
        }
    }

    private fun pause() {
        if (state.value.currentSpeechRequestId == null && state.value.queuedSpeechCount == 0) return
        pauseRequested.value = true
        mutableState.update { it.copy(playbackStatus = CommunicationPlaybackStatus.Paused) }
        if (state.value.currentSpeechRequestId != null) {
            scope.launch {
                runCatching { speechService.pause() }.onFailure { markPlaybackFailure() }
            }
        }
    }

    private fun resume() {
        if (!pauseRequested.value) return
        pauseRequested.value = false
        mutableState.update { it.copy(playbackStatus = CommunicationPlaybackStatus.Playing) }
        scope.launch {
            runCatching { speechService.resume() }.onFailure { markPlaybackFailure() }
        }
    }

    private fun stop() {
        stopGeneration.update { it + 1 }
        pauseRequested.value = false
        var drained = 0
        while (speechRequests.tryReceive().isSuccess) drained++
        mutableState.update { current ->
            current.copy(
                playbackStatus = CommunicationPlaybackStatus.Idle,
                currentSpeechRequestId = null,
                queuedSpeechCount = (current.queuedSpeechCount - drained).coerceAtLeast(0),
            )
        }
        scope.launch {
            runCatching { speechService.stop() }.onFailure { markPlaybackFailure() }
        }
    }

    private fun markPlaybackFailure() {
        val failure = CommunicationFailure(++failureSequence, CommunicationFailureKind.Playback)
        mutableState.update { current -> current.copy(lastFailure = failure) }
    }

    private suspend fun awaitResume(generation: Long) {
        while (pauseRequested.value) {
            ensureNotStopped(generation)
            mutableState.update { current ->
                current.copy(playbackStatus = CommunicationPlaybackStatus.Paused)
            }
            delay(20)
        }
        ensureNotStopped(generation)
    }

    private fun ensureNotStopped(generation: Long) {
        if (generation != stopGeneration.value) throw CancellationException("Speech request stopped")
    }
}

private data class SpeechRequest(
    val id: Long,
    val stopGeneration: Long,
    val message: Message,
    val voice: Voice?,
    val cacheAudio: Boolean,
    val rateOverride: Double?,
    val recordHistory: Boolean,
    val visibleInHistory: Boolean,
    val primaryLanguage: String,
)

private data class SpeechChunk(
    val text: String,
    val languageTag: String?,
    val recordingPath: String?,
    val mathMode: Boolean,
)

private fun Message.immutableSnapshot(): Message = copy(
    parts = parts.toList(),
    languageSpans = languageSpans.toList(),
    editProvenance = editProvenance.toList(),
)

private fun CommunicationSessionSnapshot.immutableSnapshot(): CommunicationSessionSnapshot = copy(
    activeMessage = activeMessage.immutableSnapshot(),
    heldMessage = heldMessage?.immutableSnapshot(),
)

private fun Message.speechChunks(): List<SpeechChunk> {
    var partOffset = 0
    return buildList {
        parts.forEach { part ->
            val partStart = partOffset
            val partEnd = partStart + part.displayText.length
            partOffset = partEnd
            if (partStart == partEnd && part.recordingPath != null) {
                add(
                    SpeechChunk(
                        text = "",
                        languageTag = part.languageTag,
                        recordingPath = part.recordingPath,
                        mathMode = part.mathMode,
                    )
                )
                return@forEach
            }
            val boundaries = buildSet {
                add(partStart)
                add(partEnd)
                languageSpans.forEach { annotation ->
                    if (annotation.range.start in (partStart + 1)..<partEnd) add(annotation.range.start)
                    if (annotation.range.endExclusive in (partStart + 1)..<partEnd) {
                        add(annotation.range.endExclusive)
                    }
                }
            }.sorted()
            boundaries.zipWithNext().forEach { (start, end) ->
                val wholePart = start == partStart && end == partEnd
                val text = if (wholePart) {
                    part.spokenText
                } else {
                    part.displayText.substring(start - partStart, end - partStart)
                }
                if (text.isEmpty()) return@forEach
                val annotation = languageSpans.firstOrNull {
                    start >= it.range.start && start < it.range.endExclusive
                }
                add(
                    SpeechChunk(
                        text = text,
                        languageTag = annotation?.languageTag ?: part.languageTag,
                        recordingPath = part.recordingPath.takeIf { wholePart },
                        mathMode = part.mathMode,
                    )
                )
            }
        }
    }
}
