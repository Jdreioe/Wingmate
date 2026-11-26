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
import io.github.jdreioe.wingmate.domain.TextPredictionService
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.withLanguageOverride
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlin.time.Clock

/**
 * Owns the current communication and serializes persistence and playback behind
 * one state/action interface. Callers never wait for either side effect.
 *
 * Speech uses the engine selected when the request was accepted. If a cloud engine
 * fails, the request continues with the device voice and a non-blocking
 * [CommunicationFailureKind.SpeechFallback] explains why the voice changed.
 */
class QueuedCommunicationSession(
    private val dataSource: CommunicationSessionDataSource,
    private val speechService: SpeechService,
    private val saidTextRepository: SaidTextRepository,
    private val currentSettings: () -> Settings,
    private val scope: CoroutineScope,
    private val predictionService: TextPredictionService? = null,
) : CommunicationSession {
    private val mutableState = MutableStateFlow(CommunicationSessionState())
    override val state: StateFlow<CommunicationSessionState> = mutableState.asStateFlow()

    private val initialization = CompletableDeferred<Unit>()
    private val saveRequests = Channel<Unit>(Channel.CONFLATED)
    private val speechRequests = Channel<SpeechRequest>(Channel.UNLIMITED)
    private val stopGeneration = MutableStateFlow(0L)
    private val previewGeneration = MutableStateFlow(0L)
    private val pauseRequested = MutableStateFlow(false)
    private val pendingStop = MutableStateFlow<Job?>(null)
    private val activePlayback = MutableStateFlow<Job?>(null)
    private val activeRequest = MutableStateFlow<SpeechRequest?>(null)
    private var initialLoadFailed = false
    private var speechRequestSequence = 0L
    private var failureSequence = 0L

    init {
        scope.launch {
            loadInitialSnapshot()
            initialization.complete(Unit)
        }
        scope.launch {
            initialization.await()
            for (ignored in saveRequests) {
                if (initialLoadFailed && state.value.revision == 0L) loadInitialSnapshot()
                else persistLatestSnapshot()
            }
        }
        scope.launch {
            for (request in speechRequests) {
                pendingStop.value?.join()
                if (isStale(request)) {
                    mutableState.update { current ->
                        current.copy(queuedSpeechCount = (current.queuedSpeechCount - 1).coerceAtLeast(0))
                    }
                    continue
                }
                // Native Stop may cancel its caller. Give each request its own job so
                // cancellation cannot terminate the consumer of future speech requests.
                supervisorScope {
                    activeRequest.value = request
                    val playback = async(start = CoroutineStart.LAZY) { play(request) }
                    activePlayback.value = playback
                    try {
                        playback.await()
                    } catch (cancelled: CancellationException) {
                        if (!currentCoroutineContext().isActive) throw cancelled
                    } finally {
                        activePlayback.compareAndSet(playback, null)
                        activeRequest.compareAndSet(request, null)
                    }
                }
            }
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
                cancelPreviews()
                val message = state.value.activeMessage
                enqueueSpeech(message, action.voice, action.cacheAudio, recordHistory = true)
            }
            is CommunicationAction.SpeakPart -> {
                cancelPreviews()
                enqueueSpeech(
                    message = Message(parts = listOf(action.part)),
                    voice = action.voice,
                    cacheAudio = action.cacheAudio,
                    recordHistory = false,
                    rateOverride = action.rateOverride,
                    isPreview = action.isPreview,
                )
            }
            CommunicationAction.CancelPreview -> cancelPreviews()
            CommunicationAction.Pause -> pause()
            CommunicationAction.Resume -> resume()
            CommunicationAction.Stop -> stop()
            CommunicationAction.RetryPersistence -> saveRequests.trySend(Unit)
            CommunicationAction.DismissFailure -> mutableState.update { current ->
                if (current.lastFailure?.kind != CommunicationFailureKind.Persistence) {
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
            is CommunicationStorageResult.Success -> {
                initialLoadFailed = false
                mutableState.update { current ->
                    if (current.revision == 0L) {
                        current.copy(
                            snapshot = loaded.value.immutableSnapshot(),
                            isInitialized = true,
                            persistenceStatus = CommunicationPersistenceStatus.Saved,
                            lastFailure = current.lastFailure?.takeUnless {
                                it.kind == CommunicationFailureKind.Persistence
                            },
                        )
                    } else {
                        current.copy(isInitialized = true)
                    }
                }
            }
            is CommunicationStorageResult.Failure -> {
                initialLoadFailed = true
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
        isPreview: Boolean = false,
    ) {
        if (message.spokenText.isBlank() && message.parts.none { it.recordingPath != null }) return
        val settings = currentSettings()
        val request = SpeechRequest(
            id = ++speechRequestSequence,
            stopGeneration = stopGeneration.value,
            previewGeneration = previewGeneration.value,
            isPreview = isPreview,
            message = message.immutableSnapshot(),
            voice = voice.withLanguageOverride(settings.primaryLanguage)?.copy(
                supportedLanguages = voice?.supportedLanguages?.toList(),
            ),
            cacheAudio = cacheAudio,
            rateOverride = rateOverride,
            recordHistory = recordHistory,
            visibleInHistory = settings.historyVisible,
            primaryLanguage = settings.primaryLanguage,
            engine = settings.ttsEngine,
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
        if (isStale(request)) {
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
            awaitResume(request)
            playMessage(request)
            if (!isStale(request) && request.recordHistory) {
                val now = Clock.System.now().toEpochMilliseconds()
                val saidText = SaidText(
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
                saidTextRepository.add(saidText)
                // Rebuild from persisted history without delaying speech or discarding vocabulary.
                if (saidText.visibleInHistory) {
                    predictionService?.refresh()
                }
            }
        } catch (failure: CancellationException) {
            if (!currentCoroutineContext().isActive) throw failure
            if (!isStale(request)) markPlaybackFailure()
        } catch (_: Throwable) {
            if (!isStale(request)) markPlaybackFailure()
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

    private suspend fun playMessage(request: SpeechRequest) {
        val pending = mutableListOf<SpeechChunk>()
        // Once the cloud fails, finish the rest of this request on the device voice.
        var engine = request.engine

        suspend fun speakPending(segments: List<SpeechSegment>, voice: Voice?) {
            if (segments.any { !it.languageTag.isNullOrBlank() }) {
                speechService.speakSegmentsWithoutHistory(
                    segments = segments,
                    voice = voice,
                    pitch = voice?.pitch,
                    rate = request.rateOverride ?: voice?.rate,
                    cacheAudio = request.cacheAudio,
                    engine = engine,
                )
            } else {
                speechService.speakWithoutHistory(
                    text = segments.joinToString("") { it.text },
                    voice = voice,
                    pitch = voice?.pitch,
                    rate = request.rateOverride ?: voice?.rate,
                    cacheAudio = request.cacheAudio,
                    engine = engine,
                )
            }
            awaitPlayback()
        }

        suspend fun flushPending() {
            if (pending.isEmpty()) return
            awaitResume(request)
            val first = pending.first()
            val voice = request.voice
                .withLanguageOverride(first.languageTag ?: request.primaryLanguage)
                ?.copy(mathMode = request.voice?.mathMode == true || first.mathMode)
            val segments = pending.map { SpeechSegment(it.text, languageTag = it.languageTag) }
            mutableState.update { current ->
                current.copy(playbackStatus = CommunicationPlaybackStatus.Playing)
            }
            try {
                speakPending(segments, voice)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cloudFailure: Throwable) {
                if (engine == TtsEngine.SYSTEM) throw cloudFailure
                ensureNotStopped(request)
                engine = TtsEngine.SYSTEM
                markFailure(CommunicationFailureKind.SpeechFallback)
                speakPending(segments, voice)
            }
            pending.clear()
            ensureNotStopped(request)
        }

        for (chunk in request.message.speechChunks()) {
            ensureNotStopped(request)
            if (pending.isNotEmpty() && pending.first().mathMode != chunk.mathMode) flushPending()
            val recordingPath = chunk.recordingPath
            if (recordingPath == null) {
                pending += chunk
                continue
            }
            flushPending()
            awaitResume(request)
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
                ensureNotStopped(request)
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
        val playback = activePlayback.value
        playback?.cancel()
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
        val previousStop = pendingStop.value
        val stopping = scope.launch(start = CoroutineStart.LAZY) {
            previousStop?.join()
            playback?.join()
            runCatching { speechService.stop() }.onFailure { markPlaybackFailure() }
        }
        pendingStop.value = stopping
        stopping.start()
    }

    private fun cancelPreviews() {
        previewGeneration.update { it + 1 }
        val current = activeRequest.value ?: return
        if (!current.isPreview) return
        val playback = activePlayback.value
        playback?.cancel()
        mutableState.update { state ->
            if (state.currentSpeechRequestId == current.id) {
                state.copy(
                    playbackStatus = CommunicationPlaybackStatus.Idle,
                    currentSpeechRequestId = null,
                )
            } else {
                state
            }
        }
        val previousStop = pendingStop.value
        val stopping = scope.launch(start = CoroutineStart.LAZY) {
            previousStop?.join()
            playback?.join()
            runCatching { speechService.stop() }
        }
        pendingStop.value = stopping
        stopping.start()
    }

    private fun markPlaybackFailure() = markFailure(CommunicationFailureKind.Playback)

    private fun markFailure(kind: CommunicationFailureKind) {
        val failure = CommunicationFailure(++failureSequence, kind)
        mutableState.update { current -> current.copy(lastFailure = failure) }
    }

    private suspend fun awaitResume(request: SpeechRequest) {
        while (pauseRequested.value) {
            ensureNotStopped(request)
            mutableState.update { current ->
                current.copy(playbackStatus = CommunicationPlaybackStatus.Paused)
            }
            delay(20)
        }
        ensureNotStopped(request)
    }

    private fun isStale(request: SpeechRequest): Boolean =
        request.stopGeneration != stopGeneration.value ||
            (request.isPreview && request.previewGeneration != previewGeneration.value)

    private fun ensureNotStopped(request: SpeechRequest) {
        if (isStale(request)) throw CancellationException("Speech request stopped")
    }
}

private data class SpeechRequest(
    val id: Long,
    val stopGeneration: Long,
    val previewGeneration: Long,
    val isPreview: Boolean,
    val message: Message,
    val voice: Voice?,
    val cacheAudio: Boolean,
    val rateOverride: Double?,
    val recordHistory: Boolean,
    val visibleInHistory: Boolean,
    val primaryLanguage: String,
    val engine: TtsEngine,
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
