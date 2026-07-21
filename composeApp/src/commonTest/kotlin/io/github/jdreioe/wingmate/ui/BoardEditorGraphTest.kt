package io.github.jdreioe.wingmate.ui

import io.github.jdreioe.wingmate.domain.obf.BoardSetGraph
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import io.github.jdreioe.wingmate.domain.obf.ObfLoadBoard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class BoardEditorGraphTest {
    @Test
    fun screenAndIndividualPageNamesCanBeChangedIndependently() {
        val graph = graph()

        val renamedScreen = renameDraftBoardSet(graph, "My communication")
        val renamedPage = renameDraftBoard(renamedScreen, "home", "Everyday words")

        assertEquals("My communication", renamedPage.boardSet.name)
        assertEquals("Everyday words", renamedPage.boards.single().name)
    }

    @Test
    fun movingAndResizingPagesProduceNewUndoableGraphValues() {
        val graph = graph(
            order = listOf(
                listOf("first", "second"),
                listOf(null, null)
            )
        )

        val moved = moveDraftField(graph, "home", 0, 0, 0, 1)
        val resized = resizeDraftBoard(moved, "home", rows = 3, columns = 2)

        assertNotEquals(graph, moved)
        assertEquals(listOf("second", "first"), moved.boards.single().grid?.order?.first())
        assertEquals(3, resized.boards.single().grid?.rows)
        assertEquals(2, graph.boards.single().grid?.rows)
    }

    @Test
    fun homeNavigationIsPinnedToTheBottomLeftAndFollowsPageResizing() {
        val homeButton = ObfButton(
            id = "go_home",
            label = "Home",
            loadBoard = ObfLoadBoard(id = "home", name = "Home")
        )
        val otherButtons = listOf("one", "two", "three").map { id ->
            ObfButton(id = id, label = id)
        }
        val page = ObfBoard(
            format = "open-board-0.1",
            id = "page",
            name = "Page",
            buttons = otherButtons + homeButton,
            grid = ObfGrid(
                rows = 3,
                columns = 2,
                order = listOf(
                    listOf("go_home", "one"),
                    listOf("two", null),
                    listOf("three", null)
                )
            )
        )
        val root = ObfBoard(
            format = "open-board-0.1",
            id = "home",
            name = "Home",
            grid = ObfGrid(1, 1, listOf(listOf(null)))
        )
        val graph = BoardSetGraph(
            boardSet = ObfBoardSet(
                id = "set",
                name = "Set",
                rootBoardId = root.id,
                boardIds = listOf(root.id, page.id),
                createdAt = 1L,
                updatedAt = 1L
            ),
            boards = listOf(root, page)
        )

        val pinned = graph.withHomeFieldsBottomLeft()
        assertEquals("go_home", pinned.boards.last().grid?.order?.last()?.first())

        val resized = resizeDraftBoard(pinned, page.id, rows = 2, columns = 2)
        assertEquals(2, resized.boards.last().grid?.rows)
        assertEquals("go_home", resized.boards.last().grid?.order?.last()?.first())
    }

    private fun graph(
        order: List<List<String?>> = listOf(
            listOf(null, null),
            listOf(null, null)
        )
    ): BoardSetGraph {
        val board = ObfBoard(
            format = "open-board-0.1",
            id = "home",
            name = "Home",
            grid = ObfGrid(rows = 2, columns = 2, order = order)
        )
        return BoardSetGraph(
            boardSet = ObfBoardSet(
                id = "set",
                name = "Original",
                rootBoardId = board.id,
                boardIds = listOf(board.id),
                createdAt = 1L,
                updatedAt = 1L
            ),
            boards = listOf(board)
        )
    }
}
