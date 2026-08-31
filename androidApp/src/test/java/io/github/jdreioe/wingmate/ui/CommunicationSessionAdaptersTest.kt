package io.github.jdreioe.wingmate.ui

import io.github.jdreioe.wingmate.application.QueuedCommunicationSession
import io.github.jdreioe.wingmate.domain.CommunicationAction
import io.github.jdreioe.wingmate.domain.Message
import io.github.jdreioe.wingmate.domain.MessagePart
import io.github.jdreioe.wingmate.domain.MessagePartSource
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.TextSpan
import io.github.jdreioe.wingmate.domain.fromScreenButton
import io.github.jdreioe.wingmate.domain.fromTextDiff
import io.github.jdreioe.wingmate.domain.toScreenButtons
import io.github.jdreioe.wingmate.domain.obf.BoardSetGraph
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfSound
import io.github.jdreioe.wingmate.infrastructure.InMemoryCommunicationSessionDataSource
import io.github.jdreioe.wingmate.infrastructure.InMemorySaidTextRepository
import io.github.jdreioe.wingmate.infrastructure.NoopSpeechService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CommunicationSessionAdaptersTest {
    @Test
    fun `text replacement describes only the changed range`() {
        assertEquals(
            CommunicationAction.ReplaceRange(
                range = TextSpan(6, 11),
                replacement = "Jonas",
                mathMode = true,
            ),
            Message.fromTextDiff("Hello world", "Hello Jonas", mathMode = true),
        )
    }

    @Test
    fun `screen button keeps speech metadata and maps back to original symbol`() {
        val button = ObfButton(
            id = "help",
            label = "Help",
            vocalization = "Please help",
            locale = "da-DK",
            soundId = "help-audio",
        ).withMathMode(true)
        val board = ObfBoard(
            format = "open-board-0.1",
            id = "home",
            buttons = listOf(button),
            sounds = listOf(ObfSound(id = "help-audio", path = "audio/help.wav")),
        )
        val graph = BoardSetGraph(
            boardSet = ObfBoardSet(
                id = "core",
                name = "Core",
                rootBoardId = board.id,
                boardIds = listOf(board.id),
                createdAt = 1L,
                updatedAt = 1L,
            ),
            boards = listOf(board),
        )

        val part = requireNotNull(MessagePart.fromScreenButton("core", board, button, "en-US"))
        val message = Message().appendPart(part, spellingMode = false)

        assertEquals("Please help", part.spokenText)
        assertEquals("da-DK", part.languageTag)
        assertEquals("audio/help.wav", part.recordingPath)
        assertEquals(true, part.mathMode)
        assertEquals(MessagePartSource.ScreenButton("core", "home", "help"), part.source)
        assertSame(button, message.toScreenButtons(graph).single())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `typing and Screens edit one shared session`() = runTest {
        val session = QueuedCommunicationSession(
            dataSource = InMemoryCommunicationSessionDataSource(),
            speechService = NoopSpeechService(),
            saidTextRepository = InMemorySaidTextRepository(),
            currentSettings = { Settings(primaryLanguage = "en-US") },
            scope = backgroundScope,
        )
        runCurrent()
        val button = ObfButton(id = "help", label = "Help")
        val board = ObfBoard(
            format = "open-board-0.1",
            id = "home",
            buttons = listOf(button),
        )

        val screenPart = requireNotNull(MessagePart.fromScreenButton("core", board, button, "en-US"))
        session.accept(CommunicationAction.AppendPart(screenPart, spellingMode = false))
        val textAfterScreen = session.state.value.activeMessage.displayText
        session.accept(Message.fromTextDiff(textAfterScreen, "$textAfterScreen now"))

        assertEquals("Help now", session.state.value.activeMessage.displayText)
        assertEquals(
            listOf(MessagePartSource.ScreenButton::class, MessagePartSource.Typed::class),
            session.state.value.activeMessage.parts.map { it.source::class },
        )
    }
}
