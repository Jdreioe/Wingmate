package io.github.jdreioe.wingmate.application.usecase

import io.github.jdreioe.wingmate.domain.FolderPhrase
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.PhraseRepository
import io.github.jdreioe.wingmate.domain.isFolderPhrase
import io.github.jdreioe.wingmate.domain.isGridPhrase
import io.github.jdreioe.wingmate.domain.toFolderPhrase

class GetPhrasesAndCategoriesUseCase(private val phraseRepository: PhraseRepository) {
    suspend operator fun invoke(): Pair<List<Phrase>, List<FolderPhrase>> {
        val all = phraseRepository.getAll()
        val phrases = all.filter { it.isGridPhrase() }
        val folders = all.mapNotNull { it.toFolderPhrase() }
        return phrases to folders
    }
}
