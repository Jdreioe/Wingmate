package io.github.jdreioe.wingmate.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
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
        composeRule.setContent {
            AppTheme {
                ObfBoardContent(
                    state = ObfBoardUiState(board = board, messageText = "Hello", messageBarEditable = true),
                    onButtonClick = {},
                    onSpeakSentence = { events += "speak" },
                    onDeleteLast = { events += "delete" },
                    onClearSentence = { events += "clear" },
                    onSentenceChanged = { events += "text:$it" },
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

    private fun SemanticsNodeInteraction.runCustomAction(label: String) {
        val action = fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == label }
        composeRule.runOnUiThread { action.action() }
        composeRule.waitForIdle()
    }
}
