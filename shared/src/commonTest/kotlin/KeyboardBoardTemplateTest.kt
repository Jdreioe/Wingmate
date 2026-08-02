import io.github.jdreioe.wingmate.application.KeyboardPreset
import io.github.jdreioe.wingmate.application.KeyboardBoardTemplate
import io.github.jdreioe.wingmate.domain.obf.ObfButtonActionEffect
import io.github.jdreioe.wingmate.domain.obf.ObfKeyboardLayout
import io.github.jdreioe.wingmate.domain.obf.BoardSetGraph
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.domain.obf.parseObfButtonActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyboardBoardTemplateTest {
    @Test
    fun providesAnEditableKeyboardWithMessageActions() {
        val boards = KeyboardBoardTemplate.boards()
        val board = boards.first()

        assertEquals("Keyboard", board.name)
        assertEquals(11, board.grid?.columns)
        assertEquals(5, board.grid?.rows)
        assertTrue(board.grid?.order?.all { it.size == 11 } == true)
        assertEquals(ObfButtonActionEffect.Predictions, parseObfButtonActions(board.buttons.first()).single())
        val qButton = board.buttons.first { it.label == "q" }
        assertEquals("q", qButton.label)
        assertEquals(ObfButtonActionEffect.AppendText("q"), parseObfButtonActions(qButton).single())
        assertTrue(board.grid?.order?.first()?.any { id -> board.buttons.first { it.id == id }.label == "Predict" } == true)
        val spaceId = board.buttons.first { it.action == ":space" }.id
        assertEquals(9, board.grid?.order?.last { it.any { id -> id == spaceId } }?.count { it == spaceId })
        assertTrue(board.buttons.any { it.action == ":space" })
        assertTrue(board.buttons.any { it.action == ":backspace" })
        assertTrue(board.buttons.any { it.action == ":speak" })
        assertTrue(board.buttons.any { it.action == ":clear" })
        assertTrue(board.buttons.any { parseObfButtonActions(it).singleOrNull() == ObfButtonActionEffect.Predictions })
        assertEquals(4, board.buttons.count { parseObfButtonActions(it).singleOrNull() == ObfButtonActionEffect.Predictions })
        assertTrue(board.spellingMode)
        assertTrue(board.isKeyboard)
        assertEquals(ObfKeyboardLayout.Qwerty, board.keyboardLayout)
        assertTrue(boards.all { it.isKeyboard })
        assertEquals(
            listOf(ObfKeyboardLayout.Qwerty, ObfKeyboardLayout.Qwerty, ObfKeyboardLayout.Symbols),
            boards.map { it.keyboardLayout }
        )
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

    @Test
    fun alphabeticalPresetLaysOutLettersInOrder() {
        val boards = KeyboardBoardTemplate.boards(KeyboardPreset.Alphabetical)

        assertEquals(
            listOf(ObfKeyboardLayout.Alphabetical, ObfKeyboardLayout.Alphabetical, ObfKeyboardLayout.Symbols),
            boards.map { it.keyboardLayout }
        )
        assertTrue(boards.all { it.isKeyboard })
        assertTrue(boards.all { it.spellingMode })

        val lowercase = boards.first()
        assertEquals("a", lowercase.buttons.first { it.label == "a" }.label)
        val lettersIn: (Int) -> List<String> = { rowIndex ->
            lowercase.grid!!.order[rowIndex].filterNotNull().mapNotNull { id ->
                lowercase.buttons.firstOrNull { it.id == id }?.label
            }.filter { it.length == 1 && it.first().isLetter() }
        }
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j"), lettersIn(1))
        assertEquals(listOf("k", "l", "m", "n", "o", "p", "q", "r", "s"), lettersIn(2))
        assertEquals(listOf("t", "u", "v", "w", "x", "y", "z"), lettersIn(3))

        val uppercase = boards[1]
        assertEquals("A", uppercase.buttons.first { it.label == "A" }.label)

        val symbols = boards[2]
        assertEquals(ObfKeyboardLayout.Symbols, symbols.keyboardLayout)
        assertNull(symbols.buttons.firstOrNull { it.label == "a" })
    }
}