package io.github.jdreioe.wingmate.application.usecase

import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.PhraseRepository

class GetPhrasesAndCategoriesUseCase(private val phraseRepository: PhraseRepository) {
    suspend operator fun invoke(): Pair<List<Phrase>, List<Phrase>> {
        val all = phraseRepository.getAll()
        val phrases = all.filter { !it.isCategory }
        val categories = all.filter { it.isCategory }
        return phrases to categories
    }
}
