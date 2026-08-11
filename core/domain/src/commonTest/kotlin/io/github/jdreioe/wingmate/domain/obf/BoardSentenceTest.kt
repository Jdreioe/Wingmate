package io.github.jdreioe.wingmate.domain.obf

import kotlin.test.Test
import kotlin.test.assertEquals

class BoardSentenceTest {
    @Test
    fun emptySelectionHasNoSentence() {
        assertEquals("", buildResolvedSentence(emptyList(), emptyMap(), false, "en"))
    }

    @Test
    fun vocalizationTakesPriorityAndUsesBoardLocalization() {
        val selected = listOf(
            button(label = "Hello", vocalization = "Hello spoken"),
            button(label = "world")
        )
        val strings = mapOf(
            "da" to mapOf(
                "Hello spoken" to "Hej",
                "world" to "verden"
            )
        )

        assertEquals("Hej verden", buildResolvedSentence(selected, strings, false, "da-DK"))
    }

    @Test
    fun singleCharacterWordsOnWordScreensAreSpaced() {
        val selected = listOf(
            button(label = "H"),
            button(label = "i")
        )

        assertEquals("H i", buildResolvedSentence(selected, emptyMap(), false, "en"))
    }

    @Test
    fun singleCharacterWordsOnSpellingBoardsJoin() {
        val selected = listOf(
            button(label = "H"),
            button(label = "i")
        )

        assertEquals("Hi", buildResolvedSentence(selected, emptyMap(), true, "en"))
    }

    @Test
    fun explicitSpaceTokensArePreserved() {
        val selected = listOf(
            button(label = "Hello"),
            button(label = " "),
            button(label = "there")
        )

        assertEquals("Hello there", buildResolvedSentence(selected, emptyMap(), false, "en"))
    }

    @Test
    fun explicitSpaceBetweenKeyboardLettersIsPreserved() {
        val selected = listOf(
            button(label = "d"),
            button(label = " "),
            button(label = "e")
        )

        assertEquals("d e", buildResolvedSentence(selected, emptyMap(), false, "en"))
    }

    @Test
    fun spellingBoardDoesNotAutoInsertSpaces() {
        val selected = listOf(
            button(label = "h"),
            button(label = "e"),
            button(label = "l"),
            button(label = "lo")
        )

        assertEquals("hello", buildResolvedSentence(selected, emptyMap(), true, "en"))
    }

    @Test
    fun spellingBoardPreservesExplicitSpaceTokens() {
        val selected = listOf(
            button(label = "hello"),
            button(label = " "),
            button(label = "world")
        )

        assertEquals("hello world", buildResolvedSentence(selected, emptyMap(), true, "en"))
    }

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
