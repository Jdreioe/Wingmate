package io.github.jdreioe.wingmate.domain

import io.github.jdreioe.wingmate.domain.obf.BoardActivationBehavior
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MessageTest {
    @Test
    fun `phrase activation retains display spoken text and provenance`() {
        val phrase = Phrase(id = "tea", text = "Tea", name = "A cup of tea", createdAt = 1)

        val result = Message().activatePhrase(
            phrase = phrase,
            cursor = 0,
            activationBehavior = BoardActivationBehavior.SpeakAndAdd,
            speechPolicy = SpeechPolicy.Immediate,
        )

        assertEquals("Tea", result.message.displayText)
        assertEquals("A cup of tea", result.message.spokenText)
        assertIs<MessagePartSource.Phrase>(result.message.parts.single().source)
        assertTrue(result.shouldSpeak)
    }

    @Test
    fun `typing around a phrase preserves the untouched phrase part`() {
        val original = Message().insertPhrase(
            cursor = 0,
            phrase = Phrase(id = "tea", text = "Tea", createdAt = 1),
        )

        val edited = original.edit("Hot Tea").edit("Hot Tea please")

        assertEquals("Hot Tea please", edited.displayText)
        assertIs<MessagePartSource.Phrase>(edited.parts.single { it.displayText == "Tea" }.source)
    }

    @Test
    fun `speak-only activation leaves the Message unchanged`() {
        val result = Message().activatePhrase(
            phrase = Phrase(id = "tea", text = "Tea", createdAt = 1),
            cursor = 0,
            activationBehavior = BoardActivationBehavior.SpeakOnly,
            speechPolicy = SpeechPolicy.SentenceOnly,
        )

        assertTrue(result.message.parts.isEmpty())
        assertFalse(result.shouldSpeak)
    }
}
