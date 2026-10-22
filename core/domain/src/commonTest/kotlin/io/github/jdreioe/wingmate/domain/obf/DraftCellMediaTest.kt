package io.github.jdreioe.wingmate.domain.obf

import kotlin.test.Test
import kotlin.test.assertEquals

class DraftCellMediaTest {
    @Test
    fun `clearing a Button's media drops it unless another Button shares it`() {
        val page = ObfBoard(
            format = "open-board-0.1",
            id = "page",
            buttons = listOf(
                ObfButton(id = "a", label = "A", imageId = "own", soundId = "shared"),
                ObfButton(id = "b", label = "B", soundId = "shared"),
            ),
            images = listOf(ObfImage(id = "own", url = "https://example.org/a.png")),
            sounds = listOf(ObfSound(id = "shared", path = "/sounds/a.wav")),
            grid = ObfGrid(rows = 1, columns = 2, order = listOf(listOf("a", "b"))),
        )
        val screen = ObfBoardSet(id = "screen", name = "Screen", rootBoardId = "page", boardIds = listOf("page"), createdAt = 1, updatedAt = 1)

        val updated = updateDraftCell(
            BoardSetGraph(screen, listOf(page)), "page", row = 0, column = 0,
            label = "A", vocalization = null, imageUrl = null, recordingPath = null,
            backgroundColor = null, language = null, linkedBoardId = null,
        ).boardsById.getValue("page")

        assertEquals(emptyList(), updated.images)
        assertEquals(listOf("shared"), updated.sounds.map { it.id })
    }
}
