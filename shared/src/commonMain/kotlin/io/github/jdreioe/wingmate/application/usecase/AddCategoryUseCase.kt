package io.github.jdreioe.wingmate.application.usecase

import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.PhraseRepository
import io.github.jdreioe.wingmate.domain.SpeechTextProcessor

class AddCategoryUseCase(private val phraseRepository: PhraseRepository) {
	suspend operator fun invoke(name: String): Phrase {
		val trimmed = name.trim()
		return phraseRepository.add(
			Phrase(
			id = "",
			text = SpeechTextProcessor.normalizeShorthandSsml(trimmed),
			name = trimmed,
			createdAt = 0,
			parentId = null,
			linkedBoardId = "",
			isGridItem = false
		)
		)
	}
}
