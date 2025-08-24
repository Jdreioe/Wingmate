package io.github.jdreioe.wingmate.application.usecase

import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.PhraseRepository

class GetAllItemsUseCase(private val phraseRepository: PhraseRepository) {
    suspend operator fun invoke(): List<Phrase> = phraseRepository.getAll()
}
