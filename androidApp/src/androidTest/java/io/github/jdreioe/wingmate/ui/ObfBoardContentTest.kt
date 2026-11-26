package io.github.jdreioe.wingmate.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.platform.app.InstrumentationRegistry
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.obf.GridFieldSpan
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import io.github.jdreioe.wingmate.domain.obf.ObfLoadBoard
import io.github.jdreioe.wingmate.domain.obf.ResolvedBoardSettings
import io.github.jdreioe.wingmate.domain.obf.resolveBoardSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ObfBoardContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val yes = ObfButton(id = "yes", label = "Yes")
    private val no = ObfButton(id = "no", label = "No")
    private val secret = ObfButton(id = "secret", label = "Secret", hidden = true)
    private val food = ObfButton(id = "food", label = "Food", loadBoard = ObfLoadBoard(id = "food", name = "Food"))

    // Row 0: Yes, a reference to a missing Button, No. Row 1: hidden, empty, a page link.
    private val board = ObfBoard(
        format = "open-board-0.1",
        id = "board-content-test",
        buttons = listOf(yes, no, secret, food),
        grid = ObfGrid(
            rows = 2,
            columns = 3,
            order = listOf(listOf("yes", "ghost", "no"), listOf("secret", null, "food")),
        ),
    )

    @Test
    fun rendersSuppliedStateAndFollowsReplacementState() {
        var state by mutableStateOf(
            ObfBoardUiState(board = board, messageText = "I want", predictionLabels = mapOf("no" to "Later"))
        )
        composeRule.setContent {
            AppTheme { ObfBoardContent(state = state, onButtonClick = {}) }
        }

        composeRule.onNodeWithText("Yes").assertIsDisplayed()
        composeRule.onNodeWithText("Later").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("I want").assertIsDisplayed()
        composeRule.onNodeWithText("Secret").assertDoesNotExist()

        composeRule.runOnIdle { state = state.copy(messageText = "I want food", showHiddenButtons = true) }

        composeRule.onNodeWithContentDescription("I want food").assertIsDisplayed()
        composeRule.onNodeWithText("Secret").assertIsDisplayed()
    }

    @Test
    fun activatingButtonsInsertsWithFeedbackAndForwardsPageLinks() {
        val clicked = mutableListOf<String>()
        val activated = mutableListOf<String>()
        composeRule.setContent {
            AppTheme {
                ObfBoardContent(
                    state = ObfBoardUiState(board = board),
                    onButtonClick = { clicked += it.id },
                    feedback = BoardFeedback(onActivated = { _, label -> activated += label }),
                )
            }
        }

        composeRule.onNodeWithText("Yes").performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.board_cell_opens_board, "Food"))
            .assertIsDisplayed()
        composeRule.onNodeWithText("Food").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("yes", "food"), clicked)
            assertEquals(listOf("Yes", "Food"), activated)
        }
    }

    @Test
    fun rightClickDoesWhatALongPressDoesWithoutActivating() {
        val clicked = mutableListOf<String>()
        val longClicked = mutableListOf<String>()
        var touchDown by mutableStateOf(false)
        composeRule.setContent {
            AppTheme {
                ObfBoardContent(
                    state = ObfBoardUiState(board = board, settings = Settings(selectOnTouchDown = touchDown)),
                    onButtonClick = { clicked += it.id },
                    onButtonLongClick = { longClicked += it.id },
                )
            }
        }

        // Touch-down selection fires on contact; a right click must still open the menu instead.
        for (mode in listOf(false, true)) {
            composeRule.runOnIdle {
                touchDown = mode
                clicked.clear()
                longClicked.clear()
            }
            composeRule.onNodeWithText("Yes").performMouseInput { rightClick() }
            composeRule.runOnIdle {
                assertEquals("touchDown=$mode", listOf("yes"), longClicked)
                assertEquals("touchDown=$mode", emptyList<String>(), clicked)
            }
        }
    }

    @Test
    fun rightClickOpensTheReorderMenuEvenWhenLongPressesSelect() {
        composeRule.setContent {
            AppTheme {
                ObfBoardContent(
                    state = ObfBoardUiState(board = board, settings = Settings(holdToSelectMillis = 800)),
                    onButtonClick = {},
                    reorder = BoardReorder(
                        canMove = { true },
                        onMove = { _, _ -> },
                        // As the Typing Screen does: hold-to-select keeps long presses.
                        gesturesEnabled = false,
                        menu = { button, _ -> androidx.compose.material3.Text("Menu for ${button.id}") },
                    ),
                )
            }
        }

        composeRule.onNodeWithText("No").performMouseInput { rightClick() }

        composeRule.onNodeWithText("Menu for no").assertIsDisplayed()
    }

    @Test
    fun cellClicksReportPositionInsteadOfButtonClick() {
        val cells = mutableListOf<Triple<Int, Int, String?>>()
        var buttonClicks = 0
        composeRule.setContent {
            AppTheme {
                ObfBoardContent(
                    state = ObfBoardUiState(board = board),
                    onButtonClick = { buttonClicks++ },
                    onCellClick = { row, column, button -> cells += Triple(row, column, button?.id) },
                )
            }
        }

        composeRule.onNodeWithText("No").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(Triple(0, 2, "no")), cells)
            assertEquals(0, buttonClicks)
        }
    }

    @Test
    fun sentenceControlsAndEditableMessageForwardToCallbacks() {
        val events = mutableListOf<String>()
        var message by mutableStateOf("Hello")
        composeRule.setContent {
            AppTheme {
                ObfBoardContent(
                    state = ObfBoardUiState(board = board, messageText = message, messageBarEditable = true),
                    onButtonClick = {},
                    onSpeakSentence = { events += "speak" },
                    onDeleteLast = { events += "delete" },
                    onClearSentence = { events += "clear" },
                    onSentenceChanged = { message = it; events += "text:$it" },
                )
            }
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.board_workspace_speak_sentence)).performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.board_workspace_delete_last)).performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.board_workspace_clear_sentence)).performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Hello there")

        composeRule.runOnIdle {
            assertEquals(listOf("speak", "delete", "clear", "text:Hello there"), events)
        }
    }

    @Test
    fun arrowKeysMoveSuppliedFocusPastMissingAndHiddenCellsAndEnterActivates() {
        var focus by mutableStateOf<Pair<Int, Int>?>(null)
        val clicked = mutableListOf<String>()
        composeRule.setContent {
            AppTheme {
                ObfBoardContent(
                    state = ObfBoardUiState(board = board, focusedCell = focus),
                    onButtonClick = { clicked += it.id },
                    onFocusCell = { focus = it },
                )
            }
        }
        val grid = composeRule.onNodeWithTag("board-grid")
        grid.requestFocus()

        grid.performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.runOnIdle { assertEquals(0 to 0, focus) }
        composeRule.onNodeWithTag("board-focus-ring").assert(hasAnyDescendant(hasText("Yes")))

        grid.performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.runOnIdle { assertEquals(0 to 2, focus) }

        grid.performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.runOnIdle { assertEquals(1 to 2, focus) }
        composeRule.onNodeWithTag("board-focus-ring").assert(hasAnyDescendant(hasText("Food")))

        grid.performKeyInput { pressKey(Key.Enter) }
        composeRule.runOnIdle { assertEquals(listOf("food"), clicked) }
    }

    @Test
    fun switchSelectActivatesTheFocusedButton() {
        val settings = Settings(selectKeyBinding = "Space")
        val clicked = mutableListOf<String>()
        var host: AndroidAccessInputHost? = null
        composeRule.setContent {
            AppTheme {
                InteractionInputRoot(settings = settings) {
                    host = LocalAccessInputHost.current
                    ObfBoardContent(
                        state = ObfBoardUiState(board = board, settings = settings),
                        onButtonClick = { clicked += it.id },
                    )
                }
            }
        }
        // Key events need a focused node; the switch target itself is tracked by the host.
        val grid = composeRule.onNodeWithTag("board-grid")
        grid.requestFocus()
        composeRule.runOnIdle { host!!.focus("board:yes") }

        grid.performKeyInput {
            keyDown(Key.Spacebar)
            keyUp(Key.Spacebar)
        }

        composeRule.runOnIdle { assertEquals(listOf("yes"), clicked) }
    }

    @Test
    fun resizeActionsReportAllowedSpansAndAnnounceBlockedOnes() {
        val resized = mutableListOf<List<Int>>()
        composeRule.setContent {
            AppTheme {
                ObfBoardContent(
                    state = ObfBoardUiState(
                        board = board,
                        isEditMode = true,
                        selectedFieldAnchor = 0 to 0,
                        selectedFieldSpans = listOf(GridFieldSpan(1, 1), GridFieldSpan(1, 2)),
                    ),
                    onButtonClick = {},
                    onResizeField = { row, column, rows, columns -> resized += listOf(row, column, rows, columns) },
                )
            }
        }
        val handle = composeRule.onNodeWithTag("resize-handle")

        handle.runCustomAction(context.getString(R.string.board_resize_increase_width))
        composeRule.runOnIdle { assertEquals(listOf(listOf(0, 0, 1, 2)), resized) }
        composeRule.onNodeWithContentDescription(context.getString(R.string.board_resize_size, 2, 1))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))

        handle.runCustomAction(context.getString(R.string.board_resize_increase_height))
        composeRule.runOnIdle { assertEquals(1, resized.size) }
        composeRule.onNodeWithContentDescription(context.getString(R.string.board_resize_blocked_occupied))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun editModeOffersMissingAndEmptyCellsForFilling() {
        val cells = mutableListOf<Triple<Int, Int, String?>>()
        val onlyMissing = board.copy(grid = ObfGrid(1, 1, listOf(listOf("ghost"))))
        composeRule.setContent {
            AppTheme {
                ObfBoardContent(
                    state = ObfBoardUiState(board = onlyMissing, isEditMode = true),
                    onButtonClick = {},
                    onCellClick = { row, column, button -> cells += Triple(row, column, button?.id) },
                )
            }
        }

        composeRule.onNodeWithTag("board-empty").assertDoesNotExist()
        composeRule.onNodeWithText("+").performClick()

        composeRule.runOnIdle { assertEquals(listOf(Triple(0, 0, null)), cells) }
    }

    @Test
    fun pageWithoutUsableButtonsExplainsItselfAndKeepsTheMessage() {
        var state by mutableStateOf(
            ObfBoardUiState(
                board = board.copy(grid = ObfGrid(1, 2, listOf(listOf("ghost", "secret")))),
                messageText = "Hello",
            )
        )
        var spoken = 0
        composeRule.setContent {
            AppTheme { ObfBoardContent(state = state, onButtonClick = {}, onSpeakSentence = { spoken++ }) }
        }
        val emptyMessage = context.getString(R.string.board_page_empty)

        composeRule.onNodeWithText(emptyMessage).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Hello").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.board_workspace_speak_sentence)).performClick()
        composeRule.runOnIdle { assertEquals(1, spoken) }

        composeRule.runOnIdle { state = state.copy(board = ObfBoard(format = "open-board-0.1", id = "blank")) }
        composeRule.onNodeWithText(emptyMessage).assertIsDisplayed()
    }

    @Test
    fun touchExitSurvivesMidDragRecompositionAndActivatesButtonOnLift() {
        val clicked = mutableListOf<String>()
        val previews = mutableListOf<String>()
        var state by mutableStateOf(
            ObfBoardUiState(
                board = board,
                messageText = "Start",
                settings = Settings(selectOnTouchExit = true, auditoryFishingEnabled = true),
            )
        )
        composeRule.setContent {
            AppTheme {
                ObfBoardContent(
                    state = state,
                    onButtonClick = { clicked += it.id },
                    feedback = BoardFeedback(
                        onAuditoryCue = { _, label ->
                            previews += label
                            // Simulate CommunicationSession playback state recomposing the board mid-drag.
                            state = state.copy(messageText = "Speaking:$label")
                        },
                    ),
                )
            }
        }

        val grid = composeRule.onNodeWithTag("board-grid")
        val gridBounds = grid.fetchSemanticsNode().boundsInRoot
        val yesCenter = composeRule.onNodeWithText("Yes").fetchSemanticsNode().boundsInRoot.center - gridBounds.topLeft
        val noCenter = composeRule.onNodeWithText("No").fetchSemanticsNode().boundsInRoot.center - gridBounds.topLeft

        grid.performTouchInput { down(yesCenter) }
        composeRule.waitForIdle()
        grid.performTouchInput { moveTo(noCenter) }
        composeRule.waitForIdle()
        grid.performTouchInput { up() }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(listOf("Yes", "No"), previews)
            assertEquals(listOf("no"), clicked)
        }
    }

    @Test
    fun touchExitLiftInKeyguardGapOrOutsideCancelsSafely() {
        val clicked = mutableListOf<String>()
        var cancelCount = 0
        val twoButtonBoard = ObfBoard(
            format = "open-board-0.1",
            id = "gap-test",
            buttons = listOf(yes, no),
            grid = ObfGrid(rows = 1, columns = 2, order = listOf(listOf("yes", "no"))),
        )
        val spacedSettings: ResolvedBoardSettings = resolveBoardSettings(
            appShowLabels = true,
            appShowSymbols = false,
            appLabelAtTop = false,
        ).copy(cellGapDp = 32, outerMarginDp = 16)
        composeRule.setContent {
            AppTheme {
                ObfBoardContent(
                    state = ObfBoardUiState(
                        board = twoButtonBoard,
                        boardSettings = spacedSettings,
                        settings = Settings(selectOnTouchExit = true, auditoryFishingEnabled = true),
                    ),
                    onButtonClick = { clicked += it.id },
                    feedback = BoardFeedback(onCancelAuditoryCue = { cancelCount++ }),
                )
            }
        }

        val grid = composeRule.onNodeWithTag("board-grid")
        val gridBounds = grid.fetchSemanticsNode().boundsInRoot
        val yesBounds = composeRule.onNodeWithText("Yes").fetchSemanticsNode().boundsInRoot
        val noBounds = composeRule.onNodeWithText("No").fetchSemanticsNode().boundsInRoot
        val yesLocal = yesBounds.center - gridBounds.topLeft
        val gapLocal = Offset(
            x = (yesBounds.right + noBounds.left) / 2f - gridBounds.left,
            y = yesBounds.center.y - gridBounds.top,
        )

        grid.performTouchInput { down(yesLocal) }
        composeRule.waitForIdle()
        grid.performTouchInput { moveTo(gapLocal) }
        composeRule.waitForIdle()
        grid.performTouchInput { up() }
        composeRule.waitForIdle()

        var cancelCountAfterGap = 0
        composeRule.runOnIdle {
            assertEquals(emptyList<String>(), clicked)
            assert(cancelCount > 0)
            cancelCountAfterGap = cancelCount
        }

        val outsideGridLocal = Offset(
            x = -24f,
            y = yesBounds.center.y - gridBounds.top,
        )
        grid.performTouchInput { down(yesLocal) }
        composeRule.waitForIdle()
        grid.performTouchInput { moveTo(outsideGridLocal) }
        composeRule.waitForIdle()
        grid.performTouchInput { up() }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(emptyList<String>(), clicked)
            assert(cancelCount > cancelCountAfterGap)
        }
    }

    @Test
    fun touchExitSystemCancelDoesNotActivateButton() {
        val clicked = mutableListOf<String>()
        var cancelCount = 0
        composeRule.setContent {
            AppTheme {
                ObfBoardContent(
                    state = ObfBoardUiState(
                        board = board,
                        settings = Settings(selectOnTouchExit = true),
                    ),
                    onButtonClick = { clicked += it.id },
                    feedback = BoardFeedback(onCancelAuditoryCue = { cancelCount++ }),
                )
            }
        }

        val grid = composeRule.onNodeWithTag("board-grid")
        val gridBounds = grid.fetchSemanticsNode().boundsInRoot
        val yesLocal = composeRule.onNodeWithText("Yes").fetchSemanticsNode().boundsInRoot.center - gridBounds.topLeft

        grid.performTouchInput {
            down(yesLocal)
            cancel()
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(emptyList<String>(), clicked)
            assert(cancelCount > 0)
        }
    }

    @Test
    fun touchExitCancelsWhenPageIdentityChangesMidGesture() {
        val clicked = mutableListOf<String>()
        var cancelCount = 0
        var activeBoard by mutableStateOf(board.copy(id = "page-a"))
        composeRule.setContent {
            AppTheme {
                ObfBoardContent(
                    state = ObfBoardUiState(
                        board = activeBoard,
                        settings = Settings(selectOnTouchExit = true),
                    ),
                    onButtonClick = { clicked += "${activeBoard.id}:${it.id}" },
                    feedback = BoardFeedback(onCancelAuditoryCue = { cancelCount++ }),
                )
            }
        }

        val grid = composeRule.onNodeWithTag("board-grid")
        val gridBounds = grid.fetchSemanticsNode().boundsInRoot
        val yesLocal = composeRule.onNodeWithText("Yes").fetchSemanticsNode().boundsInRoot.center - gridBounds.topLeft

        grid.performTouchInput { down(yesLocal) }
        composeRule.waitForIdle()
        // Swap to a different Page with the exact same grid shape and items while finger is held down.
        composeRule.runOnIdle { activeBoard = board.copy(id = "page-b") }
        composeRule.waitForIdle()
        grid.performTouchInput { up() }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(emptyList<String>(), clicked)
            assert(cancelCount > 0)
        }
    }

    @Test
    fun touchExitActivatesVisibleButtonInRtlLayout() {
        val clicked = mutableListOf<String>()
        val previews = mutableListOf<String>()
        val twoButtonBoard = ObfBoard(
            format = "open-board-0.1",
            id = "rtl-test",
            buttons = listOf(yes, no),
            grid = ObfGrid(rows = 1, columns = 2, order = listOf(listOf("yes", "no"))),
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                AppTheme {
                    ObfBoardContent(
                        state = ObfBoardUiState(
                            board = twoButtonBoard,
                            settings = Settings(selectOnTouchExit = true, auditoryFishingEnabled = true),
                        ),
                        onButtonClick = { clicked += it.id },
                        feedback = BoardFeedback(onAuditoryCue = { _, label -> previews += label }),
                    )
                }
            }
        }

        val grid = composeRule.onNodeWithTag("board-grid")
        val gridBounds = grid.fetchSemanticsNode().boundsInRoot
        val yesBounds = composeRule.onNodeWithText("Yes").fetchSemanticsNode().boundsInRoot
        val noBounds = composeRule.onNodeWithText("No").fetchSemanticsNode().boundsInRoot
        // In RTL, column 0 ("Yes") renders on the right of column 1 ("No").
        assert(yesBounds.center.x > noBounds.center.x)

        val yesLocal = yesBounds.center - gridBounds.topLeft
        val noLocal = noBounds.center - gridBounds.topLeft

        grid.performTouchInput { down(yesLocal) }
        composeRule.waitForIdle()
        grid.performTouchInput { up() }
        composeRule.waitForIdle()
        grid.performTouchInput { down(noLocal) }
        composeRule.waitForIdle()
        grid.performTouchInput { up() }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(listOf("Yes", "No"), previews)
            assertEquals(listOf("yes", "no"), clicked)
        }
    }

    private fun SemanticsNodeInteraction.runCustomAction(label: String) {
        val action = fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == label }
        composeRule.runOnUiThread { action.action() }
        composeRule.waitForIdle()
    }
}
