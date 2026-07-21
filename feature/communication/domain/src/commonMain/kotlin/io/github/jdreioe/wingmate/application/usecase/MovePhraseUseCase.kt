package io.github.jdreioe.wingmate.application.usecase

import io.github.jdreioe.wingmate.domain.PhraseRepository

class MovePhraseUseCase(private val phraseRepository: PhraseRepository) {
    suspend operator fun invoke(fromIndex: Int, toIndex: Int) {
        phraseRepository.move(fromIndex, toIndex)
    }
}
