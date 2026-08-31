package io.github.jdreioe.wingmate.application.usecase

import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.PhraseRepository
import io.github.jdreioe.wingmate.domain.SpeechTextProcessor

class UpdatePhraseUseCase(private val phraseRepository: PhraseRepository) {
    suspend operator fun invoke(
        id: String,
        text: String? = null,
        name: String? = null,
        imageUrl: String? = null,
        recordingPath: String? = null,
        parentId: String? = null,
        linkedBoardId: String? = null,
        isHidden: Boolean? = null
    ): Phrase {
        val existing = phraseRepository.getAll().firstOrNull { it.id == id }
            ?: error("Phrase not found")
        val updated = existing.copy(
            text = text?.let { SpeechTextProcessor.normalizeShorthandSsml(it) } ?: existing.text,
            name = name?.let { SpeechTextProcessor.normalizeShorthandSsml(it) } ?: existing.name,
            // A missing value leaves the field alone. The native editors send an
            // explicit blank value when the user removes it.
            imageUrl = imageUrl.resolveInto(existing.imageUrl),
            recordingPath = recordingPath.resolveInto(existing.recordingPath),
            parentId = parentId.resolveInto(existing.parentId),
            linkedBoardId = linkedBoardId.resolveInto(existing.linkedBoardId),
            isHidden = isHidden ?: existing.isHidden
        )
        return phraseRepository.update(updated)
    }

    private fun String?.resolveInto(current: String?): String? = when (this) {
        null -> current
        else -> trim().ifBlank { null }
    }
}
