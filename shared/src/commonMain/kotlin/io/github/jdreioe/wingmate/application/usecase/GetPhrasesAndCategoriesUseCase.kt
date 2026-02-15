package io.github.jdreioe.wingmate.application.usecase

import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.PhraseRepository

class GetPhrasesAndCategoriesUseCase(private val phraseRepository: PhraseRepository) {
    suspend operator fun invoke(): Pair<List<Phrase>, List<Phrase>> {
        val all = phraseRepository.getAll()
        
        // Items for the Grid: explicity marked for grid OR (default behavior: buttons are grid items)
        val phrases = all.filter { 
            it.isGridItem == true || (it.isGridItem == null && it.linkedBoardId == null) 
        }
        
        // Items for the Category bar: explicitly marked NOT for grid OR (default behavior: folders are chips)
        val folders = all.filter { 
            it.isGridItem == false || (it.isGridItem == null && it.linkedBoardId != null) 
        }
        
        return phrases to folders
    }
}
