package io.github.jdreioe.wingmate.application.usecase

import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.PhraseRepository
import io.github.jdreioe.wingmate.domain.SpeechTextProcessor

class UpdatePhraseUseCase(private val phraseRepository: PhraseRepository) {
    suspend operator fun invoke(
        id: String,
        text: String?,
        name: String?,
        imageUrl: String?,
        recordingPath: String?
    ): Phrase {
        val existing = phraseRepository.getAll().firstOrNull { it.id == id }
            ?: error("Phrase not found")
        val updated = existing.copy(
            text = text?.let { SpeechTextProcessor.normalizeShorthandSsml(it) } ?: existing.text,
            name = name?.let { SpeechTextProcessor.normalizeShorthandSsml(it) } ?: existing.name,
            imageUrl = imageUrl?.let { it.takeIf { value -> value.isNotBlank() } },
            recordingPath = recordingPath ?: existing.recordingPath
        )
        return phraseRepository.update(updated)
    }
}
