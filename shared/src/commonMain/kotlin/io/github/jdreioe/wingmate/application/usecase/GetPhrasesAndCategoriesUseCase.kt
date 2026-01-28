package io.github.jdreioe.wingmate.application.usecase

import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.PhraseRepository

class GetPhrasesAndCategoriesUseCase(private val phraseRepository: PhraseRepository) {
    suspend operator fun invoke(): Pair<List<Phrase>, List<Phrase>> {
        val all = phraseRepository.getAll()
        // Phrases with linkedBoardId are folder/category items, others are regular phrases
        val phrases = all.filter { it.linkedBoardId == null }
        val folders = all.filter { it.linkedBoardId != null }
        return phrases to folders
    }
}
