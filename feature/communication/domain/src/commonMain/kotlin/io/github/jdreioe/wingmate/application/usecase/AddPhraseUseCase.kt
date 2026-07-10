package io.github.jdreioe.wingmate.application.usecase

import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.PhraseRepository
import io.github.jdreioe.wingmate.domain.SpeechTextProcessor

class AddPhraseUseCase(private val phraseRepository: PhraseRepository) {
    suspend operator fun invoke(
        text: String,
        categoryId: String?,
        name: String? = null,
        imageUrl: String? = null,
        recordingPath: String? = null
    ): Phrase {
        val phrase = Phrase(
            id = "", // Repository will generate ID
            text = SpeechTextProcessor.normalizeShorthandSsml(text),
            name = name?.takeIf { it.isNotBlank() }?.let { SpeechTextProcessor.normalizeShorthandSsml(it) },
            backgroundColor = null,
            imageUrl = imageUrl,
            parentId = categoryId,
            createdAt = 0, // Repository will set timestamp
            recordingPath = recordingPath
        )
        return phraseRepository.add(phrase)
    }
}
