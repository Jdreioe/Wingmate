package io.github.jdreioe.wingmate.application.usecase

import io.github.jdreioe.wingmate.domain.PhraseRepository

class DeletePhraseUseCase(private val phraseRepository: PhraseRepository) {
    suspend operator fun invoke(phraseId: String) {
        if (phraseId.isBlank()) {
            throw IllegalArgumentException("Phrase ID cannot be blank")
        }
        phraseRepository.delete(phraseId)
    }
}
