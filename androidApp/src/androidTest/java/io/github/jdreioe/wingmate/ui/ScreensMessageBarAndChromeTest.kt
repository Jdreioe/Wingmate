package io.github.jdreioe.wingmate.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.domain.obf.MessageBarAction
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ScreensMessageBarAndChromeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun tappingMessageChipRepeatsPartAndRemoveButtonDeletesIt() {
        val spoken = mutableListOf<Int>()
        val removed = mutableListOf<Int>()
        composeRule.setContent {
            AppTheme {
                ScreensMessageBar(
                    parts = listOf(
                        ScreensMessagePartItem(index = 0, title = "Hello"),
                        ScreensMessagePartItem(index = 1, title = "Water"),
                    ),
                    messageText = "Hello Water",
                    editable = false,
                    selectOnTouchDown = false,
                    onSpeakPart = { spoken += it },
                    onRemovePart = { removed += it },
                )
            }
        }

        composeRule.onNodeWithText("Water").performClick()
        composeRule.onAllNodesWithContentDescription(context.getString(R.string.screens_message_bar_remove_part))[0]
            .performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(1), spoken)
            assertEquals(listOf(0), removed)
        }
    }

    @Test
    fun messageBarActionSquareRunsConfiguredActionsAndEditsInPlace() {
        val events = mutableListOf<String>()
        var actions by mutableStateOf(listOf(MessageBarAction.Speak, MessageBarAction.Hold))
        var editing by mutableStateOf(false)
        composeRule.setContent {
            AppTheme {
                ScreensMessageBarActionSquare(
                    actions = actions,
                    editing = editing,
                    hasMessage = true,
                    isSpeaking = false,
                    isHolding = false,
                    selectOnTouchDown = false,
                    onSpeak = { events += "speak" },
                    onStopSpeech = { events += "stop" },
                    onHold = { events += "hold" },
                    onDeleteLast = { events += "delete" },
                    onClear = { events += "clear" },
                    onActionsChanged = { actions = it },
                )
            }
        }

        composeRule.onNodeWithTag("message-bar-action-speak").performClick()
        composeRule.onNodeWithTag("message-bar-action-hold").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("speak", "hold"), events)
            editing = true
        }

        composeRule.onNodeWithTag("add-message-bar-action").assertIsDisplayed().performClick()
        composeRule.onNodeWithText(context.getString(R.string.screens_message_bar_delete_last)).performClick()
        composeRule.runOnIdle {
            assertEquals(
                listOf(MessageBarAction.Speak, MessageBarAction.Hold, MessageBarAction.DeleteLast),
                actions,
            )
        }
    }

    @Test
    fun editHeaderAndSelectedFieldTrashBadgeForwardCanvasActions() {
        var selectedBoardId by mutableStateOf("home")
        var addedPage = false
        var finished = false
        val removedCells = mutableListOf<Pair<Int, Int>>()
        val boards = listOf(
            ObfBoard(format = "open-board-0.1", id = "home", name = "Home"),
            ObfBoard(format = "open-board-0.1", id = "food", name = "Food"),
        )
        val gridBoard = ObfBoard(
            format = "open-board-0.1",
            id = "home",
            name = "Home",
            buttons = listOf(ObfButton(id = "yes", label = "Yes")),
            grid = ObfGrid(1, 2, listOf(listOf("yes", null))),
        )

        composeRule.setContent {
            AppTheme {
                androidx.compose.foundation.layout.Column {
                    ScreensEditHeader(
                        screenName = "Core",
                        boards = boards,
                        selectedBoardId = selectedBoardId,
                        rootBoardId = "home",
                        canResizePage = true,
                        canUndo = false,
                        onLeave = {},
                        onRenameScreen = {},
                        onOpenScreenSettings = {},
                        onSelectBoard = { selectedBoardId = it },
                        onOpenPageSettings = {},
                        onResizePage = {},
                        onSetRootPage = {},
                        onDeletePage = {},
                        onAddPage = { addedPage = true },
                        onUndo = {},
                        onFinish = { finished = true },
                    )
                    ObfBoardContent(
                        state = ObfBoardUiState(
                            board = gridBoard,
                            isEditMode = true,
                            showMessageBar = false,
                            selectedFieldAnchor = 0 to 0,
                        ),
                        onButtonClick = {},
                        onRemoveField = { r, c -> removedCells += r to c },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("edit-page-chip-food").performClick()
        composeRule.onNodeWithTag("screens-edit-add-page").performClick()
        composeRule.onNodeWithTag("selected-field-remove").performClick()
        composeRule.onNodeWithTag("screens-edit-done").performClick()

        composeRule.runOnIdle {
            assertEquals("food", selectedBoardId)
            assertTrue(addedPage)
            assertEquals(listOf(0 to 0), removedCells)
            assertTrue(finished)
        }
    }
}
