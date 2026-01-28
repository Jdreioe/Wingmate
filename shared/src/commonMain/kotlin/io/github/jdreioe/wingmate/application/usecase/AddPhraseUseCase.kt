package io.github.jdreioe.wingmate.application.usecase

import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.PhraseRepository

class AddPhraseUseCase(private val phraseRepository: PhraseRepository) {
    suspend operator fun invoke(text: String, categoryId: String?): Phrase {
        val phrase = Phrase(
            id = "", // Repository will generate ID
            text = text,
            name = null,
            backgroundColor = null,
            parentId = categoryId,
            createdAt = 0 // Repository will set timestamp
        )
        return phraseRepository.add(phrase)
    }
}
