package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.PhraseRepository
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SettingsRepository
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.VoiceRepository
import io.github.jdreioe.wingmate.infrastructure.AzureVoiceCatalog
import io.github.jdreioe.wingmate.application.PhraseUseCase
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.VoiceUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// Simple Bloc-style base
abstract class Bloc<E, S>(initial: S) {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<S> = _state.asStateFlow()

    protected fun setState(reducer: (S) -> S) {
        _state.value = reducer(_state.value)
    }

    fun dispatch(event: E) {
        scope.launch { handle(event) }
    }

    protected abstract suspend fun handle(event: E)

    fun close() {
        scope.cancel()
    }
}

// App-specific blocs
sealed class PhraseEvent {
    data class Add(val phrase: Phrase) : PhraseEvent()
    data class AddCategory(val category: io.github.jdreioe.wingmate.domain.CategoryItem) : PhraseEvent()
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

class PhraseBloc(private val useCase: PhraseUseCase) : Bloc<PhraseEvent, PhraseState>(PhraseState()) {
    // Backward-compatible constructor for existing DI setups that pass a repository
    constructor(repo: PhraseRepository) : this(PhraseUseCase(repo))

    override suspend fun handle(event: PhraseEvent) {
        when (event) {
            is PhraseEvent.Load -> {
                setState { it.copy(loading = true, error = null) }
                try {
                    val list = useCase.list()
                    setState { it.copy(loading = false, items = list) }
                } catch (t: Throwable) {
                    setState { it.copy(loading = false, error = t.message) }
                }
            }
            is PhraseEvent.Add -> {
                setState { it.copy(loading = true, error = null) }
                try {
                    useCase.add(event.phrase)
                    val list = useCase.list()
                    setState { it.copy(loading = false, items = list) }
                } catch (t: Throwable) {
                    setState { it.copy(loading = false, error = t.message) }
                }
            }
            is PhraseEvent.AddCategory -> {
                setState { it.copy(loading = true, error = null) }
                try {
                    // prevent duplicate category names (case-insensitive)
                    val existing = useCase.list().filter { it.isCategory }
                    val name = event.category.name?.trim() ?: ""
                    if (existing.any { (it.name ?: "").trim().equals(name, ignoreCase = true) }) {
                        setState { it.copy(loading = false, error = "Category with name '$name' already exists") }
                    } else {
                        val id = if (event.category.id.isBlank()) java.util.UUID.randomUUID().toString() else event.category.id
                        val p = Phrase(id = id, text = "", name = name, backgroundColor = null, parentId = null, isCategory = true, createdAt = System.currentTimeMillis())
                        useCase.add(p)
                        val list = useCase.list()
                        setState { it.copy(loading = false, items = list) }
                    }
                } catch (t: Throwable) {
                    setState { it.copy(loading = false, error = t.message) }
                }
            }
            is PhraseEvent.Edit -> {
                setState { it.copy(loading = true, error = null) }
                try {
                    useCase.update(event.phrase)
                    val list = useCase.list()
                    setState { it.copy(loading = false, items = list) }
                } catch (t: Throwable) {
                    setState { it.copy(loading = false, error = t.message) }
                }
            }
            is PhraseEvent.Delete -> {
                setState { it.copy(loading = true, error = null) }
                try {
                    useCase.delete(event.id)
                    val list = useCase.list()
                    setState { it.copy(loading = false, items = list) }
                } catch (t: Throwable) {
                    setState { it.copy(loading = false, error = t.message) }
                }
            }
            is PhraseEvent.Move -> {
                setState { it.copy(loading = true, error = null) }
                try {
                    useCase.move(event.fromIndex, event.toIndex)
                    val list = useCase.list()
                    setState { it.copy(loading = false, items = list) }
                } catch (t: Throwable) {
                    setState { it.copy(loading = false, error = t.message) }
                }
            }
        }
    }
}

sealed class SettingsEvent {
    data class Update(val settings: Settings) : SettingsEvent()
    data object Load : SettingsEvent()
}

data class SettingsState(
    val loading: Boolean = false,
    val value: Settings? = null,
    val error: String? = null
)

class SettingsBloc(private val useCase: SettingsUseCase) : Bloc<SettingsEvent, SettingsState>(SettingsState()) {
    constructor(repo: SettingsRepository) : this(SettingsUseCase(repo))

    override suspend fun handle(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.Load -> {
                setState { it.copy(loading = true, error = null) }
                try {
                    val s = useCase.get()
                    setState { it.copy(loading = false, value = s) }
                } catch (t: Throwable) {
                    setState { it.copy(loading = false, error = t.message) }
                }
            }
            is SettingsEvent.Update -> {
                setState { it.copy(loading = true, error = null) }
                try {
                    val s = useCase.update(event.settings)
                    setState { it.copy(loading = false, value = s) }
                } catch (t: Throwable) {
                    setState { it.copy(loading = false, error = t.message) }
                }
            }
        }
    }
}

// Voice Bloc
sealed class VoiceEvent {
    data object Load : VoiceEvent()
    data class Select(val voice: Voice) : VoiceEvent()
    data object RefreshFromAzure : VoiceEvent()
}

data class VoiceState(
    val loading: Boolean = false,
    val items: List<Voice> = emptyList(),
    val selected: Voice? = null,
    val error: String? = null,
)

class VoiceBloc(
    private val useCase: VoiceUseCase
) : Bloc<VoiceEvent, VoiceState>(VoiceState()) {
    constructor(repo: VoiceRepository, azure: AzureVoiceCatalog, configRepo: io.github.jdreioe.wingmate.domain.ConfigRepository) : this(
        VoiceUseCase(repo, azure, configRepo)
    )

    override suspend fun handle(event: VoiceEvent) {
        when (event) {
            is VoiceEvent.Load -> {
                setState { it.copy(loading = true, error = null) }
                try {
                    val list = useCase.list()
                    val sel = useCase.selected()
                    setState { it.copy(loading = false, items = list, selected = sel) }
                } catch (t: Throwable) {
                    setState { it.copy(loading = false, error = t.message) }
                }
            }
            is VoiceEvent.Select -> {
                try {
                    useCase.select(event.voice)
                    setState { it.copy(selected = event.voice) }
                } catch (t: Throwable) {
                    setState { it.copy(error = t.message) }
                }
            }
            is VoiceEvent.RefreshFromAzure -> {
                setState { it.copy(loading = true, error = null) }
                try {
                    val fromCloud = useCase.refreshFromAzure()
                    setState { it.copy(loading = false, items = fromCloud) }
                } catch (t: Throwable) {
                    setState { it.copy(loading = false, error = t.message) }
                }
            }
        }
    }
}
