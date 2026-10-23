package io.github.jdreioe.wingmate.ui

import android.graphics.Insets
import android.view.WindowInsets
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.filters.SdkSuppress
import io.github.jdreioe.wingmate.application.TypingScreenProjector
import io.github.jdreioe.wingmate.configureEdgeToEdgeWindow
import io.github.jdreioe.wingmate.domain.CommunicationSessionSnapshot
import io.github.jdreioe.wingmate.domain.CommunicationSessionState
import io.github.jdreioe.wingmate.domain.Message
import io.github.jdreioe.wingmate.domain.MessagePart
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.domain.Settings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@SdkSuppress(minSdkVersion = 30)
class LikelyNextKeyboardRowTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun keyboardShowsReadOnlySuggestionsAboveTheExistingTypingControls() {
        val suggestion = SaidText(id = 7, saidText = "Thank you", date = 100L)
        val ready = PhraseScreenState(
            settings = Settings(likelyNextPageEnabled = true),
            likelyNext = listOf(suggestion),
            phrases = listOf(Phrase(id = "water", text = "Water", createdAt = 1L)),
            template = TypingScreenProjector.defaultTemplate(columns = 3, now = 1L),
            templateStatus = TypingLoadStatus.Ready,
            phraseStatus = TypingLoadStatus.Ready,
            categoryStatus = TypingLoadStatus.Ready,
            input = TextFieldValue("Keep my Message"),
            communication = CommunicationSessionState(
                isInitialized = true,
                snapshot = CommunicationSessionSnapshot(activeMessage = Message(listOf(MessagePart("Keep my Message")))),
            ),
        )
        var state by mutableStateOf(ready)
        val actions = mutableListOf<PhraseScreenAction>()
        composeRule.activityRule.scenario.onActivity {
            it.configureEdgeToEdgeWindow()
            // Supply deterministic IME insets instead of depending on Gboard timing.
            it.window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
        composeRule.setContent {
            AppTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    PhraseScreenContent(ui = state, onAction = { actions += it })
                }
            }
        }

        composeRule.onNodeWithTag("likely-next-row").assertDoesNotExist()
        keyboardVisible(true)
        composeRule.mainClock.advanceTimeBy(800)
        composeRule.onNodeWithTag("likely-next-row").assertIsDisplayed()
        composeRule.onNodeWithText("Water").assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).assertTextEquals("Keep my Message").assertIsDisplayed()
        composeRule.onNodeWithText("Thank you")
            .assert(SemanticsMatcher("read-only suggestion has no editing actions") {
                it.config.getOrNull(SemanticsActions.CustomActions).isNullOrEmpty()
            })
            .performClick()
        assertEquals(listOf<PhraseScreenAction>(PhraseScreenAction.ActivateLikelyNext(suggestion.toTypingHistoryPhrase())), actions)

        composeRule.runOnIdle { state = ready.copy(settings = ready.settings.copy(historyVisible = false)) }
        composeRule.onNodeWithTag("likely-next-row").assertDoesNotExist()
        composeRule.runOnIdle { state = ready.copy(settings = ready.settings.copy(likelyNextPageEnabled = false)) }
        composeRule.onNodeWithTag("likely-next-row").assertDoesNotExist()
        composeRule.runOnIdle { state = ready.copy(likelyNext = emptyList()) }
        composeRule.onNodeWithTag("likely-next-row").assertDoesNotExist()
        composeRule.runOnIdle { state = ready }
        composeRule.onNodeWithTag("likely-next-row").assertIsDisplayed()
        keyboardVisible(false)
        composeRule.onNodeWithTag("likely-next-row").assertDoesNotExist()
    }

    private fun keyboardVisible(visible: Boolean) {
        composeRule.runOnUiThread {
            val root = composeRule.activity.window.decorView
            root.dispatchApplyWindowInsets(WindowInsets.Builder(root.rootWindowInsets)
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, if (visible) 600 else 0))
                .setVisible(WindowInsets.Type.ime(), visible)
                .build())
        }
        composeRule.waitForIdle()
    }
}
