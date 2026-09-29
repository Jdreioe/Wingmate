package io.github.jdreioe.wingmate.domain.obf

import kotlin.test.Test
import kotlin.test.assertEquals

class BoardSentenceTest {
    @Test
    fun buttonSpeechPartResolvesLocalizedTextAndRecording() {
        val board = ObfBoard(
            format = "open-board-0.1",
            id = "board",
            strings = mapOf("da" to mapOf("Hello spoken" to "Hej")),
            sounds = listOf(ObfSound(id = "snd", path = "/tmp/hello.wav")),
            buttons = listOf(button(label = "Hello", vocalization = "Hello spoken", soundId = "snd"))
        )
        val part = board.buttonSpeechPart(board.buttons.single(), primaryLanguage = "da-DK")
        assertEquals(ButtonSpeechPart(text = "Hej", language = null, recordingPath = "/tmp/hello.wav", mathMode = false), part)
    }

    @Test
    fun buttonSpeechPartNullWhenNoSpeakableText() {
        val board = ObfBoard(format = "open-board-0.1", id = "board")
        val part = board.buttonSpeechPart(ObfButton(id = "empty"), primaryLanguage = "en")
        assertEquals(null, part)
    }

    @Test
    fun joinSentenceTextUsesSpacesForNormalMode() {
        assertEquals("hello world", joinSentenceText(listOf("hello", "world"), false))
        assertEquals("hello", joinSentenceText(listOf("hello"), false))
        assertEquals("", joinSentenceText(emptyList(), false))
    }

    @Test
    fun joinSentenceTextAutoSpacesEveryWordIncludingSingleCharacters() {
        assertEquals("I want to go", joinSentenceText(listOf("I", "want", "to", "go"), false))
        assertEquals("I a", joinSentenceText(listOf("I", "a"), false))
    }

    @Test
    fun joinSentenceTextDoesNotDoubleSpaceTokensThatAlreadyCarryWhitespace() {
        assertEquals("I want to go", joinSentenceText(listOf("I", "want to", "go"), false))
        assertEquals("I want to", joinSentenceText(listOf("I", "want ", "to"), false))
        assertEquals("I want to go", joinSentenceText(listOf("I", " want", "to", "go"), false))
        assertEquals("Hello there", joinSentenceText(listOf("Hello", " ", "there"), false))
    }

    @Test
    fun joinSentenceTextJoinsSpellingTokensWithoutSeparators() {
        assertEquals("hello", joinSentenceText(listOf("h", "e", "l", "lo"), true))
        assertEquals("hello world", joinSentenceText(listOf("hello", " ", "world"), true))
    }

    @Test
    fun backspaceUndoesTheLastWordSelectionOnCommunicationBoards() {
        assertEquals(
            listOf("I", "need"),
            backspaceSentenceSelection(listOf("I", "need", "help"))
        )
    }

    @Test
    fun backspaceRemovesOneCharacterOnSpellingBoards() {
        assertEquals(
            listOf("hel"),
            backspaceSentenceSelection(listOf("hell"), spellingMode = true)
        )
        assertEquals(
            emptyList(),
            backspaceSentenceSelection(listOf("h"), spellingMode = true)
        )
    }

    private fun button(
        label: String,
        vocalization: String? = null,
        soundId: String? = null
    ) = ObfButton(
        id = "button-$label",
        label = label,
        vocalization = vocalization,
        soundId = soundId
    )
}
