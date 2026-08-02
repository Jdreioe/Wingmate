import io.github.jdreioe.wingmate.application.KeyboardBoardTemplate
import io.github.jdreioe.wingmate.domain.obf.ObfButtonActionEffect
import io.github.jdreioe.wingmate.domain.obf.ObfButtonType
import io.github.jdreioe.wingmate.domain.obf.BoardSetGraph
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.domain.obf.parseObfButtonActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeyboardBoardTemplateTest {
    @Test
    fun providesAnEditableKeyboardWithMessageActions() {
        val boards = KeyboardBoardTemplate.boards()
        val board = boards.first()

        assertEquals("Keyboard", board.name)
        assertEquals(11, board.grid?.columns)
        assertEquals(4, board.grid?.rows)
        assertTrue(board.grid?.order?.all { it.size == 11 } == true)
        assertEquals(ObfButtonActionEffect.AppendText("q"), parseObfButtonActions(board.buttons.first()).single())
        assertEquals(listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"), board.buttons.take(10).map { it.label })
        val spaceId = board.buttons.first { it.action == ":space" }.id
        assertEquals(7, board.grid?.order?.last()?.count { it == spaceId })
        assertTrue(board.buttons.any { it.action == ":space" })
        assertTrue(board.buttons.any { it.action == ":backspace" })
        assertTrue(board.buttons.any { it.action == ":speak" })
        assertTrue(board.buttons.any { it.type == ObfButtonType.NGramPrediction })
        assertEquals(listOf("Keyboard", "Keyboard — uppercase", "Numbers & symbols"), boards.map { it.name })
        assertTrue(boards.all { candidate ->
            candidate.grid?.let { grid -> grid.order.all { row -> row.size == grid.columns } } == true
        })

        val graph = BoardSetGraph(
            ObfBoardSet("keyboard", "Keyboard", board.id, boards.map { it.id }, createdAt = 1, updatedAt = 1),
            boards
        )
        assertTrue(board.buttons.filter { it.label == "⇧" || it.label == "123" }
            .all { graph.resolveLinkedBoard(it.loadBoard) != null })
    }
}
