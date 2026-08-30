import io.github.jdreioe.wingmate.application.BoardSetUseCase
import io.github.jdreioe.wingmate.application.NoopFeatureUsageReporter
import io.github.jdreioe.wingmate.application.ObzExportResult
import io.github.jdreioe.wingmate.application.TypingScreenUseCase
import io.github.jdreioe.wingmate.domain.obf.ScreenKind
import io.github.jdreioe.wingmate.domain.obf.pageElements
import io.github.jdreioe.wingmate.domain.obf.withPageElements
import io.github.jdreioe.wingmate.infrastructure.InMemoryBoardRepository
import io.github.jdreioe.wingmate.infrastructure.InMemoryBoardSetRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TypingScreenUseCaseTest {
    @Test
    fun `seeding is idempotent and does not overwrite customization`() = runBlocking {
        val boards = InMemoryBoardRepository()
        val sets = InMemoryBoardSetRepository()
        val useCase = TypingScreenUseCase(sets, boards)
        val seeded = useCase.getOrCreate(columns = 4)
        val customizedBoard = seeded.rootBoard!!.let { board ->
            val elements = board.pageElements()
            board.withPageElements(elements.mapIndexed { index, element -> element.copy(row = index + 3) })
        }
        boards.saveBoard(customizedBoard)

        val loaded = useCase.getOrCreate(columns = 8)

        assertEquals(customizedBoard, loaded.rootBoard)
        assertEquals(4, loaded.rootBoard!!.grid!!.columns)
    }

    @Test
    fun `system screen is hidden and guarded from vocabulary operations`() = runBlocking {
        val boards = InMemoryBoardRepository()
        val sets = InMemoryBoardSetRepository()
        val typing = TypingScreenUseCase(sets, boards).getOrCreate(columns = 4)
        val boardSets = BoardSetUseCase(sets, boards, NoopFeatureUsageReporter())

        assertTrue(boardSets.listBoardSets().isEmpty())
        assertNull(boardSets.duplicateBoardSet(typing.boardSet.id))
        assertNull(boardSets.toggleLocked(typing.boardSet.id))
        assertNull(boardSets.exportRootBoardAsObf(typing.boardSet.id))
        assertIs<ObzExportResult.Failure>(boardSets.exportBoardSetAsObzResult(typing.boardSet.id))

        boardSets.deleteBoardSet(typing.boardSet.id)
        assertEquals(ScreenKind.Typing, sets.getBoardSet(typing.boardSet.id)?.kind)
    }
}
