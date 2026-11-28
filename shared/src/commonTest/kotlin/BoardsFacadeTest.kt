import io.github.jdreioe.wingmate.application.BoardSetExportStatus
import io.github.jdreioe.wingmate.application.BoardSetSpeechCacheUseCase
import io.github.jdreioe.wingmate.application.BoardSetUseCase
import io.github.jdreioe.wingmate.application.BoardsFacade
import io.github.jdreioe.wingmate.application.NativeFailure
import io.github.jdreioe.wingmate.application.NativeFailureKind
import io.github.jdreioe.wingmate.application.NoopFeatureUsageReporter
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.domain.BoardRepository
import io.github.jdreioe.wingmate.domain.obf.BoardSettingsOverrides
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import io.github.jdreioe.wingmate.domain.obf.withPageSettingsOverrides
import io.github.jdreioe.wingmate.domain.obf.backgroundImage
import io.github.jdreioe.wingmate.domain.obf.withBackgroundImage
import io.github.jdreioe.wingmate.infrastructure.InMemoryBoardRepository
import io.github.jdreioe.wingmate.infrastructure.InMemoryBoardSetRepository
import io.github.jdreioe.wingmate.infrastructure.InMemorySettingsRepository
import io.github.jdreioe.wingmate.infrastructure.InMemoryVoiceRepository
import io.github.jdreioe.wingmate.infrastructure.NoopSpeechService
import io.github.jdreioe.wingmate.platform.ShareService
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardsFacadeTest {
    @Test
    fun backgroundEditingPreservesVocabularyAndRejectsLockedScreens() = runBlocking {
        val facade = facade()
        val set = facade.createBoardSet("Test", rows = 1, columns = 1, rootBoardName = "Home")
        facade.upsertBoardCellButton(
            boardId = set.rootBoardId, row = 0, col = 0, label = "Hello", vocalization = null,
            backgroundColor = null, borderColor = null, linkedBoardId = null,
            imageUrl = "file:///symbol.png", clearImage = false, actions = emptyList(), wordType = null,
        )
        val page = facade.setBoardBackgroundImage(set.id, set.rootBoardId, "file:///background.png", "Fit")
        assertEquals("file:///background.png", facade.backgroundImageUrl(page))
        assertEquals("Fit", facade.backgroundImageFit(page))
        assertEquals(2, page.images.size)
        val cleared = facade.setBoardBackgroundImage(set.id, set.rootBoardId, null, "Fill")
        assertEquals(listOf("file:///symbol.png"), cleared.images.map { it.url })
        assertEquals("Hello", facade.printedPages(set.id, "en").single().cells.single().label)
        facade.toggleBoardSetLocked(set.id)
        val error = assertFailsWith<NativeFailure> {
            facade.setBoardBackgroundImage(set.id, set.rootBoardId, "file:///other.png", "Fill")
        }
        assertEquals(NativeFailureKind.Validation, error.kind)
        assertNull(facade.backgroundImageUrl(facade.getBoard(set.rootBoardId)!!))
    }

    @Test
    fun speechImportExposesValidationFailuresAndResolvesItsPlaybackSettings() = runBlocking {
        val facade = facade()
        val failure = assertFailsWith<NativeFailure> {
            facade.importSpeech("My speech", "# Heading only", 4, "Previous", "Next")
        }
        assertEquals(NativeFailureKind.Validation, failure.kind)
        assertTrue(facade.listBoardSets().isEmpty())
        val sizeFailure = assertFailsWith<NativeFailure> { facade.previewSpeech("Speech", "Hello", 0) }
        assertEquals(NativeFailureKind.Validation, sizeFailure.kind)
        val created = facade.importSpeech("My speech", "Hello.\n\nThank you.", 1, "Previous", "Next")
        val settings = facade.resolveBoardSettings(created.rootBoardId)
        assertEquals("Immediate", settings.speechPolicy)
        assertEquals("SpeakOnly", settings.activationBehavior)
        assertEquals(false, settings.showMessageBar)
    }

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
    fun boardReturnBehaviorRespectsStayOnCurrentPageOverride() = runBlocking {
        val facade = facade()

        assertTrue(
            facade.canStayOnCurrentPage(
                behavior = "Previous",
                currentBoardId = "food",
                boardStack = listOf("root"),
                rootBoardId = "root",
            )
        )
        assertFalse(
            facade.canStayOnCurrentPage(
                behavior = "Stay",
                currentBoardId = "food",
                boardStack = listOf("root"),
                rootBoardId = "root",
            )
        )

        val result = facade.boardReturnBehavior(
            behavior = "Previous",
            currentBoardId = "food",
            boardStack = listOf("root"),
            rootBoardId = "root",
            stayOnCurrentPage = true,
        )
        assertEquals("food", result.boardId)
        assertEquals(listOf("root"), result.boardStack)
    }

    @Test
    fun cellUpsertPersistsButtonAndClearPrunesUnreferencedImage() = runBlocking {
        val facade = facade()
        val boardSet = facade.createBoardSet("Test", rows = 2, columns = 2, rootBoardName = "Home")
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
    fun bulkMaskingUpdatesVisibilityAndRejectsLockedScreens() = runBlocking {
        val facade = facade()
        val set = facade.createBoardSet("Masking", rows = 2, columns = 2, rootBoardName = "Home")
        val page2 = facade.createBoard(set.id, "Food", rows = 1, columns = 2)
        val homeId = set.rootBoardId
        val b1 = facade.upsertBoardCellButton(
            boardId = homeId, row = 0, col = 0, label = "More", vocalization = null,
            backgroundColor = null, borderColor = null, linkedBoardId = null,
            imageUrl = null, clearImage = false, actions = emptyList(), wordType = null,
        ).buttons.single()
        facade.upsertBoardCellButton(
            boardId = homeId, row = 0, col = 1, label = "Stop", vocalization = null,
            backgroundColor = null, borderColor = null, linkedBoardId = null,
            imageUrl = null, clearImage = false, actions = emptyList(), wordType = null,
        )
        facade.upsertBoardCellButton(
            boardId = page2.id, row = 0, col = 0, label = "Apple", vocalization = null,
            backgroundColor = null, borderColor = null, linkedBoardId = null,
            imageUrl = null, clearImage = false, actions = emptyList(), wordType = null,
        )

        val toggled = facade.toggleButtonHidden(set.id, homeId, b1.id)
        assertTrue(toggled.buttons.first { it.id == b1.id }.hidden)
        assertEquals(2, toggled.grid?.rows)
        assertEquals(2, toggled.grid?.columns)

        val pageHidden = facade.setPageButtonsHidden(set.id, homeId, hidden = true)
        assertTrue(pageHidden.buttons.all { it.hidden })
        assertFalse(facade.getBoard(page2.id)!!.buttons.single().hidden)

        assertTrue(facade.setScreenButtonsHidden(set.id, hidden = true))
        assertTrue(facade.getBoard(page2.id)!!.buttons.single().hidden)

        assertTrue(facade.setScreenButtonsHidden(set.id, hidden = false))
        assertTrue(facade.getBoard(homeId)!!.buttons.none { it.hidden })
        assertTrue(facade.getBoard(page2.id)!!.buttons.none { it.hidden })

        facade.toggleBoardSetLocked(set.id)
        val toggleFailure = assertFailsWith<NativeFailure> {
            facade.toggleButtonHidden(set.id, homeId, b1.id)
        }
        assertEquals(NativeFailureKind.Validation, toggleFailure.kind)
        val pageFailure = assertFailsWith<NativeFailure> {
            facade.setPageButtonsHidden(set.id, homeId, hidden = true)
        }
        assertEquals(NativeFailureKind.Validation, pageFailure.kind)
        val screenFailure = assertFailsWith<NativeFailure> {
            facade.setScreenButtonsHidden(set.id, hidden = true)
        }
        assertEquals(NativeFailureKind.Validation, screenFailure.kind)
    }

    @Test
    fun clearingACellKeepsThePageBackgroundImage() = runBlocking {
        val boards = InMemoryBoardRepository()
        val facade = facade(boards = boards)
        val boardId = facade.createBoardSet("Test", rows = 1, columns = 1, rootBoardName = "Home").rootBoardId
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
        val boardId = facade.createBoardSet("Test", rows = 1, columns = 1, rootBoardName = "Home").rootBoardId

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
    fun cellUpsertStoresAppearanceAndKeepsItWhenALaterEditOmitsIt() = runBlocking {
        val facade = facade()
        val boardId = facade.createBoardSet("Test", rows = 1, columns = 1, rootBoardName = "Home").rootBoardId

        facade.upsertBoardCellButton(
            boardId = boardId, row = 0, col = 0, label = "Hello", vocalization = null,
            backgroundColor = null, borderColor = null, linkedBoardId = null,
            imageUrl = null, clearImage = false, actions = listOf(":speak", ":home"), wordType = "noun",
            applyAppearance = true, hidden = true, shape = "pill", language = "da-DK",
            mathMode = true, recordingPath = "/recordings/hello.m4a",
        )
        val stored = facade.listBoardCells(boardId).single()
        assertEquals(true, stored.hidden)
        assertEquals("pill", stored.shape)
        assertEquals("da-DK", stored.language)
        assertEquals(true, stored.mathMode)
        assertEquals("/recordings/hello.m4a", stored.soundPath)
        assertEquals(listOf(":speak", ":home"), stored.actions)
        assertEquals("noun", stored.wordType)

        val kept = facade.upsertBoardCellButton(
            boardId = boardId, row = 0, col = 0, label = "Hi", vocalization = null,
            backgroundColor = null, borderColor = null, linkedBoardId = null,
            imageUrl = null, clearImage = false, actions = emptyList(), wordType = null,
        )
        assertEquals(true, kept.buttons.single().hidden)
        assertEquals("da-DK", kept.buttons.single().locale)
        assertEquals("/recordings/hello.m4a", kept.sounds.single().path)

        facade.upsertBoardCellButton(
            boardId = boardId, row = 0, col = 0, label = "Hi", vocalization = null,
            backgroundColor = null, borderColor = null, linkedBoardId = null,
            imageUrl = null, clearImage = false, actions = emptyList(), wordType = null,
            applyAppearance = true, clearRecording = true,
        )
        val cleared = facade.getBoard(boardId)!!
        assertEquals(false, cleared.buttons.single().hidden)
        assertTrue(cleared.sounds.isEmpty())
        assertNull(cleared.buttons.single().locale)
    }

    @Test
    fun messageBarButtonsAreChosenPerScreenAndSurviveTheSettingsForm() = runBlocking {
        val facade = facade()
        val set = facade.createBoardSet("Test", rows = 1, columns = 1, rootBoardName = "Home")
        assertEquals(listOf("Speak"), facade.resolveBoardSettings(set.rootBoardId).messageBarActions)

        facade.updateMessageBarActions(set.id, listOf("Hold", "Speak", "Clear"))
        facade.updateBoardSettings(
            boardSetId = set.id, boardId = set.rootBoardId, forPage = false,
            showLabels = "inherit", showSymbols = "inherit", labelAtTop = "inherit",
            showMessageBar = "inherit", showSpeakButton = "inherit", messageBarEditable = "inherit",
            activationBehavior = "inherit", returnBehavior = "inherit", cacheSpeech = "inherit",
        )
        assertEquals(listOf("Hold", "Speak", "Clear"), facade.resolveBoardSettings(set.rootBoardId).messageBarActions)

        val unknown = assertFailsWith<NativeFailure> { facade.updateMessageBarActions(set.id, listOf("Dance")) }
        assertEquals(NativeFailureKind.Validation, unknown.kind)
    }

    @Test
    fun screenAndPageSettingsInheritUntilOverriddenAndRejectALockedScreen() = runBlocking {
        val facade = facade()
        val set = facade.createBoardSet("Test", rows = 1, columns = 1, rootBoardName = "Home")
        val inherit = facade.boardSettingsDraft(set.id, set.rootBoardId, forPage = false)
        assertEquals("inherit", inherit.showMessageBar)
        assertEquals("inherit", inherit.activationBehavior)

        facade.updateBoardSettings(
            boardSetId = set.id, boardId = set.rootBoardId, forPage = false,
            showLabels = "inherit", showSymbols = "inherit", labelAtTop = "inherit",
            showMessageBar = "off", showSpeakButton = "inherit", messageBarEditable = "inherit",
            activationBehavior = "SpeakOnly", returnBehavior = "Previous", cacheSpeech = "on",
            cellGapDp = 12, outerMarginDp = 24,
        )
        val screen = facade.resolveBoardSettings(set.rootBoardId)
        assertEquals(false, screen.showMessageBar)
        assertEquals("SpeakOnly", screen.activationBehavior)
        assertEquals("Previous", screen.returnBehavior)
        assertEquals(12, screen.cellGapDp)
        assertEquals(24, screen.outerMarginDp)

        val screenDraft = facade.boardSettingsDraft(set.id, set.rootBoardId, forPage = false)
        assertEquals(12, screenDraft.cellGapDp)
        assertEquals(24, screenDraft.outerMarginDp)

        facade.updateBoardSettings(
            boardSetId = set.id, boardId = set.rootBoardId, forPage = true,
            showLabels = "inherit", showSymbols = "off", labelAtTop = "on",
            showMessageBar = "on", showSpeakButton = "inherit", messageBarEditable = "off",
            activationBehavior = "inherit", returnBehavior = "inherit", cacheSpeech = "inherit",
            cellGapDp = 4, outerMarginDp = null,
        )
        val pageResolved = facade.resolveBoardSettings(set.rootBoardId)
        assertEquals(4, pageResolved.cellGapDp)
        assertEquals(24, pageResolved.outerMarginDp)

        facade.updateBoardSettings(
            boardSetId = set.id, boardId = set.rootBoardId, forPage = true,
            showLabels = "inherit", showSymbols = "off", labelAtTop = "on",
            showMessageBar = "on", showSpeakButton = "inherit", messageBarEditable = "off",
            activationBehavior = "inherit", returnBehavior = "inherit", cacheSpeech = "inherit",
        )
        val page = facade.resolveBoardSettings(set.rootBoardId)
        assertEquals(false, page.showSymbols)
        assertEquals(true, page.labelAtTop)
        assertEquals(true, page.showMessageBar)
        assertEquals("SpeakOnly", page.activationBehavior)

        facade.toggleBoardSetLocked(set.id)
        val locked = assertFailsWith<NativeFailure> {
            facade.updateBoardSettings(
                boardSetId = set.id, boardId = set.rootBoardId, forPage = false,
                showLabels = "on", showSymbols = "on", labelAtTop = "off",
                showMessageBar = "on", showSpeakButton = "on", messageBarEditable = "on",
                activationBehavior = "inherit", returnBehavior = "Stay", cacheSpeech = "off",
            )
        }
        assertEquals(NativeFailureKind.Validation, locked.kind)
    }

    @Test
    fun listBoardCellsRendersUpsertedButton() = runBlocking {
        val facade = facade()
        val boardSet = facade.createBoardSet("Test", rows = 2, columns = 2, rootBoardName = "Home")
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
    fun boardSnapshotDerivesSettingsButtonsAndSpansFromOnePageRead() = runBlocking {
        val boards = RevisingBoardRepository(InMemoryBoardRepository())
        val facade = facade(boards = boards)
        val boardSet = facade.createBoardSet("Test", rows = 2, columns = 2, rootBoardName = "Home")
        val boardId = boardSet.rootBoardId
        facade.upsertBoardCellButton(
            boardId = boardId, row = 0, col = 1, label = "Hi", vocalization = "Hi",
            backgroundColor = null, borderColor = null, linkedBoardId = null,
            imageUrl = null, clearImage = false, actions = emptyList(), wordType = null,
        )
        val stable = assertNotNull(facade.loadBoardSnapshot(boardId))
        assertEquals("Hi", stable.cells.single().label)
        assertEquals(listOf(stable.cells.single().buttonId), stable.fieldItems.mapNotNull { it.buttonId })

        // Every read now returns a new Page revision. A snapshot built from more than one
        // read would mix revisions between its board, settings, and Buttons.
        boards.revising = true
        val snapshot = assertNotNull(facade.loadBoardSnapshot(boardId))
        val revision = snapshot.board.buttons.single().label
        assertEquals(revision, snapshot.cells.single().label)
        assertEquals(revision, "rev${snapshot.settings.cellGapDp}")
        boards.revising = false

        assertNull(facade.loadBoardSnapshot("missing-page"))
    }

    @Test
    fun moveBoardFieldSwapsButtonsAndRefusesASpanThatDoesNotFit() = runBlocking {
        val facade = facade()
        val boardId = facade.createBoardSet("Test", rows = 2, columns = 2, rootBoardName = "Home").rootBoardId
        facade.upsertBoardCellButton(
            boardId = boardId, row = 0, col = 0, label = "A", vocalization = null,
            backgroundColor = null, borderColor = null, linkedBoardId = null,
            imageUrl = null, clearImage = false, actions = emptyList(), wordType = null,
        )
        facade.upsertBoardCellButton(
            boardId = boardId, row = 0, col = 1, label = "B", vocalization = null,
            backgroundColor = null, borderColor = null, linkedBoardId = null,
            imageUrl = null, clearImage = false, actions = emptyList(), wordType = null,
        )

        assertTrue(facade.moveBoardField(boardId, fromRow = 0, fromCol = 0, toRow = 0, toCol = 1))
        val swapped = facade.listBoardCells(boardId).associate { (it.row to it.col) to it.label }
        assertEquals("B", swapped[0 to 0])
        assertEquals("A", swapped[0 to 1])

        facade.resizeBoardField(boardId, row = 0, col = 0, rowSpan = 2, columnSpan = 1)
        assertEquals(false, facade.moveBoardField(boardId, fromRow = 0, fromCol = 0, toRow = 1, toCol = 1))
        val unchanged = facade.listBoardCells(boardId).associate { (it.row to it.col) to it.label }
        assertEquals("B", unchanged[0 to 0])
        assertEquals("B", unchanged[1 to 0])
        assertEquals("A", unchanged[0 to 1])
    }

    @Test
    fun resolveBoardSettingsAppliesAppDefaultsWhenNoOverrides() = runBlocking {
        val settings = InMemorySettingsRepository()
        val facade = facade(settings = settings)
        val boardSet = facade.createBoardSet("Test", rows = 2, columns = 2, rootBoardName = "Home")

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
        val boardSet = facade.createBoardSet("MySet", rows = 1, columns = 1, rootBoardName = "Home")

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
    fun shareBoardSetAsGridsetReportsNotFoundShareFailureAndSuccess() = runBlocking {
        val share = RecordingShareService()
        val facade = facade(share = share)
        val boardSet = facade.createBoardSet("My/Set", rows = 1, columns = 1, rootBoardName = "Home")

        val notFound = facade.shareBoardSetAsGridset("missing")
        assertEquals(BoardSetExportStatus.NotFound, notFound.status)
        assertNull(notFound.fileName)

        val unshared = facade.shareBoardSetAsGridset(boardSet.id)
        assertEquals(BoardSetExportStatus.ShareFailure, unshared.status)
        assertTrue(unshared.isRetryable)

        share.shared = true
        val exported = facade.shareBoardSetAsGridset(boardSet.id)
        assertEquals(BoardSetExportStatus.Success, exported.status)
        assertEquals("My_Set.gridset", exported.fileName)
    }

    @Test
    fun editsSeparateMissingTargetsFromRefusedChanges() = runBlocking {
        val facade = facade()
        val boardSet = facade.createBoardSet("Test", rows = 1, columns = 1, rootBoardName = "Home")

        suspend fun kindOf(edit: suspend () -> Unit) = assertFailsWith<NativeFailure> { edit() }.kind

        assertEquals(NativeFailureKind.NotFound, kindOf { facade.renameBoardSet("missing", "Name") })
        assertEquals(NativeFailureKind.NotFound, kindOf { facade.renameBoard(boardSet.id, "missing", "Name") })
        assertEquals(NativeFailureKind.Validation, kindOf { facade.deleteBoard(boardSet.id, boardSet.rootBoardId) })
        assertEquals(NativeFailureKind.Validation, kindOf { facade.clearBoardCellButton(boardSet.rootBoardId, 3, 3) })

        facade.toggleBoardSetLocked(boardSet.id)
        val locked = assertFailsWith<NativeFailure> { facade.renameBoardSet(boardSet.id, "Renamed") }
        assertEquals(NativeFailureKind.Validation, locked.kind)
        assertEquals(false, locked.isRetryable)
    }

    @Test
    fun wordFinderSearchesMultiPageScreenHonorsHiddenVisibilityAndFallsBackToRoot() = runBlocking {
        val facade = facade()
        val set = facade.createBoardSet("Core", rows = 2, columns = 2, rootBoardName = "Home")
        val food = facade.createBoard(set.id, name = "Food", rows = 2, columns = 2)
        val drinks = facade.createBoard(set.id, name = "Drinks", rows = 2, columns = 2)

        facade.upsertBoardCellButton(
            boardId = set.rootBoardId, row = 0, col = 0, label = "Food", vocalization = null,
            backgroundColor = null, borderColor = null, linkedBoardId = food.id,
            imageUrl = null, clearImage = false, actions = emptyList(), wordType = null,
        )
        facade.upsertBoardCellButton(
            boardId = set.rootBoardId, row = 0, col = 1, label = "Drinks", vocalization = null,
            backgroundColor = null, borderColor = null, linkedBoardId = drinks.id,
            imageUrl = null, clearImage = false, actions = emptyList(), wordType = null,
        )
        facade.upsertBoardCellButton(
            boardId = food.id, row = 0, col = 0, label = "Apple", vocalization = null,
            backgroundColor = null, borderColor = null, linkedBoardId = null,
            imageUrl = null, clearImage = false, actions = emptyList(), wordType = null,
        )
        facade.upsertBoardCellButton(
            boardId = food.id, row = 0, col = 1, label = "Apple pie", vocalization = null,
            backgroundColor = null, borderColor = null, linkedBoardId = null,
            imageUrl = null, clearImage = false, actions = emptyList(), wordType = null,
            applyAppearance = true, hidden = true,
        )

        val fromHomeRun = facade.findWords(set.id, query = "apple", currentBoardId = set.rootBoardId, includeHidden = false)
        assertEquals(1, fromHomeRun.size)
        assertEquals("Home → Food → Apple", fromHomeRun.single().formattedPath)
        assertFalse(fromHomeRun.single().isHidden)
        assertFalse(fromHomeRun.single().startsFromRoot)

        val fromDrinksFallback = facade.findWords(set.id, query = "apple", currentBoardId = drinks.id, includeHidden = false)
        assertEquals(1, fromDrinksFallback.size)
        assertEquals("Home → Food → Apple", fromDrinksFallback.single().formattedPath)
        assertTrue(fromDrinksFallback.single().startsFromRoot)

        val fromHomeEdit = facade.findWords(set.id, query = "apple", currentBoardId = set.rootBoardId, includeHidden = true)
        assertEquals(listOf("Apple", "Apple pie"), fromHomeEdit.map { it.label })
        assertFalse(fromHomeEdit[0].isHidden)
        assertTrue(fromHomeEdit[1].isHidden)

        assertTrue(facade.findWords("missing-screen", query = "apple", currentBoardId = null).isEmpty())
    }

    private fun facade(
        settings: InMemorySettingsRepository = InMemorySettingsRepository(),
        share: ShareService = RecordingShareService(),
        boards: BoardRepository = InMemoryBoardRepository(),
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
        return BoardsFacade(
            boardSetUseCase = boardSetUseCase,
            boardRepository = boards,
            settingsUseCase = SettingsUseCase(settings),
            boardSetSpeechCache = speechCache,
            shareService = share,
        )
    }

    /** Returns a new Page revision (Button labels and Page gap) on every read once [revising]. */
    private class RevisingBoardRepository(private val inner: BoardRepository) : BoardRepository by inner {
        var revising = false
        private var reads = 0

        override suspend fun getBoard(id: String): ObfBoard? {
            val board = inner.getBoard(id) ?: return null
            if (!revising) return board
            reads += 1
            return board
                .copy(buttons = board.buttons.map { it.copy(label = "rev$reads") })
                .withPageSettingsOverrides(BoardSettingsOverrides(cellGapDp = reads))
        }
    }

    private class RecordingShareService(var shared: Boolean = false) : ShareService {
        override fun shareAudio(filePath: String): Boolean = false
        override fun shareText(text: String): Boolean = false
        override fun shareFile(fileName: String, content: ByteArray): Boolean = shared
    }
}
