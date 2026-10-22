package io.github.jdreioe.wingmate.domain.obf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoardPrintLayoutTest {
    private val home = ObfBoard(
        format = "open-board-0.1",
        id = "home",
        name = "Home",
        buttons = listOf(
            ObfButton(id = "hi", label = "Hi", backgroundColor = "rgb(255,255,0)"),
            ObfButton(id = "food", label = "Food", loadBoard = ObfLoadBoard(id = "food")),
            ObfButton(id = "secret", label = "Secret", hidden = true),
        ),
        grid = ObfGrid(rows = 2, columns = 2, order = listOf(listOf("hi", "food"), listOf("secret", null))),
    )
    private val food = ObfBoard(
        format = "open-board-0.1",
        id = "food",
        name = "Food",
        strings = mapOf("da" to mapOf("Food" to "Mad", "Apple" to "Æble")),
        buttons = listOf(ObfButton(id = "apple", label = "Apple"), ObfButton(id = "back", label = "Home", loadBoard = ObfLoadBoard(id = "home"))),
    )
    private val graph = BoardSetGraph(
        ObfBoardSet(id = "s", name = "S", rootBoardId = "home", boardIds = listOf("food", "home"), createdAt = 1, updatedAt = 1),
        listOf(food, home),
    )

    @Test
    fun `the root Page prints first and links name the Page they open`() {
        val pages = printableScreen(graph, locale = "en")

        assertEquals(listOf(1 to "Home", 2 to "Food"), pages.map { it.number to it.title })
        val link = pages[0].cells.single { it.label == "Food" }.opens
        assertEquals(PrintedLink("Food", 2), link)
        assertNull(pages[0].cells.single { it.label == "Hi" }.opens)
    }

    @Test
    fun `hidden Buttons are left out and the grid keeps its size`() {
        val home = printableScreen(graph, locale = "en")[0]

        assertEquals(listOf("Hi", "Food"), home.cells.map { it.label })
        assertEquals(2 to 2, home.rows to home.columns)
    }

    @Test
    fun `labels and titles are localized and Pages without a grid still print`() {
        val food = printableScreen(graph, locale = "da")[1]

        assertEquals("Mad", food.title)
        assertEquals(listOf("Æble", "Home"), food.cells.map { it.label })
        assertEquals(listOf(0 to 0, 0 to 1), food.cells.map { it.row to it.column })
        assertEquals(PrintedLink("Home", 1), food.cells[1].opens)
    }
}
