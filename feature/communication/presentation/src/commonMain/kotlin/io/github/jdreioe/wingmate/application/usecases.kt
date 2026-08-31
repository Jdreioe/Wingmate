package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.*
import io.github.jdreioe.wingmate.infrastructure.AzureVoiceCatalog
import io.github.jdreioe.wingmate.infrastructure.GoogleVoiceCatalog

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
    private val phraseRepository: PhraseRepository,
    private val featureUsageReporter: FeatureUsageReporter
) {
    suspend fun list(): List<CategoryItem> {
        val all = phraseRepository.getAll()
        return all.mapNotNull { it.toFolderPhrase() }.map { it.toCategoryItem() }
    }

    suspend fun add(category: CategoryItem): CategoryItem {
        val existingNames = phraseRepository.getAll()
            .mapNotNull { it.toFolderPhrase()?.phrase?.text }
            .toSet()
        val baseName = category.name?.trim().orEmpty()
        val uniqueName = generateUniqueName(baseName, existingNames)
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val folderId = category.id.ifBlank { "category-${now}-${kotlin.random.Random.nextInt(1000, 9999)}" }
        val phrase = Phrase(
            id = folderId,
            text = uniqueName,
            linkedBoardId = folderId,
            isGridItem = false,
            createdAt = now,
        )
        val stored = phraseRepository.add(phrase)
        val result = stored.toFolderPhrase()?.toCategoryItem() ?: CategoryItem(id = stored.id, name = stored.text)
        featureUsageReporter.reportEvent(
            FeatureUsageEvents.CATEGORY_ADDED,
            "has_name" to (!result.name.isNullOrBlank()).toString()
        )
        return result
    }

    suspend fun update(category: CategoryItem): CategoryItem {
        val all = phraseRepository.getAll()
        val existing = all.firstOrNull { it.id == category.id } ?: return category
        val updated = existing.copy(text = category.name?.trim().orEmpty())
        phraseRepository.update(updated)
        return category
    }

    suspend fun delete(id: String) {
        phraseRepository.delete(id)
        featureUsageReporter.reportEvent(
            FeatureUsageEvents.CATEGORY_DELETED,
            "source" to "category_use_case"
        )
    }

    suspend fun move(fromIndex: Int, toIndex: Int) {
        val all = phraseRepository.getAll()
        val folderPhrases = all.mapNotNull { it.toFolderPhrase() }
        if (fromIndex !in folderPhrases.indices || toIndex !in folderPhrases.indices) return
        val fromId = folderPhrases[fromIndex].id
        val toId = folderPhrases[toIndex].id
        val fromAbsolute = all.indexOfFirst { it.id == fromId }
        val toAbsolute = all.indexOfFirst { it.id == toId }
        if (fromAbsolute >= 0 && toAbsolute >= 0) {
            phraseRepository.move(fromAbsolute, toAbsolute)
        }
        featureUsageReporter.reportEvent(
            FeatureUsageEvents.CATEGORY_MOVED,
            "from_index" to fromIndex.toString(),
            "to_index" to toIndex.toString()
        )
    }

    private fun generateUniqueName(base: String, existing: Set<String>): String {
        if (base.isEmpty() || base !in existing) return base
        var suffix = 2
        while (true) {
            val candidate = "${base}_$suffix"
            if (candidate !in existing) return candidate
            suffix++
        }
    }
}

class SettingsUseCase(
    private val repo: SettingsRepository,
    private val stateManager: SettingsStateManager? = null,
) {
    suspend fun get(): Settings = repo.get()
    suspend fun update(settings: Settings): Settings =
        stateManager?.updateSettings(settings) ?: repo.update(settings)
}

class VoiceUseCase(
    private val repo: VoiceRepository,
    private val azure: AzureVoiceCatalog,
    private val google: GoogleVoiceCatalog,
    private val configRepo: ConfigRepository,
    private val featureUsageReporter: FeatureUsageReporter,
    private val boardSetSpeechCache: BoardSpeechCache? = null,
) {
    suspend fun list(): List<Voice> = repo.getVoices()
    suspend fun listForEngine(engine: TtsEngine): List<Voice> = repo.getVoices().forTtsEngine(engine)
    suspend fun selected(): Voice? {
        val persisted = repo.getSelected() ?: return null
        val enriched = persisted.withCatalogMetadata(repo.getVoices())
        if (enriched != persisted) repo.saveSelected(enriched)
        return enriched
    }
    suspend fun select(voice: Voice) {
        repo.saveSelected(voice)
        val provider = voice.provider
        featureUsageReporter.reportEvent(
            FeatureUsageEvents.VOICE_SELECTED,
            "provider" to when {
                provider != null -> provider.name.lowercase()
                voice.name?.contains("Neural2", ignoreCase = true) == true ||
                    voice.name?.contains("Wavenet", ignoreCase = true) == true ||
                    voice.name?.contains("Chirp", ignoreCase = true) == true ||
                    voice.name?.contains("Journey", ignoreCase = true) == true ||
                    voice.name?.startsWith("google|") == true -> "google"
                voice.name?.contains("Neural", ignoreCase = true) == true -> "azure"
                else -> "system"
            },
            "primary_language" to voice.primaryLanguage,
            "selected_language" to voice.selectedLanguage
        )
        boardSetSpeechCache?.cacheAll()
    }
    suspend fun refreshFromAzure(): List<Voice> {
        val list = azure.list()
        // Keep the last working catalog if the refresh comes back empty, and backfill
        // the saved selection with catalog-only metadata (secondary locales, provider).
        if (list.isNotEmpty()) {
            repo.saveVoices(list)
            persistCatalogMetadataForSelected(list)
        }
        featureUsageReporter.reportEvent(
            FeatureUsageEvents.VOICE_REFRESHED,
            "count" to list.size.toString()
        )
        // A transient failure yields an empty catalog; keep serving the persisted one
        // so the voice picker doesn't blank out.
        return cachedVoicesWhenEmpty(list)
    }

    suspend fun refreshFromGoogle(): List<Voice> {
        val list = google.list()
        // Keep the last working catalog if validation or refresh fails.
        if (list.isNotEmpty()) {
            repo.saveVoices(list)
            persistCatalogMetadataForSelected(list)
        }
        featureUsageReporter.reportEvent(
            FeatureUsageEvents.VOICE_REFRESHED,
            "provider" to "google",
            "count" to list.size.toString(),
        )
        return cachedVoicesWhenEmpty(list)
    }

    private suspend fun cachedVoicesWhenEmpty(voices: List<Voice>): List<Voice> =
        if (voices.isEmpty()) repo.getVoices() else voices

    private suspend fun persistCatalogMetadataForSelected(catalog: List<Voice>) {
        val persisted = repo.getSelected() ?: return
        val enriched = persisted.withCatalogMetadata(catalog)
        if (enriched != persisted) repo.saveSelected(enriched)
    }
}
