package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.Phrase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Simple Bloc-style base
abstract class Bloc<E, S>(initial: S, dispatcher: CoroutineDispatcher = Dispatchers.Default) {
    private val scope = CoroutineScope(dispatcher + Job())
    private val events = Channel<E>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<S> = _state.asStateFlow()

    init {
        // Repository reads and writes must finish in dispatch order, including across suspension.
        scope.launch {
            for (event in events) handle(event)
        }
    }

    protected fun setState(reducer: (S) -> S) {
        _state.update(reducer)
    }

    fun dispatch(event: E) {
        events.trySend(event)
    }

    protected abstract suspend fun handle(event: E)

    fun close() {
        events.cancel()
        scope.cancel()
    }
}

// App-specific blocs
sealed class PhraseEvent {
    data class Add(val phrase: Phrase) : PhraseEvent()
    data class Edit(val phrase: Phrase) : PhraseEvent()
    data class Delete(val id: String) : PhraseEvent()
    data class Move(val fromIndex: Int, val toIndex: Int) : PhraseEvent()
    data object Load : PhraseEvent()
}

data class PhraseState(
    val loading: Boolean = false,
    val items: List<Phrase> = emptyList(),
    val error: String? = null
)

class PhraseBloc(
    private val useCase: PhraseUseCase,
    private val featureUsageReporter: FeatureUsageReporter,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Bloc<PhraseEvent, PhraseState>(PhraseState(), dispatcher) {
    override suspend fun handle(event: PhraseEvent) {
        when (event) {
            is PhraseEvent.Load -> {
                setState { it.copy(loading = true, error = null) }
                try {
                    val list = useCase.list()
                    setState { it.copy(loading = false, items = list) }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    setState { it.copy(loading = false, error = t.message) }
                }
            }
            is PhraseEvent.Add -> {
                setState { it.copy(loading = true, error = null) }
                try {
                    useCase.add(event.phrase)
                    featureUsageReporter.reportEvent(
                        FeatureUsageEvents.PHRASE_ADDED,
                        "has_category" to (!event.phrase.parentId.isNullOrBlank()).toString(),
                        "has_recording" to (!event.phrase.recordingPath.isNullOrBlank()).toString()
                    )
                    val list = useCase.list()
                    setState { it.copy(loading = false, items = list) }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    setState { it.copy(loading = false, error = t.message) }
                }
            }
            is PhraseEvent.Edit -> {
                setState { it.copy(loading = true, error = null) }
                try {
                    useCase.update(event.phrase)
                    featureUsageReporter.reportEvent(
                        FeatureUsageEvents.PHRASE_EDITED,
                        "has_category" to (!event.phrase.parentId.isNullOrBlank()).toString(),
                        "has_recording" to (!event.phrase.recordingPath.isNullOrBlank()).toString()
                    )
                    val list = useCase.list()
                    setState { it.copy(loading = false, items = list) }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    setState { it.copy(loading = false, error = t.message) }
                }
            }
            is PhraseEvent.Delete -> {
                setState { it.copy(loading = true, error = null) }
                try {
                    useCase.delete(event.id)
                    featureUsageReporter.reportEvent(
                        FeatureUsageEvents.PHRASE_DELETED,
                        "source" to "phrase_bloc"
                    )
                    val list = useCase.list()
                    setState { it.copy(loading = false, items = list) }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    setState { it.copy(loading = false, error = t.message) }
                }
            }
            is PhraseEvent.Move -> {
                setState { it.copy(loading = true, error = null) }
                try {
                    useCase.move(event.fromIndex, event.toIndex)
                    featureUsageReporter.reportEvent(
                        FeatureUsageEvents.PHRASE_MOVED,
                        "from_index" to event.fromIndex.toString(),
                        "to_index" to event.toIndex.toString()
                    )
                    val list = useCase.list()
                    setState { it.copy(loading = false, items = list) }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    setState { it.copy(loading = false, error = t.message) }
                }
            }
        }
    }
}
