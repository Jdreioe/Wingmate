import io.github.jdreioe.wingmate.application.BoardSetExportStatus
import io.github.jdreioe.wingmate.application.BoardSetSpeechCacheUseCase
import io.github.jdreioe.wingmate.application.BoardSetUseCase
import io.github.jdreioe.wingmate.application.BoardsFacade
import io.github.jdreioe.wingmate.application.NativeFailure
import io.github.jdreioe.wingmate.application.NativeFailureKind
import io.github.jdreioe.wingmate.application.NoopFeatureUsageReporter
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import io.github.jdreioe.wingmate.domain.obf.backgroundImage
import io.github.jdreioe.wingmate.domain.obf.withBackgroundImage
import io.github.jdreioe.wingmate.infrastructure.InMemoryBoardRepository
import io.github.jdreioe.wingmate.infrastructure.InMemoryBoardSetRepository
import io.github.jdreioe.wingmate.infrastructure.InMemorySettingsRepository
import io.github.jdreioe.wingmate.infrastructure.InMemoryVoiceRepository
import io.github.jdreioe.wingmate.infrastructure.NoopSpeechService
import io.github.jdreioe.wingmate.infrastructure.ObfParser
import io.github.jdreioe.wingmate.infrastructure.QuickCorePresetService
import io.github.jdreioe.wingmate.infrastructure.BoardImportService
import io.github.jdreioe.wingmate.platform.ArchiveReader
import io.github.jdreioe.wingmate.platform.FilePicker
import io.github.jdreioe.wingmate.platform.ShareService
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardsFacadeTest {
    @Test
    fun boardReturnBehaviorStaysOnCurrentForUnknownBehavior() = runBlocking {
        val facade = facade()

        val result = facade.boardReturnBehavior(
            behavior = "NotABehavior",
            currentBoardId = "current",
            boardStack = listOf("root", "current"),
            rootBoardId = "root",
        )

        assertEquals("current", result.boardId)
        assertEquals(listOf("root", "current"), result.boardStack)
    }

    @Test
    fun boardReturnBehaviorStartPageReturnsRoot() = runBlocking {
        val facade = facade()

        val result = facade.boardReturnBehavior(
            behavior = "StartPage",
            currentBoardId = "current",
            boardStack = listOf("root", "current"),
            rootBoardId = "root",
        )

        assertEquals("root", result.boardId)
    }

    @Test
    fun cellUpsertPersistsButtonAndClearPrunesUnreferencedImage() = runBlocking {
        val facade = facade()
        val boardSet = facade.createBoardSet("Test", rows = 2, columns = 2)
        val boardId = boardSet.rootBoardId

        val updated = facade.upsertBoardCellButton(
            boardId = boardId, row = 0, col = 0, label = "Hello", vocalization = "Hello",
            backgroundColor = null, borderColor = null, linkedBoardId = null,
            imageUrl = "https://example.com/hello.png", clearImage = false,
            actions = emptyList(), wordType = null,
        )
        assertNotNull(updated)
        assertEquals(listOf("Hello"), updated.buttons.map { it.label })
        assertEquals(listOf("hello.png"), updated.images.map { it.url?.substringAfterLast('/') })

        val cleared = facade.clearBoardCellButton(boardId, row = 0, col = 0)
        assertNotNull(cleared)
        assertTrue(cleared.buttons.isEmpty())
        assertTrue(cleared.images.isEmpty())
    }

    @Test
    fun clearingACellKeepsThePageBackgroundImage() = runBlocking {
        val boards = InMemoryBoardRepository()
        val facade = facade(boards = boards)
        val boardId = facade.createBoardSet("Test", rows = 1, columns = 1).rootBoardId
        facade.upsertBoardCellButton(
            boardId = boardId, row = 0, col = 0, label = "Hello", vocalization = null,
            backgroundColor = null, borderColor = null, linkedBoardId = null,
            imageUrl = "https://example.com/hello.png", clearImage = false,
            actions = emptyList(), wordType = null,
        )
        boards.saveBoard(boards.getBoard(boardId)!!.withBackgroundImage(ObfImage(id = "bg", path = "bg.png")))

        val cleared = facade.clearBoardCellButton(boardId, row = 0, col = 0)

        assertEquals(listOf("bg"), cleared.images.map { it.id })
        assertEquals("bg", cleared.backgroundImage?.imageId)
    }

    @Test
    fun cellUpsertKeepsOrClearsAnImageOnlyWhenRequested() = runBlocking {
        val facade = facade()
        val boardId = facade.createBoardSet("Test", rows = 1, columns = 1).rootBoardId

        facade.upsertBoardCellButton(
            boardId = boardId, row = 0, col = 0, label = "Hello", vocalization = null,
            backgroundColor = null, borderColor = "#123456", linkedBoardId = null,
            imageUrl = "https://example.com/hello.png", clearImage = false,
            actions = emptyList(), wordType = null,
        )
        val kept = facade.upsertBoardCellButton(
            boardId = boardId, row = 0, col = 0, label = "Hi", vocalization = null,
            backgroundColor = null, borderColor = "#654321", linkedBoardId = null,
            imageUrl = null, clearImage = false, actions = emptyList(), wordType = null,
        )
        assertEquals("https://example.com/hello.png", kept?.images?.single()?.url)
        assertEquals("#654321", kept?.buttons?.single()?.borderColor)

        val cleared = facade.upsertBoardCellButton(
            boardId = boardId, row = 0, col = 0, label = "Hi", vocalization = null,
            backgroundColor = null, borderColor = null, linkedBoardId = null,
            imageUrl = null, clearImage = true, actions = emptyList(), wordType = null,
        )
        assertNotNull(cleared)
        assertTrue(cleared.images.isEmpty())
        assertNull(cleared.buttons.single().imageId)
    }

    @Test
    fun listBoardCellsRendersUpsertedButton() = runBlocking {
        val facade = facade()
        val boardSet = facade.createBoardSet("Test", rows = 2, columns = 2)
        val boardId = boardSet.rootBoardId

        facade.upsertBoardCellButton(
            boardId = boardId, row = 1, col = 1, label = "Hi", vocalization = "Hi",
            backgroundColor = null, borderColor = null, linkedBoardId = null,
            imageUrl = null, clearImage = false, actions = emptyList(), wordType = null,
        )

        val cells = facade.listBoardCells(boardId)
        assertEquals(1, cells.size)
        assertEquals("Hi", cells.single().label)
        assertEquals(1, cells.single().row)
        assertEquals(1, cells.single().col)
    }

    @Test
    fun resolveBoardSettingsAppliesAppDefaultsWhenNoOverrides() = runBlocking {
        val settings = InMemorySettingsRepository()
        val facade = facade(settings = settings)
        val boardSet = facade.createBoardSet("Test", rows = 2, columns = 2)

        val resolved = facade.resolveBoardSettings(boardSet.rootBoardId)

        val persisted = settings.get()
        assertEquals(persisted.showLabels, resolved.showLabels)
        assertEquals(persisted.showSymbols, resolved.showSymbols)
        assertEquals(persisted.boardActivationBehavior.name, resolved.activationBehavior)
        assertEquals(persisted.boardReturnBehavior.name, resolved.returnBehavior)
    }

    @Test
    fun shareBoardSetAsObzReportsNotFoundShareFailureAndSuccess() = runBlocking {
        val share = RecordingShareService()
        val facade = facade(share = share)
        val boardSet = facade.createBoardSet("MySet", rows = 1, columns = 1)

        val notFound = facade.shareBoardSetAsObz("missing")
        assertEquals(BoardSetExportStatus.NotFound, notFound.status)
        assertNull(notFound.fileName)

        val unshared = facade.shareBoardSetAsObz(boardSet.id)
        assertEquals(BoardSetExportStatus.ShareFailure, unshared.status)
        assertTrue(unshared.isRetryable)

        share.shared = true
        val exported = facade.shareBoardSetAsObz(boardSet.id)
        assertEquals(BoardSetExportStatus.Success, exported.status)
        assertEquals("MySet.obz", exported.fileName)
    }

    @Test
    fun editsSeparateMissingTargetsFromRefusedChanges() = runBlocking {
        val facade = facade()
        val boardSet = facade.createBoardSet("Test", rows = 1, columns = 1)

        suspend fun kindOf(edit: suspend () -> Unit) = assertFailsWith<NativeFailure> { edit() }.kind

        assertEquals(NativeFailureKind.NotFound, kindOf { facade.renameBoardSet("missing", "Name") })
        assertEquals(NativeFailureKind.NotFound, kindOf { facade.renameBoard(boardSet.id, "missing", "Name") })
        assertEquals(NativeFailureKind.NotFound, kindOf { facade.importQuickCorePreset("not-a-preset", "Name") })
        assertEquals(NativeFailureKind.Validation, kindOf { facade.deleteBoard(boardSet.id, boardSet.rootBoardId) })
        assertEquals(NativeFailureKind.Validation, kindOf { facade.clearBoardCellButton(boardSet.rootBoardId, 3, 3) })

        facade.toggleBoardSetLocked(boardSet.id)
        val locked = assertFailsWith<NativeFailure> { facade.renameBoardSet(boardSet.id, "Renamed") }
        assertEquals(NativeFailureKind.Validation, locked.kind)
        assertEquals(false, locked.isRetryable)
    }

    private fun facade(
        settings: InMemorySettingsRepository = InMemorySettingsRepository(),
        share: ShareService = RecordingShareService(),
        boards: InMemoryBoardRepository = InMemoryBoardRepository(),
    ): BoardsFacade {
        val boardSets = InMemoryBoardSetRepository()
        val boardSetUseCase = BoardSetUseCase(boardSets, boards, NoopFeatureUsageReporter())
        val speechCache = BoardSetSpeechCacheUseCase(
            boardSetRepository = boardSets,
            boardRepository = boards,
            settingsRepository = settings,
            voiceRepository = InMemoryVoiceRepository(),
            speechService = NoopSpeechService(),
        )
        val quickCore = QuickCorePresetService(
            client = HttpClient(),
            importer = BoardImportService(
                obfParser = ObfParser(),
                boardRepository = boards,
                boardSetRepository = boardSets,
                filePicker = NoopFilePicker,
            ),
        )
        return BoardsFacade(
            boardSetUseCase = boardSetUseCase,
            boardRepository = boards,
            settingsUseCase = SettingsUseCase(settings),
            boardSetSpeechCache = speechCache,
            quickCorePresetService = quickCore,
            shareService = share,
        )
    }

    private class RecordingShareService(var shared: Boolean = false) : ShareService {
        override fun shareAudio(filePath: String): Boolean = false
        override fun shareText(text: String): Boolean = false
        override fun shareFile(fileName: String, content: ByteArray): Boolean = shared
    }

    private object NoopFilePicker : FilePicker {
        override suspend fun pickFile(title: String, extensions: List<String>): String? = null
        override suspend fun readFileAsText(path: String): String? = null
        override suspend fun openArchive(path: String): ArchiveReader? = null
    }
}
