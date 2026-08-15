package io.github.jdreioe.wingmate.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.hojmoseit.wingmate.R
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
                    onRetry = {},
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
        var retried = false
        val backToLibraryLabel = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getString(R.string.board_workspace_back_to_library)
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
                    onRetry = { retried = true },
                ) {}
            }
        }

        composeRule.onNodeWithText("Could not load board").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.onNodeWithText(backToLibraryLabel).performClick()

        assertTrue(retried)
        assertTrue(returnedToLibrary)
    }
}
