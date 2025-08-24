package io.github.jdreioe.wingmate.application.usecase

import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.PhraseRepository

/**
 * Adds a category by storing it as a Phrase with isCategory = true.
 * The UI/store reads categories from PhraseRepository by filtering isCategory.
 */
class AddCategoryUseCase(private val phraseRepository: PhraseRepository) {
    suspend operator fun invoke(name: String): Phrase {
        if (name.isBlank()) error("Category name cannot be blank")
        val categoryAsPhrase = Phrase(
            id = "",               // Repository assigns ID
            text = "",              // Categories don't use text; keep non-null to satisfy model
            name = name,             // Display name of the category
            backgroundColor = null,
            parentId = null,
            isCategory = true,
            createdAt = 0            // Repository sets createdAt
        )
        return phraseRepository.add(categoryAsPhrase)
    }
}
