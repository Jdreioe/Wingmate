package io.github.jdreioe.wingmate.kde

import io.github.jdreioe.wingmate.application.bloc.PhraseListStore
import io.github.jdreioe.wingmate.application.usecase.GetPhrasesAndCategoriesUseCase
import io.github.jdreioe.wingmate.domain.CategoryItem
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.PhraseRepository
import io.github.jdreioe.wingmate.domain.toCategoryItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.core.context.GlobalContext

/**
 * Thin read-model over the shared [PhraseListStore] (single source of truth,
 * same as iOS/Android). Reads come from the shared use case; every write is
 * routed through the store so category/folder semantics stay canonical.
 */
class PhraseViewModel {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val phrasesUseCase: GetPhrasesAndCategoriesUseCase by lazy {
        GlobalContext.get().get()
    }

    private val phraseListStore: PhraseListStore by lazy {
        GlobalContext.get().get()
    }

    private val phraseRepository: PhraseRepository by lazy {
        GlobalContext.get().get()
    }

    private val _phrases = MutableStateFlow<List<Phrase>>(emptyList())
    val phrases: StateFlow<List<Phrase>> = _phrases.asStateFlow()

    private val _categories = MutableStateFlow<List<CategoryItem>>(emptyList())
    val categories: StateFlow<List<CategoryItem>> = _categories.asStateFlow()

    private val _currentCategory = MutableStateFlow<String?>(null)
    val currentCategory: StateFlow<String?> = _currentCategory.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        scope.launch { reloadData() }
    }

    fun selectCategory(categoryId: String?) {
        _currentCategory.value = categoryId
        phraseListStore.accept(PhraseListStore.Intent.SelectCategory(categoryId))
        scope.launch {
            val (phrases, _) = phrasesUseCase.invoke()
            _phrases.value = phrases.filter { it.parentId == categoryId }
        }
    }

    fun addPhrase(text: String, imageUrl: String? = null) {
        phraseListStore.accept(
            PhraseListStore.Intent.AddPhrase(text = text, imageUrl = imageUrl)
        )
        loadData()
    }

    fun updatePhrase(id: String, text: String?, name: String? = null, recordingPath: String? = null) {
        if (recordingPath != null) {
            phraseListStore.accept(PhraseListStore.Intent.UpdatePhraseRecording(id = id, recordingPath = recordingPath))
        } else {
            phraseListStore.accept(PhraseListStore.Intent.UpdatePhrase(id = id, text = text, name = name))
        }
        loadData()
    }

    fun deletePhrase(phraseId: String) {
        phraseListStore.accept(PhraseListStore.Intent.DeletePhrase(phraseId = phraseId))
        loadData()
    }

    fun addCategory(name: String) {
        phraseListStore.accept(PhraseListStore.Intent.AddCategory(name = name))
        loadData()
    }

    fun deleteCategory(categoryId: String) {
        phraseListStore.accept(PhraseListStore.Intent.DeleteCategory(categoryId = categoryId))
        loadData()
    }

    /**
     * Full phrase edit routed through the shared store. Field values follow
     * the store's update contract: null keeps the existing value, "" removes
     * it. [KotlinBridge] translates its JSON body into that convention.
     */
    fun updateDetails(
        id: String,
        text: String?,
        name: String?,
        imageUrl: String?,
        parentId: String?,
        linkedBoardId: String?,
        recordingPath: String?,
        isHidden: Boolean?,
    ) {
        phraseListStore.accept(
            PhraseListStore.Intent.UpdatePhraseDetails(
                id = id,
                text = text,
                name = name,
                imageUrl = imageUrl,
                recordingPath = recordingPath,
                parentId = parentId,
                linkedBoardId = linkedBoardId,
                isHidden = isHidden,
            )
        )
        loadData()
    }

    /** Returns false when the name is blank, true when the rename was dispatched. */
    fun renameCategory(id: String, name: String): Boolean {
        val normalized = name.trim().takeIf { it.isNotEmpty() } ?: return false
        phraseListStore.accept(
            PhraseListStore.Intent.UpdatePhrase(id = id, text = normalized, name = null, imageUrl = null)
        )
        loadData()
        return true
    }

    suspend fun moveItem(id: String, delta: Int): Boolean {
        val all = phraseRepository.getAll()
        val from = all.indexOfFirst { it.id == id }
        if (from < 0) return false
        val to = (from + delta).coerceIn(0, all.lastIndex)
        if (from != to) {
            phraseListStore.accept(PhraseListStore.Intent.MovePhrase(fromIndex = from, toIndex = to))
        }
        loadData()
        return true
    }

    private suspend fun reloadData() {
        val (phrases, folders) = phrasesUseCase.invoke()
        _phrases.value = phrases.filter { it.parentId == _currentCategory.value }
        _categories.value = folders.map { it.toCategoryItem() }
    }

    fun cleanup() {
        scope.cancel()
    }
}
