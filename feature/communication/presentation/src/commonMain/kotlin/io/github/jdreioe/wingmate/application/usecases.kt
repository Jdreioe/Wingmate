package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.*
import io.github.jdreioe.wingmate.infrastructure.AzureVoiceCatalog

/**
 * Thin application-layer use-cases that encapsulate domain repository calls.
 * This implements the Onion principle: Blocs call use-cases, not repositories or infra directly.
 */
class PhraseUseCase(private val repo: PhraseRepository) {
    suspend fun list(): List<Phrase> = repo.getAll()
    suspend fun add(phrase: Phrase): Phrase = repo.add(normalizePhrase(phrase))
    suspend fun update(phrase: Phrase): Phrase = repo.update(normalizePhrase(phrase))
    suspend fun delete(id: String) = repo.delete(id)
    suspend fun move(fromIndex: Int, toIndex: Int) = repo.move(fromIndex, toIndex)

    private fun normalizePhrase(phrase: Phrase): Phrase {
        return phrase.copy(
            text = SpeechTextProcessor.normalizeShorthandSsml(phrase.text),
            name = phrase.name?.let { SpeechTextProcessor.normalizeShorthandSsml(it) }
        )
    }
}

class CategoryUseCase(
    private val repo: io.github.jdreioe.wingmate.domain.CategoryRepository,
    private val featureUsageReporter: FeatureUsageReporter
) {
    suspend fun list(): List<io.github.jdreioe.wingmate.domain.CategoryItem> = repo.getAll()
    suspend fun add(category: io.github.jdreioe.wingmate.domain.CategoryItem): io.github.jdreioe.wingmate.domain.CategoryItem {
        val added = repo.add(category)
        featureUsageReporter.reportEvent(
            FeatureUsageEvents.CATEGORY_ADDED,
            "has_name" to (!added.name.isNullOrBlank()).toString()
        )
        return added
    }
    suspend fun update(category: io.github.jdreioe.wingmate.domain.CategoryItem): io.github.jdreioe.wingmate.domain.CategoryItem = repo.update(category)
    suspend fun delete(id: String) {
        repo.delete(id)
        featureUsageReporter.reportEvent(
            FeatureUsageEvents.CATEGORY_DELETED,
            "source" to "category_use_case"
        )
    }
    suspend fun move(fromIndex: Int, toIndex: Int) {
        repo.move(fromIndex, toIndex)
        featureUsageReporter.reportEvent(
            FeatureUsageEvents.CATEGORY_MOVED,
            "from_index" to fromIndex.toString(),
            "to_index" to toIndex.toString()
        )
    }
}

class SettingsUseCase(private val repo: SettingsRepository) {
    suspend fun get(): Settings = repo.get()
    suspend fun update(settings: Settings): Settings = repo.update(settings)
    
    // Get the state manager from DI to trigger reactive updates
    suspend fun updateWithNotification(settings: Settings): Settings {
        return repo.update(settings)
    }
}

class VoiceUseCase(
    private val repo: VoiceRepository,
    private val azure: AzureVoiceCatalog,
    private val configRepo: ConfigRepository,
    private val featureUsageReporter: FeatureUsageReporter
) {
    suspend fun list(): List<Voice> = repo.getVoices()
    suspend fun selected(): Voice? = repo.getSelected()
    suspend fun select(voice: Voice) {
        repo.saveSelected(voice)
        featureUsageReporter.reportEvent(
            FeatureUsageEvents.VOICE_SELECTED,
            "provider" to if (voice.name?.contains("Neural", ignoreCase = true) == true) "azure" else "system",
            "primary_language" to voice.primaryLanguage,
            "selected_language" to voice.selectedLanguage
        )
    }
    suspend fun refreshFromAzure(): List<Voice> {
        val list = azure.list()
        // Cache list for offline/next launch
        repo.saveVoices(list)
        featureUsageReporter.reportEvent(
            FeatureUsageEvents.VOICE_REFRESHED,
            "count" to list.size.toString()
        )
        return list
    }
}
