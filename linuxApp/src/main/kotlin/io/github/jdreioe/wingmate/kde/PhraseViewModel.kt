package io.github.jdreioe.wingmate.kde

import io.github.jdreioe.wingmate.application.usecase.*
import io.github.jdreioe.wingmate.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.core.context.GlobalContext

/**
 * Bridge between QML and Kotlin business logic.
 * Exposes phrase and category management to the UI.
 */
class PhraseViewModel {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private val phrasesUseCase: GetPhrasesAndCategoriesUseCase by lazy {
        GlobalContext.get().get()
    }
    
    private val addPhraseUseCase: AddPhraseUseCase by lazy {
        GlobalContext.get().get()
    }
    
    private val updatePhraseUseCase: UpdatePhraseUseCase by lazy {
        GlobalContext.get().get()
    }
    
    private val deletePhraseUseCase: DeletePhraseUseCase by lazy {
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
        scope.launch {
            val (phrases, folders) = phrasesUseCase.invoke()
            _phrases.value = phrases.filter { it.parentId == _currentCategory.value }
            // folders are Phrase objects with linkedBoardId
            _categories.value = folders.map { phrase ->
                CategoryItem(
                    id = phrase.id,
                    name = phrase.text,
                    isFolder = phrase.linkedBoardId != null
                )
            }
        }
    }
    
    fun selectCategory(categoryId: String?) {
        _currentCategory.value = categoryId
        scope.launch {
            val (phrases, _) = phrasesUseCase.invoke()
            _phrases.value = phrases.filter { it.parentId == categoryId }
        }
    }
    
    fun addPhrase(text: String, imageUrl: String? = null) {
        scope.launch {
            // AddPhraseUseCase takes (text: String, categoryId: String?)
            addPhraseUseCase.invoke(text, _currentCategory.value)
            loadData()
        }
    }
    
    fun updatePhrase(id: String, text: String?, name: String? = null, recordingPath: String? = null) {
        scope.launch {
            // UpdatePhraseUseCase takes (id, text, name, recordingPath)
            updatePhraseUseCase.invoke(id, text, name, recordingPath)
            loadData()
        }
    }
    
    fun deletePhrase(phraseId: String) {
        scope.launch {
            deletePhraseUseCase.invoke(phraseId)
            loadData()
        }
    }
    
    // Categories are now represented as Phrases with linkedBoardId
    fun addCategory(name: String) {
        scope.launch {
            // Create a folder (Phrase with linkedBoardId pointing to a new board)
            val folderId = java.util.UUID.randomUUID().toString()
            val folder = Phrase(
                id = folderId,
                text = name,
                linkedBoardId = folderId, // Self-referential for now
                parentId = _currentCategory.value,
                createdAt = System.currentTimeMillis(),
                isGridItem = false // Folders appear in category bar, not grid
            )
            phraseRepository.add(folder)
            loadData()
        }
    }
    
    fun deleteCategory(categoryId: String) {
        scope.launch {
            // Delete the folder phrase
            phraseRepository.delete(categoryId)
            loadData()
        }
    }
    
    fun cleanup() {
        scope.cancel()
    }
}
