package io.github.jdreioe.wingmate.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BoardWorkspaceContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun suppliedReadyContentAndStatusAreDisplayed() {
        composeRule.setContent {
            AppTheme {
                BoardWorkspaceContent(
                    state = BoardWorkspaceState(
                        contentStatus = BoardWorkspaceContentStatus.Ready,
                        statusMessage = "Board saved",
                    ),
                    hasActiveBoard = true,
                    onBackToLibrary = {},
                ) {
                    Text("Communication board")
                }
            }
        }

        composeRule.onNodeWithText("Board saved").assertIsDisplayed()
        composeRule.onNodeWithText("Communication board").assertIsDisplayed()
    }

    @Test
    fun recoverableFailureWithoutBoardCanReturnToLibrary() {
        var returnedToLibrary = false
        composeRule.setContent {
            AppTheme {
                BoardWorkspaceContent(
                    state = BoardWorkspaceState(
                        contentStatus = BoardWorkspaceContentStatus.RecoverableFailure(
                            "Could not load board"
                        ),
                    ),
                    hasActiveBoard = false,
                    onBackToLibrary = { returnedToLibrary = true },
                ) {}
            }
        }

        composeRule.onNodeWithText("Could not load board").assertIsDisplayed()
        composeRule.onNodeWithText("Back to library").performClick()

        assertTrue(returnedToLibrary)
    }
}
