import io.github.jdreioe.wingmate.application.BoardSetUseCase
import io.github.jdreioe.wingmate.application.NoopFeatureUsageReporter
import io.github.jdreioe.wingmate.domain.obf.BoardSetGraph
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import io.github.jdreioe.wingmate.domain.obf.ObfLoadBoard
import io.github.jdreioe.wingmate.infrastructure.InMemoryBoardRepository
import io.github.jdreioe.wingmate.infrastructure.InMemoryBoardSetRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BoardSetUseCaseTest {
    private val boardRepository = InMemoryBoardRepository()
    private val boardSetRepository = InMemoryBoardSetRepository()
    private val useCase = BoardSetUseCase(
        boardSetRepository,
        boardRepository,
        NoopFeatureUsageReporter()
    )

    @Test
    fun saveAndLoadGraphKeepsEveryBoardAndLink() = runBlocking {
        val graph = linkedGraph()

        val saved = useCase.saveBoardSetGraph(graph).getOrThrow()
        val loaded = useCase.loadBoardSetGraph(saved.boardSet.id)

        assertNotNull(loaded)
        assertEquals(setOf("home", "food"), loaded.boardsById.keys)
        assertEquals("home", loaded.rootBoard?.id)
        assertEquals("food", loaded.rootBoard?.buttons?.single()?.loadBoard?.id)
    }

    @Test
    fun duplicateGraphRemapsInternalBoardLinks() = runBlocking {
        useCase.saveBoardSetGraph(linkedGraph()).getOrThrow()

        val copiedSet = assertNotNull(useCase.duplicateBoardSet("set"))
        val copied = assertNotNull(useCase.loadBoardSetGraph(copiedSet.id))
        val copiedTarget = copied.rootBoard?.buttons?.single()?.loadBoard?.id

        assertNotEquals("set", copiedSet.id)
        assertNotEquals("home", copiedSet.rootBoardId)
        assertTrue(copiedTarget in copiedSet.boardIds)
        assertNotEquals("food", copiedTarget)
        assertEquals(2, copied.boards.size)
    }

    @Test
    fun saveRejectsLinksOutsideTheBoardSet() = runBlocking {
        val source = linkedGraph()
        val brokenHome = source.rootBoard!!.copy(
            buttons = source.rootBoard!!.buttons.map {
                it.copy(loadBoard = ObfLoadBoard(id = "missing"))
            }
        )

        assertFailsWith<IllegalArgumentException> {
            useCase.saveBoardSetGraph(
                source.copy(boards = source.boards.map { if (it.id == "home") brokenHome else it })
            ).getOrThrow()
        }
        Unit
    }

    @Test
    fun savingADeletedBoardRemovesItFromTheRepository() = runBlocking {
        val original = linkedGraph()
        useCase.saveBoardSetGraph(original).getOrThrow()
        val homeOnly = original.copy(
            boardSet = original.boardSet.copy(boardIds = listOf("home")),
            boards = listOf(
                original.rootBoard!!.copy(
                    buttons = original.rootBoard!!.buttons.map { it.copy(loadBoard = null) }
                )
            )
        )

        useCase.saveBoardSetGraph(homeOnly).getOrThrow()

        assertEquals(null, boardRepository.getBoard("food"))
        assertEquals(listOf("home"), useCase.loadBoardSetGraph("set")?.boardSet?.boardIds)
    }

    private fun linkedGraph(): BoardSetGraph {
        val home = ObfBoard(
            format = "open-board-0.1",
            id = "home",
            name = "Home",
            buttons = listOf(
                ObfButton(id = "to-food", label = "Food", loadBoard = ObfLoadBoard(id = "food"))
            ),
            grid = ObfGrid(rows = 1, columns = 1, order = listOf(listOf("to-food")))
        )
        val food = ObfBoard(
            format = "open-board-0.1",
            id = "food",
            name = "Food",
            grid = ObfGrid(rows = 1, columns = 1, order = listOf(listOf(null)))
        )
        return BoardSetGraph(
            boardSet = ObfBoardSet(
                id = "set",
                name = "Everyday",
                rootBoardId = "home",
                boardIds = listOf("home", "food"),
                createdAt = 1,
                updatedAt = 1
            ),
            boards = listOf(home, food)
        )
    }
}
