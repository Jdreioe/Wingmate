package io.github.jdreioe.wingmate.application.usecase

import io.github.jdreioe.wingmate.domain.PhraseRepository
import io.github.jdreioe.wingmate.domain.phraseSubtree

class DeletePhraseUseCase(private val phraseRepository: PhraseRepository) {
    suspend operator fun invoke(phraseId: String) {
        if (phraseId.isBlank()) {
            throw IllegalArgumentException("Phrase ID cannot be blank")
        }
        val subtree = phraseSubtree(phraseRepository.getAll(), phraseId)
        subtree.asReversed().forEach { phraseRepository.delete(it.id) }
    }
}
