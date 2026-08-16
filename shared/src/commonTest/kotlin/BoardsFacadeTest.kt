import io.github.jdreioe.wingmate.application.BoardSetSpeechCacheUseCase
import io.github.jdreioe.wingmate.application.BoardSetUseCase
import io.github.jdreioe.wingmate.application.BoardsFacade
import io.github.jdreioe.wingmate.application.NoopFeatureUsageReporter
import io.github.jdreioe.wingmate.application.SettingsUseCase
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
    fun joinAndBackspaceSentenceHonorSpellingMode() = runBlocking {
        val facade = facade()

        assertEquals("Hello world", facade.boardJoinSentenceText(listOf("Hello", "world"), spellingMode = false))
        assertEquals("Hello", facade.boardJoinSentenceText(listOf("H", "e", "l", "l", "o"), spellingMode = true))
        assertEquals(listOf("Hello"), facade.boardBackspaceSentence(listOf("Hello", "world"), spellingMode = false))
        assertEquals(listOf("H", "e", "l", "l"), facade.boardBackspaceSentence(listOf("H", "e", "l", "l", "o"), spellingMode = true))
        assertEquals(emptyList<String>(), facade.boardBackspaceSentence(listOf("A"), spellingMode = true))
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
    fun shareBoardSetAsObzMapsNotFoundCancelledAndSuccess() = runBlocking {
        val share = RecordingShareService()
        val facade = facade(share = share)
        val boardSet = facade.createBoardSet("MySet", rows = 1, columns = 1)

        val notFound = facade.shareBoardSetAsObz("missing")
        assertEquals(false, notFound.success)
        assertNull(notFound.fileName)
        assertTrue(notFound.message.contains("not found"))

        val cancelled = facade.shareBoardSetAsObz(boardSet.id)
        assertEquals(false, cancelled.success)
        assertEquals("MySet.obz", cancelled.fileName)
        assertEquals("Export cancelled", cancelled.message)

        share.shared = true
        val exported = facade.shareBoardSetAsObz(boardSet.id)
        assertEquals(true, exported.success)
        assertEquals("MySet.obz", exported.fileName)
        assertTrue(exported.message.contains("Exported"))
    }

    private fun facade(
        settings: InMemorySettingsRepository = InMemorySettingsRepository(),
        share: ShareService = RecordingShareService(),
    ): BoardsFacade {
        val boardSets = InMemoryBoardSetRepository()
        val boards = InMemoryBoardRepository()
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