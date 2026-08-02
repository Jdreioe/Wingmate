package io.github.jdreioe.wingmate.ui

import androidx.compose.ui.graphics.ImageBitmap
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfSound
import io.github.jdreioe.wingmate.domain.obf.ObfMediaUrlLoader
import io.github.jdreioe.wingmate.domain.SoundPlayer
import io.github.jdreioe.wingmate.infrastructure.InMemoryFileStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoardSentenceTest {
    @Test
    fun emptySelectionHasNoSentence() {
        assertEquals("", buildResolvedSentence(emptyList(), board(), "en"))
    }

    @Test
    fun vocalizationTakesPriorityAndUsesBoardLocalization() {
        val selected = listOf(
            selectedButton(label = "Hello", vocalization = "Hello spoken"),
            selectedButton(label = "world")
        )
        val board = board(
            strings = mapOf(
                "da" to mapOf(
                    "Hello spoken" to "Hej",
                    "world" to "verden"
                )
            )
        )

        assertEquals("Hej verden", buildResolvedSentence(selected, board, "da-DK"))
    }

    @Test
    fun singleCharacterTokensAreJoinedForSpelling() {
        val selected = listOf(
            selectedButton(label = "H"),
            selectedButton(label = "i")
        )

        assertEquals("Hi", buildResolvedSentence(selected, board(), "en"))
    }

    @Test
    fun explicitSpaceTokensArePreserved() {
        val selected = listOf(
            selectedButton(label = "Hello"),
            selectedButton(label = " "),
            selectedButton(label = "there")
        )

        assertEquals("Hello there", buildResolvedSentence(selected, board(), "en"))
    }

    @Test
    fun explicitSpaceBetweenKeyboardLettersIsPreserved() {
        val selected = listOf(
            selectedButton(label = "d"),
            selectedButton(label = " "),
            selectedButton(label = "e")
        )

        assertEquals("d e", buildResolvedSentence(selected, board(), "en"))
    }

    @Test
    fun spellingBoardDoesNotAutoInsertSpaces() {
        val spelling = board().withSpellingMode(true)
        val selected = listOf(
            selectedButton(label = "h"),
            selectedButton(label = "e"),
            selectedButton(label = "l"),
            selectedButton(label = "lo")
        )

        assertEquals("hello", buildResolvedSentence(selected, spelling, "en"))
    }

    @Test
    fun spellingBoardPreservesExplicitSpaceTokens() {
        val spelling = board().withSpellingMode(true)
        val selected = listOf(
            selectedButton(label = "hello"),
            selectedButton(label = " "),
            selectedButton(label = "world")
        )

        assertEquals("hello world", buildResolvedSentence(selected, spelling, "en"))
    }

    @Test
    fun soundPlaybackFallsBackFromMalformedDataToStoredPath() = runBlocking {
        val storage = InMemoryFileStorage()
        storage.saveBytes("sound.mp3", byteArrayOf(7, 8))
        val player = CapturingSoundPlayer()
        val played = playButtonSound(
            ObfSound(id = "sound", data = "broken!", path = "sound.mp3"),
            storage,
            player
        )
        assertTrue(played)
        assertEquals(listOf<Byte>(7, 8), player.lastBytes?.toList())
    }

    @Test
    fun failedSoundUrlLeavesTtsFallbackAvailable() = runBlocking {
        val played = playButtonSound(
            ObfSound(id = "sound", url = "https://unavailable.example/sound"),
            InMemoryFileStorage(),
            CapturingSoundPlayer(),
            ObfMediaUrlLoader { null }
        )
        assertFalse(played)
    }

    private fun board(
        strings: Map<String, Map<String, String>> = emptyMap()
    ) = ObfBoard(
        format = "open-board-0.1",
        id = "board",
        strings = strings
    )

    private fun selectedButton(
        label: String,
        vocalization: String? = null
    ): Pair<ObfButton, ImageBitmap?> = ObfButton(
        id = "button-$label",
        label = label,
        vocalization = vocalization
    ) to null

    private class CapturingSoundPlayer : SoundPlayer {
        var lastBytes: ByteArray? = null
        override suspend fun playBytes(bytes: ByteArray, contentType: String?): Boolean {
            lastBytes = bytes.copyOf()
            return true
        }
    }
}
