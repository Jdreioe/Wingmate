package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.*
import io.github.jdreioe.wingmate.infrastructure.AzureVoiceCatalog

/**
 * Thin application-layer use-cases that encapsulate domain repository calls.
 * This implements the Onion principle: Blocs call use-cases, not repositories or infra directly.
 */
class PhraseUseCase(private val repo: PhraseRepository) {
    suspend fun list(): List<Phrase> = repo.getAll()
    suspend fun add(phrase: Phrase): Phrase = repo.add(phrase)
    suspend fun update(phrase: Phrase): Phrase = repo.update(phrase)
    suspend fun delete(id: String) = repo.delete(id)
    suspend fun move(fromIndex: Int, toIndex: Int) = repo.move(fromIndex, toIndex)
}

class CategoryUseCase(private val repo: io.github.jdreioe.wingmate.domain.CategoryRepository) {
    suspend fun list(): List<io.github.jdreioe.wingmate.domain.CategoryItem> = repo.getAll()
    suspend fun add(category: io.github.jdreioe.wingmate.domain.CategoryItem): io.github.jdreioe.wingmate.domain.CategoryItem = repo.add(category)
    suspend fun update(category: io.github.jdreioe.wingmate.domain.CategoryItem): io.github.jdreioe.wingmate.domain.CategoryItem = repo.update(category)
    suspend fun delete(id: String) = repo.delete(id)
}

class SettingsUseCase(private val repo: SettingsRepository) {
    suspend fun get(): Settings = repo.get()
    suspend fun update(settings: Settings): Settings = repo.update(settings)
}

class VoiceUseCase(
    private val repo: VoiceRepository,
    private val azure: AzureVoiceCatalog,
    private val configRepo: ConfigRepository,
) {
    suspend fun list(): List<Voice> = repo.getVoices()
    suspend fun selected(): Voice? = repo.getSelected()
    suspend fun select(voice: Voice) {
        try { println("DEBUG: VoiceUseCase.select() called for '${voice.name}' selectedLang='${voice.selectedLanguage}'") } catch (_: Throwable) {}
        repo.saveSelected(voice)
    }
    suspend fun refreshFromAzure(): List<Voice> {
        val list = azure.list()
        // Cache list for offline/next launch
        repo.saveVoices(list)
        return list
    }
}
