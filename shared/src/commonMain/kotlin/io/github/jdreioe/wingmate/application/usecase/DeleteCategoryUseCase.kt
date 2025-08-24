package io.github.jdreioe.wingmate.application.usecase

import io.github.jdreioe.wingmate.domain.PhraseRepository

class DeleteCategoryUseCase(private val phraseRepository: PhraseRepository) {
    suspend operator fun invoke(categoryId: String) {
        // Categories are stored as phrases with isCategory=true
        phraseRepository.delete(categoryId)
        // Optionally, delete child phrases if desired; current behavior keeps them orphaned unless moved
    }
}
