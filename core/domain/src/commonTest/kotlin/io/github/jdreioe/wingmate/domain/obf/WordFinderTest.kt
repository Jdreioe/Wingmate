package io.github.jdreioe.wingmate.domain.obf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WordFinderTest {

    @Test
    fun findsShortestMultiHopPathAcrossLinkedPages() {
        val home = board(
            id = "home",
            name = "Home",
            buttons = listOf(
                ObfButton(id = "to-food", label = "Food", loadBoard = ObfLoadBoard(id = "food", name = "Food")),
                ObfButton(id = "to-more", label = "More", loadBoard = ObfLoadBoard(id = "more", name = "More")),
            ),
        )
        val more = board(
            id = "more",
            name = "More",
            buttons = listOf(
                ObfButton(id = "more-to-food", label = "Food", loadBoard = ObfLoadBoard(id = "food", name = "Food")),
            ),
        )
        val food = board(
            id = "food",
            name = "Food",
            buttons = listOf(
                ObfButton(id = "to-fruit", label = "Fruit", loadBoard = ObfLoadBoard(id = "fruit", name = "Fruit")),
                ObfButton(id = "apple-direct", label = "Apple"),
            ),
        )
        val fruit = board(
            id = "fruit",
            name = "Fruit",
            buttons = listOf(
                ObfButton(id = "apple-deep", label = "Apple"),
            ),
        )
        val graph = graphOf(rootId = "home", home, more, food, fruit)

        val matches = graph.findWords(query = "apple", currentBoardId = "home")

        assertEquals(2, matches.size)
        assertEquals("Home → Food → Apple", matches[0].formattedPath())
        assertEquals("apple-direct", matches[0].buttonId)
        assertEquals(1, matches[0].hopCount)
        assertFalse(matches[0].startsFromRoot)

        assertEquals("Home → Food → Fruit → Apple", matches[1].formattedPath())
        assertEquals("apple-deep", matches[1].buttonId)
        assertEquals(2, matches[1].hopCount)
    }

    @Test
    fun prefersCurrentPagePathAndFallsBackToRootPageWhenUnreachable() {
        val home = board(
            id = "home",
            name = "Home",
            buttons = listOf(
                ObfButton(id = "to-food", label = "Food", loadBoard = ObfLoadBoard(id = "food")),
                ObfButton(id = "to-drinks", label = "Drinks", loadBoard = ObfLoadBoard(id = "drinks")),
            ),
        )
        val food = board(
            id = "food",
            name = "Food",
            buttons = listOf(
                ObfButton(id = "apple", label = "Apple"),
            ),
        )
        val drinks = board(
            id = "drinks",
            name = "Drinks",
            buttons = listOf(
                ObfButton(id = "water", label = "Water"),
            ),
        )
        val graph = graphOf(rootId = "home", home, food, drinks)

        // Already on Drinks: Water is direct from Drinks; Apple is unreachable from Drinks so falls back to Home.
        val water = graph.findWords(query = "water", currentBoardId = "drinks").single()
        assertEquals("Drinks → Water", water.formattedPath())
        assertFalse(water.startsFromRoot)
        assertEquals("drinks", water.startingBoardId)

        val apple = graph.findWords(query = "apple", currentBoardId = "drinks").single()
        assertEquals("Home → Food → Apple", apple.formattedPath())
        assertTrue(apple.startsFromRoot)
        assertEquals("home", apple.startingBoardId)
    }

    @Test
    fun excludesHiddenButtonsAndNeverFollowsHiddenLinksUnlessIncluded() {
        val home = board(
            id = "home",
            name = "Home",
            buttons = listOf(
                ObfButton(id = "hidden-apple", label = "Apple", hidden = true),
                ObfButton(
                    id = "hidden-folder",
                    label = "Secret",
                    hidden = true,
                    loadBoard = ObfLoadBoard(id = "secret"),
                ),
                ObfButton(id = "visible-apple-pie", label = "Apple pie"),
            ),
        )
        val secret = board(
            id = "secret",
            name = "Secret",
            buttons = listOf(
                ObfButton(id = "secret-apple", label = "Apple"),
            ),
        )
        val graph = graphOf(rootId = "home", home, secret)

        val runMatches = graph.findWords(query = "apple", currentBoardId = "home", includeHidden = false)
        assertEquals(listOf("visible-apple-pie"), runMatches.map { it.buttonId })

        val editMatches = graph.findWords(query = "apple", currentBoardId = "home", includeHidden = true)
        assertEquals(listOf("hidden-apple", "secret-apple", "visible-apple-pie"), editMatches.map { it.buttonId })
        assertTrue(editMatches[0].isHidden)
        assertFalse(editMatches[1].isHidden)
        assertTrue(editMatches[1].hasHiddenStep)
        assertEquals("Home → Secret → Apple", editMatches[1].formattedPath())
        assertTrue(editMatches[1].steps.first().isHidden)
        assertFalse(editMatches[2].isHidden)
    }

    @Test
    fun prefersVisibleLinkWhenPageHasBothHiddenAndVisibleLinks() {
        val home = board(
            id = "home",
            name = "Home",
            buttons = listOf(
                ObfButton(id = "hidden-link", label = "Hidden Food", hidden = true, loadBoard = ObfLoadBoard(id = "food")),
                ObfButton(id = "visible-link", label = "Food", hidden = false, loadBoard = ObfLoadBoard(id = "food")),
            ),
        )
        val food = board(
            id = "food",
            name = "Food",
            buttons = listOf(ObfButton(id = "apple", label = "Apple")),
        )
        val graph = graphOf(rootId = "home", home, food)

        val match = graph.findWords(query = "apple", currentBoardId = "home", includeHidden = true).single()
        assertEquals("Home → Food → Apple", match.formattedPath())
        assertFalse(match.hasHiddenStep)
    }

    @Test
    fun handlesCyclesSelfLinksAndMissingLinkedBoardsSafely() {
        val a = board(
            id = "a",
            name = "Page A",
            buttons = listOf(
                ObfButton(id = "self", label = "Self", loadBoard = ObfLoadBoard(id = "a")),
                ObfButton(id = "missing", label = "Broken", loadBoard = ObfLoadBoard(id = "does-not-exist")),
                ObfButton(id = "to-b", label = "Go B", loadBoard = ObfLoadBoard(id = "b")),
            ),
        )
        val b = board(
            id = "b",
            name = "Page B",
            buttons = listOf(
                ObfButton(id = "to-a", label = "Back A", loadBoard = ObfLoadBoard(id = "a")),
                ObfButton(id = "banana", label = "Banana"),
            ),
        )
        val graph = graphOf(rootId = "a", a, b)

        val banana = graph.findWords(query = "banana", currentBoardId = "a").single()
        assertEquals("Page A → Go B → Banana", banana.formattedPath())

        // Missing query returns empty cleanly without infinite loops.
        assertTrue(graph.findWords(query = "nonexistent", currentBoardId = "a").isEmpty())
        assertTrue(graph.findWords(query = "   ", currentBoardId = "a").isEmpty())
    }

    @Test
    fun matchesLocalizedLabelsAndVocalizationsCaseInsensitivelyAndRanksExactPrefixSubstring() {
        val home = board(
            id = "home",
            name = "home_title",
            strings = mapOf(
                "da" to mapOf(
                    "home_title" to "Hjem",
                    "apple_key" to "Æble",
                    "apple_juice_key" to "Æblejuice",
                    "red_apple_key" to "Rødt æble",
                    "pineapple_key" to "Ananasæble",
                    "greeting_vocalization" to "Hej med æble",
                ),
            ),
            buttons = listOf(
                ObfButton(id = "sub", label = "pineapple_key"),
                ObfButton(id = "word-prefix", label = "red_apple_key"),
                ObfButton(id = "prefix", label = "apple_juice_key"),
                ObfButton(id = "exact", label = "apple_key"),
                ObfButton(id = "voc", label = "Hej", vocalization = "greeting_vocalization"),
            ),
        )
        val graph = graphOf(rootId = "home", home)

        val matches = graph.findWords(query = "  ÆBLE ", currentBoardId = "home", locale = "da-DK")

        assertEquals(listOf("exact", "prefix", "voc", "word-prefix", "sub"), matches.map { it.buttonId })
        assertEquals("Hjem → Æble", matches[0].formattedPath())
        assertEquals("Hej med æble", matches[2].vocalization)
    }

    @Test
    fun ignoresButtonsNotPlacedInGridAndDeduplicatesMergedSpanningButtons() {
        val home = ObfBoard(
            format = "open-board-0.1",
            id = "home",
            name = "Home",
            buttons = listOf(
                ObfButton(id = "merged", label = "Apple"),
                ObfButton(id = "unplaced", label = "Apple orphan"),
            ),
            grid = ObfGrid(
                rows = 2,
                columns = 2,
                order = listOf(
                    listOf("merged", "merged"),
                    listOf(null, null),
                ),
            ),
        )
        val graph = graphOf(rootId = "home", home)

        val matches = graph.findWords(query = "apple", currentBoardId = "home")
        assertEquals(listOf("merged"), matches.map { it.buttonId })
    }

    @Test
    fun guidanceAdvancesAcrossStepsSyncsWithNavigationAndCompletesOnTarget() {
        val match = WordFinderMatch(
            targetBoardId = "food",
            targetBoardName = "Food",
            buttonId = "btn-apple",
            label = "Apple",
            steps = listOf(
                WordFinderStep("home", "Home", "btn-food", "Food", targetBoardId = "food"),
                WordFinderStep("food", "Food", "btn-apple", "Apple", targetBoardId = null),
            ),
        )

        val initial = WordFinderGuidance(match)
        assertEquals("btn-food", initial.highlightedButtonIdFor("home"))
        assertNull(initial.highlightedButtonIdFor("food"))
        assertFalse(initial.isOnFinalStep)

        // Unrelated button activation leaves guidance intact.
        assertEquals(initial, initial.onButtonActivated("home", "other"))

        // Tapping the guided link advances to step 2.
        val step2 = initial.onButtonActivated("home", "btn-food")!!
        assertEquals(1, step2.currentStepIndex)
        assertEquals("btn-apple", step2.highlightedButtonIdFor("food"))
        assertTrue(step2.isOnFinalStep)

        // Stepping back to Home re-syncs to step 1.
        val backToStep1 = step2.onBoardChanged("home")
        assertEquals(0, backToStep1.currentStepIndex)
        assertEquals("btn-food", backToStep1.highlightedButtonIdFor("home"))

        // Navigating to Food syncs to step 2, and tapping Apple finishes guidance.
        val syncedStep2 = backToStep1.onBoardChanged("food")
        assertNull(syncedStep2.onButtonActivated("food", "btn-apple"))
    }

    private fun board(
        id: String,
        name: String,
        buttons: List<ObfButton>,
        strings: Map<String, Map<String, String>> = emptyMap(),
    ): ObfBoard = ObfBoard(
        format = "open-board-0.1",
        id = id,
        name = name,
        buttons = buttons,
        strings = strings,
        grid = if (buttons.isEmpty()) {
            ObfGrid(1, 1, listOf(listOf(null)))
        } else {
            ObfGrid(1, buttons.size, listOf(buttons.map { it.id }))
        },
    )

    private fun graphOf(rootId: String, vararg boards: ObfBoard): BoardSetGraph =
        BoardSetGraph(
            boardSet = ObfBoardSet(
                id = "screen-1",
                name = "Core",
                rootBoardId = rootId,
                boardIds = boards.map { it.id },
                createdAt = 1L,
                updatedAt = 1L,
            ),
            boards = boards.toList(),
        )
}
