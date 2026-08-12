package io.github.jdreioe.wingmate.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BoardSetManagerScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun suppliedBoardSetIsDisplayedAndForwardsOpenAction() {
        var receivedAction: BoardSetManagerAction? = null
        composeRule.setContent {
            AppTheme {
                BoardSetManagerScreen(
                    state = BoardSetManagerState(
                        boardSets = listOf(
                            ObfBoardSet(
                                id = "core-words",
                                name = "Core words",
                                rootBoardId = "home",
                                boardIds = listOf("home"),
                                createdAt = 1L,
                                updatedAt = 2L,
                            )
                        ),
                        isLoading = false,
                    ),
                    statusMessage = null,
                    importAvailable = true,
                    onAction = { receivedAction = it },
                )
            }
        }

        composeRule.onNodeWithText("Core words").assertIsDisplayed().performClick()

        assertEquals(BoardSetManagerAction.OpenClicked("core-words"), receivedAction)
    }
}
