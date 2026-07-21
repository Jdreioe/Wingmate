package io.github.jdreioe.wingmate.ui

import io.github.jdreioe.wingmate.application.BoardSetUseCase
import io.github.jdreioe.wingmate.application.NoopFeatureUsageReporter
import io.github.jdreioe.wingmate.infrastructure.ObfParser
import io.github.jdreioe.wingmate.infrastructure.InMemoryBoardRepository
import io.github.jdreioe.wingmate.infrastructure.InMemoryBoardSetRepository
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.InternalResourceApi
import wingmatekmp.composeapp.generated.resources.Res
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StarterBoardsTest {
    @Test
    fun englishCatalogStartsAtHomeAndContainsEightBoards() {
        val files = starterBoardFiles("en-GB")

        assertEquals(8, files?.size)
        assertEquals("starter_en_home", files?.first())
        assertEquals("starter_en_core24", files?.get(1))
        assertEquals("starter_en_universal_core", files?.get(2))
    }

    @Test
    fun danishCatalogStartsAtHomeAndContainsEightBoards() {
        val files = starterBoardFiles("DA-dk")

        assertEquals(8, files?.size)
        assertEquals("starter_da_hjem", files?.first())
        assertEquals("starter_da_core24", files?.get(1))
        assertEquals("starter_da_universal_core", files?.get(2))
    }

    @Test
    fun phraseScreenDoesNotOfferUnsupportedStarterLanguage() {
        assertNull(starterBoardFiles("de-DE"))
    }

    @Test
    fun eachLanguageProvidesFiveIndependentAgeGroupBoardSets() {
        assertEquals(
            listOf("Children", "School", "Teenagers", "Young Adults", "Adults"),
            starterBoardBundles("en-US").map { it.name }
        )
        assertEquals(
            listOf("Børn", "Skole", "Teenagere", "Unge voksne", "Voksne"),
            starterBoardBundles("da-DK").map { it.name }
        )
        assertTrue(starterBoardBundles("en").all { it.fileNames.size == 6 })
        assertTrue(starterBoardBundles("da").all { it.fileNames.size == 6 })
    }

    @OptIn(InternalResourceApi::class)
    @Test
    fun everyStarterResourceParsesAndLinksToAnotherStarterBoard() = runBlocking {
        val fileNames = (
            starterBoardFiles("en").orEmpty() +
                starterBoardFiles("da").orEmpty() +
                starterBoardBundles("en").flatMap { it.fileNames } +
                starterBoardBundles("da").flatMap { it.fileNames }
            ).distinct()
        val parser = ObfParser()
        val boards = fileNames.map { fileName ->
            val json = Res.readBytes("files/$fileName.obf").decodeToString()
            parser.parseBoard(json).getOrThrow()
        }
        val boardIds = boards.map { it.id }.toSet()

        assertEquals(fileNames.size, boards.size)
        assertEquals(boards.size, boardIds.size)
        boards.forEach { board ->
            val grid = requireNotNull(board.grid)
            assertEquals(grid.rows, grid.order.size, board.name)
            assertTrue(grid.order.all { it.size == grid.columns }, board.name)
            assertEquals(board.buttons.size, board.buttons.map { it.id }.toSet().size, board.name)
            assertEquals(board.buttons.size, board.images.size, "${board.name} symbol count")
            val imagesById = board.images.associateBy { it.id }
            board.buttons.forEach { button ->
                val image = imagesById[button.imageId]
                assertTrue(image != null, "${board.name}/${button.label} has no OpenSymbols image")
                assertTrue(image.url?.startsWith("https://") == true, "${board.name}/${button.label} image URL")
                assertTrue(!image.license?.type.isNullOrBlank(), "${board.name}/${button.label} image license")
                assertTrue(!image.license?.authorName.isNullOrBlank(), "${board.name}/${button.label} image author")
                assertTrue(!image.license?.sourceUrl.isNullOrBlank(), "${board.name}/${button.label} image source")
            }
            board.buttons.mapNotNull { it.loadBoard?.id }.forEach { targetId ->
                assertTrue(targetId in boardIds, "${board.name} links to missing board $targetId")
            }
            val homeNavigation = board.buttons.lastOrNull { button ->
                button.label in setOf("Home", "Hjem") && button.loadBoard != null
            }
            if (homeNavigation != null) {
                assertEquals(
                    homeNavigation.id,
                    grid.order.last().first(),
                    "${board.name} Home field must be bottom-left"
                )
            }
        }
    }

    @OptIn(InternalResourceApi::class)
    @Test
    fun danishStarterFieldsUseTheirEnglishSemanticSymbols() = runBlocking {
        val pairs = listOf(
            "starter_da_hjem" to "starter_en_home",
            "starter_da_smaa_boern" to "starter_en_young_children",
            "starter_da_skole" to "starter_en_school",
            "starter_da_teenagere" to "starter_en_teenagers",
            "starter_da_unge_voksne" to "starter_en_young_adults",
            "starter_da_voksne" to "starter_en_adults",
            "starter_da_core24" to "starter_en_core24",
            "starter_da_universal_core" to "starter_en_universal_core",
            "starter_da_behov" to "starter_en_needs"
        )
        val parser = ObfParser()

        pairs.forEach { (danishFile, englishFile) ->
            val danish = parser.parseBoard(
                Res.readBytes("files/$danishFile.obf").decodeToString()
            ).getOrThrow()
            val english = parser.parseBoard(
                Res.readBytes("files/$englishFile.obf").decodeToString()
            ).getOrThrow()
            assertEquals(english.buttons.size, danish.buttons.size, danishFile)
            val danishImages = danish.images.associateBy { it.id }
            val englishImages = english.images.associateBy { it.id }

            danish.buttons.zip(english.buttons).forEach { (danishButton, englishButton) ->
                assertEquals(
                    englishImages[englishButton.imageId]?.url,
                    danishImages[danishButton.imageId]?.url,
                    "$danishFile: ${danishButton.label} should use the ${englishButton.label} symbol"
                )
            }
        }
    }

    @OptIn(InternalResourceApi::class)
    @Test
    fun teenagerConversationSymbolsAreCuratedForMeaning() = runBlocking {
        val parser = ObfParser()
        val board = parser.parseBoard(
            Res.readBytes("files/starter_da_teenagere.obf").decodeToString()
        ).getOrThrow()
        val images = board.images.associateBy { it.id }
        val symbolKeys = board.buttons.associate { button ->
            button.label to images.getValue(requireNotNull(button.imageId)).symbol?.libraryKey
        }

        assertEquals("chat-6a28413d", symbolKeys["Hvad så?"])
        assertEquals("thumbs-up-f8d208b1", symbolKeys["Fedt"])
        assertEquals("leave-me-alone-4fab568e", symbolKeys["Giv mig plads"])
        assertEquals("we-don-t-agree-76c00a85", symbolKeys["Jeg er uenig"])
        assertEquals("text-mobile-message-to-77a8ee2c", symbolKeys["Skriv til mig"])
    }

    @OptIn(InternalResourceApi::class)
    @Test
    fun restoringStartersCreatesFiveSelfContainedEnglishBoardSets() = runBlocking {
        val boardRepository = InMemoryBoardRepository()
        val boardSetRepository = InMemoryBoardSetRepository()
        val useCase = BoardSetUseCase(
            boardSetRepository = boardSetRepository,
            boardRepository = boardRepository,
            featureUsageReporter = NoopFeatureUsageReporter()
        )

        restoreStarterBoards("en-US", ObfParser(), useCase)

        val boardSets = useCase.listBoardSets()
        assertEquals(5, boardSets.size)
        assertEquals(
            setOf("Children", "School", "Teenagers", "Young Adults", "Adults"),
            boardSets.map { it.name }.toSet()
        )
        boardSets.forEach { boardSet ->
            val graph = requireNotNull(useCase.loadBoardSetGraph(boardSet.id))
            assertEquals(6, graph.boards.size, boardSet.name)
            assertTrue(graph.boards.flatMap { it.buttons }.mapNotNull { it.loadBoard?.id }
                .all { it in graph.boardsById }, "${boardSet.name} has an external board link")
        }
    }

    @OptIn(InternalResourceApi::class)
    @Test
    fun restoringSelectedAgeGroupCreatesOnlyThatBoardSet() = runBlocking {
        val boardRepository = InMemoryBoardRepository()
        val boardSetRepository = InMemoryBoardSetRepository()
        val useCase = BoardSetUseCase(
            boardSetRepository = boardSetRepository,
            boardRepository = boardRepository,
            featureUsageReporter = NoopFeatureUsageReporter()
        )
        val selected = starterBoardBundles("da-DK").single { it.name == "Teenagere" }

        restoreStarterBoards("da-DK", ObfParser(), useCase, bundles = listOf(selected))

        val boardSet = useCase.listBoardSets().single()
        assertEquals("Teenagere", boardSet.name)
        assertEquals(6, requireNotNull(useCase.loadBoardSetGraph(boardSet.id)).boards.size)
    }
}
