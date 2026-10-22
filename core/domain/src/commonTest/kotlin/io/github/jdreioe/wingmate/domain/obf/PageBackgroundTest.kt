package io.github.jdreioe.wingmate.domain.obf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PageBackgroundTest {
    private val page = ObfBoard(
        format = "open-board-0.1",
        id = "page",
        buttons = listOf(ObfButton(id = "b", label = "B", imageId = "shared")),
        images = listOf(ObfImage(id = "shared", path = "img/shared.png")),
    )

    @Test
    fun `replacing the background drops its old image and keeps one a Button still uses`() {
        val first = page.withBackgroundImage(ObfImage(id = "bg1", path = "img/bg1.png"), PageBackgroundFit.Fit)
        assertEquals(PageBackgroundImage("bg1", PageBackgroundFit.Fit), first.backgroundImage)
        assertEquals(setOf("shared", "bg1"), first.usedImageIds())

        val second = first.withBackgroundImage(ObfImage(id = "bg2", path = "img/bg2.png"))
        assertEquals(listOf("shared", "bg2"), second.images.map { it.id })

        val sharedBackground = page.withBackgroundImage(page.images.single()).withBackgroundImage(null)
        assertNull(sharedBackground.backgroundImage)
        assertEquals(listOf("shared"), sharedBackground.images.map { it.id })
    }

    @Test
    fun `a background whose image entry is missing is ignored`() {
        val orphaned = page.withBackgroundImage(ObfImage(id = "bg", path = "img/bg.png")).copy(images = page.images)
        assertNull(orphaned.backgroundImage)
    }
}
