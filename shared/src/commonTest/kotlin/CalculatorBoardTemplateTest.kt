import io.github.jdreioe.wingmate.application.CalculatorBoardTemplate
import io.github.jdreioe.wingmate.domain.obf.BoardSetGraph
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.domain.obf.ObfButtonActionEffect
import io.github.jdreioe.wingmate.domain.obf.parseObfButtonActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CalculatorBoardTemplateTest {
    @Test
    fun providesLinkedRegularScientificAndEngineeringPages() {
        val boards = CalculatorBoardTemplate.boards()
        assertEquals(listOf("Regular", "Scientific", "Engineering"), boards.map { it.name })

        val graph = BoardSetGraph(
            boardSet = ObfBoardSet("calculator", "Calculator", boards.first().id, boards.map { it.id }, createdAt = 1, updatedAt = 1),
            boards = boards
        )
        boards.forEach { board ->
            assertTrue(board.grid?.order?.all { row -> row.size == 6 } == true)
            val modeButtons = board.buttons.take(3)
            assertEquals(3, modeButtons.mapNotNull { graph.resolveLinkedBoard(it.loadBoard) }.size)
            val equalsButton = assertNotNull(board.buttons.firstOrNull { it.label == "=" })
            assertEquals("+=", equalsButton.action)
            assertEquals(
                listOf(ObfButtonActionEffect.AppendText("=")),
                parseObfButtonActions(equalsButton)
            )
            assertTrue(board.buttons.any { it.mathMode })
        }
    }
}
