package io.github.jdreioe.wingmate.ui

import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.CommunicationAction
import io.github.jdreioe.wingmate.domain.Message
import io.github.jdreioe.wingmate.domain.MessagePart
import io.github.jdreioe.wingmate.domain.MessagePartSource
import io.github.jdreioe.wingmate.domain.TextSpan
import io.github.jdreioe.wingmate.domain.obf.BoardSetGraph
import io.github.jdreioe.wingmate.domain.obf.MessageBarAction
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreensMessageBarLogicTest {
    @Test
    fun `buildScreensMessagePartItems resolves Screen button labels and images while keeping typed parts`() {
        val waterImage = ObfImage(id = "img-water", url = "https://example.com/water.png")
        val board = ObfBoard(
            format = "open-board-0.1",
            id = "page-1",
            buttons = listOf(
                ObfButton(id = "btn-want", label = "Want"),
                ObfButton(id = "btn-water", label = "Water", imageId = "img-water"),
            ),
            images = listOf(waterImage),
        )
        val graph = BoardSetGraph(
            boardSet = ObfBoardSet(
                id = "screen-1",
                name = "Core",
                rootBoardId = "page-1",
                boardIds = listOf("page-1"),
                createdAt = 1L,
                updatedAt = 1L,
            ),
            boards = listOf(board),
        )
        val message = Message(
            parts = listOf(
                MessagePart(
                    displayText = "I",
                    source = MessagePartSource.Typed,
                ),
                MessagePart(
                    displayText = " want",
                    source = MessagePartSource.ScreenButton("screen-1", "page-1", "btn-want"),
                ),
                MessagePart(
                    displayText = " water",
                    source = MessagePartSource.ScreenButton("screen-1", "page-1", "btn-water"),
                ),
            )
        )

        val items = buildScreensMessagePartItems(message, graph, locale = "en-US")

        assertEquals(3, items.size)
        assertEquals(ScreensMessagePartItem(index = 0, title = "I", image = null), items[0])
        assertEquals(ScreensMessagePartItem(index = 1, title = "Want", image = null), items[1])
        assertEquals(ScreensMessagePartItem(index = 2, title = "Water", image = waterImage), items[2])
    }

    @Test
    fun `removeMessagePartAction targets the exact span of the chosen part`() {
        val message = Message(
            parts = listOf(
                MessagePart("Hello"),
                MessagePart(" cold"),
                MessagePart(" water"),
            )
        )

        assertEquals(
            CommunicationAction.ReplaceRange(TextSpan(0, 5), ""),
            removeMessagePartAction(message, 0),
        )
        assertEquals(
            CommunicationAction.ReplaceRange(TextSpan(5, 10), ""),
            removeMessagePartAction(message, 1),
        )
        assertEquals(
            CommunicationAction.ReplaceRange(TextSpan(10, 16), ""),
            removeMessagePartAction(message, 2),
        )
        assertNull(removeMessagePartAction(message, -1))
        assertNull(removeMessagePartAction(message, 3))
    }

    @Test
    fun `message bar action slot helpers prevent duplicates, cap at four, and keep at least one`() {
        val initial = listOf(MessageBarAction.Speak)

        // Cannot remove the last remaining button.
        assertEquals(initial, removeMessageBarAction(initial, 0))

        // Adding works up to four and ignores duplicates.
        val two = addMessageBarAction(initial, MessageBarAction.Hold)
        assertEquals(listOf(MessageBarAction.Speak, MessageBarAction.Hold), two)
        assertEquals(two, addMessageBarAction(two, MessageBarAction.Hold))

        val three = addMessageBarAction(two, MessageBarAction.DeleteLast)
        val four = addMessageBarAction(three, MessageBarAction.Clear)
        assertEquals(4, four.size)
        assertEquals(four, addMessageBarAction(four, MessageBarAction.Speak))

        // Replacing rejects actions already present in another slot, allows unused ones.
        assertEquals(two, replaceMessageBarAction(two, 1, MessageBarAction.Speak))
        assertEquals(
            listOf(MessageBarAction.Speak, MessageBarAction.Clear),
            replaceMessageBarAction(two, 1, MessageBarAction.Clear),
        )

        // Removing works once more than one button is present.
        assertEquals(
            listOf(MessageBarAction.Speak),
            removeMessageBarAction(two, 1),
        )
    }

    @Test
    fun `message bar height matches the two-by-two button square on phone and tablet`() {
        assertEquals(104.dp, screensMessageBarHeight(large = false))
        assertEquals(120.dp, screensMessageBarHeight(large = true))
    }
}
