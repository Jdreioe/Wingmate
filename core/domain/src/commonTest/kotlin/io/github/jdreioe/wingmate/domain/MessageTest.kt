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

    @Test
    fun `range replacement keeps language spans aligned`() {
        val original = Message(parts = listOf(MessagePart("hello world")))
            .toggleLanguage(TextSpan(6, 11), "da-DK")

        val edited = original.replaceRange(0, 5, MessagePart("hi"))

        assertEquals("hi world", edited.displayText)
        assertEquals(
            listOf(MessageLanguageSpan(TextSpan(3, 8), "da-DK")),
            edited.languageSpans,
        )
    }

    @Test
    fun `appended screen part carries its separator and provenance`() {
        val first = MessagePart(
            displayText = "I",
            source = MessagePartSource.ScreenButton("screen", "page", "i"),
        )
        val second = MessagePart(
            displayText = "want",
            source = MessagePartSource.ScreenButton("screen", "page", "want"),
        )

        val message = Message().appendPart(first, spellingMode = false).appendPart(second, spellingMode = false)

        assertEquals("I want", message.displayText)
        assertEquals(" want", message.parts.last().displayText)
        assertIs<MessagePartSource.ScreenButton>(message.parts.last().source)
    }

    @Test
    fun `adjacent typed parts with different speech modes stay distinct`() {
        val message = Message(parts = listOf(MessagePart("two")))
            .insertPart(3, MessagePart(" plus two", mathMode = true))

        assertEquals(listOf(false, true), message.parts.map { it.mathMode })
    }

    @Test
    fun `typing an edited structured part back exactly restores its vocalization`() {
        val originalPart = MessagePart(
            displayText = "WC",
            spokenText = "toilet",
            source = MessagePartSource.Phrase("wc"),
        )

        val edited = Message(parts = listOf(originalPart)).edit("Bathroom")
        val restored = edited.edit("WC")

        assertIs<MessagePartSource.Typed>(edited.parts.single().source)
        assertEquals(listOf(MessageEditProvenance(TextSpan(0, 8), originalPart)), edited.editProvenance)
        assertEquals(originalPart, restored.parts.single())
        assertEquals("toilet", restored.spokenText)
        assertTrue(restored.editProvenance.isEmpty())
    }
}
