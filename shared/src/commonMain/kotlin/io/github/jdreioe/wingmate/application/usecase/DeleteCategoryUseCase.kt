package io.github.jdreioe.wingmate.application.usecase

import io.github.jdreioe.wingmate.domain.CategoryRepository
import io.github.jdreioe.wingmate.domain.PhraseRepository

class DeleteCategoryUseCase(
	private val categoryRepo: CategoryRepository,
	private val phraseRepo: PhraseRepository,
) {
	suspend operator fun invoke(id: String) {
		val phrases = phraseRepo.getAll()
		phrases.filter { it.parentId == id }.forEach { phraseRepo.delete(it.id) }
		categoryRepo.delete(id)
	}
}
