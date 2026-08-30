package io.github.jdreioe.wingmate.domain.obf

import kotlin.test.Test
import kotlin.test.assertFailsWith

class BoardSetGraphValidationTest {
    @Test
    fun `action strips cannot reference a missing Button`() {
        val element = PageElement("actions", PageElementTypes.ActionStrip, row = 0, column = 0)
            .withConfiguration(ActionStripElementConfig(listOf("missing")))
        val page = ObfBoard(format = "open-board-0.1", id = "page").withPageElements(listOf(element))
        val screen = ObfBoardSet(
            id = "screen",
            name = "Screen",
            rootBoardId = page.id,
            boardIds = listOf(page.id),
            createdAt = 1,
            updatedAt = 1,
        )

        assertFailsWith<IllegalArgumentException> {
            BoardSetGraph(screen, listOf(page)).requireValid()
        }
    }
}
