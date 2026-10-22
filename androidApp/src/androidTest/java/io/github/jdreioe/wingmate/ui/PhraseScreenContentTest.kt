package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.platform.app.InstrumentationRegistry
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.application.TypingScreenProjector
import io.github.jdreioe.wingmate.domain.CategoryItem
import io.github.jdreioe.wingmate.domain.CommunicationAction
import io.github.jdreioe.wingmate.domain.CommunicationFailure
import io.github.jdreioe.wingmate.domain.CommunicationFailureKind
import io.github.jdreioe.wingmate.domain.CommunicationSessionSnapshot
import io.github.jdreioe.wingmate.domain.CommunicationSessionState
import io.github.jdreioe.wingmate.domain.Message
import io.github.jdreioe.wingmate.domain.MessagePart
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.obf.ObfButtonActionEffect
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Stateless Phrase screen content rendered from supplied state, without Koin or a ViewModel. */
class PhraseScreenContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val water = Phrase(id = "water", text = "Water", createdAt = 1L)
    private val food = CategoryItem(id = "food", name = "Food")
    private val message = "I need help"
    private val communication = CommunicationSessionState(
        snapshot = CommunicationSessionSnapshot(activeMessage = Message(listOf(MessagePart(message)))),
        isInitialized = true,
    )
    private val ready = PhraseScreenState(
        phrases = listOf(water),
        phraseStatus = TypingLoadStatus.Ready,
        communication = communication,
        template = TypingScreenProjector.defaultTemplate(columns = 3, now = 1L),
        templateStatus = TypingLoadStatus.Ready,
        categories = listOf(food),
        categoryStatus = TypingLoadStatus.Ready,
        input = TextFieldValue(message),
    )

    @Test
    fun suppliedMessageAndVocabularyRouteUserActions() {
        val actions = render(ready)

        composeRule.onNodeWithText(message).assertIsDisplayed()
        composeRule.onNodeWithText("Water").performClick()
        composeRule.onNodeWithText("Food").performClick()
        composeRule.onNodeWithText(context.getString(R.string.phrase_add_title)).performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.playback_play)).performClick()

        assertEquals(
            listOf(
                PhraseScreenAction.ActivatePhrase(water),
                PhraseScreenAction.SelectPage(TypingPageSelection.Category(food)),
                PhraseScreenAction.RequestDialog(TypingDialog.AddPhrase),
                PhraseScreenAction.Control(ObfButtonActionEffect.Speak),
            ),
            actions,
        )
    }

    // --- Screen reader: the core speak flow through accessibility actions alone ---

    @Test
    fun phrasesAndSpeakWorkThroughAccessibilityActionsEvenWithHoldToSelect() {
        // A screen reader's double-tap is an accessibility click, never a timed hold.
        val actions = render(ready.copy(settings = Settings(holdToSelectMillis = 800)))

        composeRule.onNodeWithText("Water")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithContentDescription(context.getString(R.string.playback_play))
            .performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(
            listOf(PhraseScreenAction.ActivatePhrase(water), PhraseScreenAction.Control(ObfButtonActionEffect.Speak)),
            actions,
        )
    }

    @Test
    fun phraseOptionsAreOfferedOnThePhraseItself() {
        render(ready)

        val options = context.getString(R.string.phrase_item_edit)
        composeRule.onNodeWithText("Water").assert(SemanticsMatcher("offers $options") { node ->
            node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().any { it.label == options }
        })
    }

    @Test
    fun emptyMessageFieldIsNamedByItsHint() {
        render(ready.copy(input = TextFieldValue("")))

        composeRule.onNode(hasSetTextAction() and hasText(context.getString(R.string.phrase_screen_enter_text_placeholder)))
            .assertIsDisplayed()
    }

    // --- #110: select on touch-down ---

    @Test
    fun touchDownSelectsOnceOnTouchEvenIfTheFingerThenMoves() {
        val actions = render(ready.copy(settings = Settings(selectOnTouchDown = true)))

        composeRule.onNodeWithText("Water").performTouchInput { down(center) }
        assertEquals(listOf<PhraseScreenAction>(PhraseScreenAction.ActivatePhrase(water)), actions)
        composeRule.onNodeWithText("Water").performTouchInput { moveBy(Offset(0f, 200f)); up() }
        composeRule.onNodeWithContentDescription(context.getString(R.string.playback_play)).performTouchInput { down(center); up() }

        assertEquals(
            listOf(PhraseScreenAction.ActivatePhrase(water), PhraseScreenAction.Control(ObfButtonActionEffect.Speak)),
            actions,
        )
    }

    @Test
    fun releaseSelectionWaitsForTheFingerToLift() {
        val actions = render(ready)

        composeRule.onNodeWithText("Water").performTouchInput { down(center) }
        assertEquals(emptyList<PhraseScreenAction>(), actions)
        composeRule.onNodeWithText("Water").performTouchInput { up() }

        assertEquals(listOf<PhraseScreenAction>(PhraseScreenAction.ActivatePhrase(water)), actions)
    }

    @Test
    fun likelyNextSuggestionActivatesLikeAHistoryItem() {
        val suggestion = SaidText(id = 3, saidText = "Thank you", date = 5L)
        val actions = render(ready.copy(
            settings = Settings(likelyNextPageEnabled = true),
            selectedPage = TypingPageSelection.LikelyNext,
            likelyNext = listOf(suggestion),
        ))

        composeRule.onNodeWithText(context.getString(R.string.category_likely_next)).assertIsDisplayed()
        composeRule.onNodeWithText("Thank you").performClick()

        assertEquals(
            listOf<PhraseScreenAction>(PhraseScreenAction.ActivatePhrase(Phrase(id = "history_3", text = "Thank you", createdAt = 5L))),
            actions,
        )
    }

    @Test
    fun selectedPhraseIsHighlightedOnlyWhenHighlightingIsOn() {
        render(ready.copy(settings = Settings(selectionHighlightMillis = 60_000)))
        composeRule.onAllNodesWithTag("selection-highlight", useUnmergedTree = true).assertCountEquals(0)

        composeRule.onNodeWithText("Water").performClick()

        composeRule.onAllNodesWithTag("selection-highlight", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun recoverableLoadFailureOffersRetryAndKeepsTheMessage() {
        val actions = render(ready.copy(template = null, templateStatus = TypingLoadStatus.Failed))

        composeRule.onAllNodesWithText(context.getString(R.string.typing_screen_load_failed)).onFirst().assertIsDisplayed()
        composeRule.onNodeWithText(message).assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(R.string.common_retry)).onFirst().performClick()

        assertEquals(listOf<PhraseScreenAction>(PhraseScreenAction.RetryLoad), actions)
    }

    @Test
    fun failedEditOffersRetryAndKeepsTheMessage() {
        val actions = render(ready.copy(failedMutation = PhraseScreenAction.DeletePhrase("water")))

        composeRule.onNodeWithText(context.getString(R.string.typing_screen_save_failed)).assertIsDisplayed()
        composeRule.onNodeWithText(message).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.common_retry)).performClick()

        assertEquals(listOf<PhraseScreenAction>(PhraseScreenAction.RetryMutation), actions)
    }

    @Test
    fun categoryDraftIsShownAndItsActionsAreRouted() {
        val actions = render(ready.copy(dialog = TypingDialog.AddCategory, categoryName = "Snacks", failedMutation = PhraseScreenAction.SaveCategory))

        composeRule.onNodeWithText("Snacks").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.common_add)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.common_cancel)).performClick()

        assertEquals(
            listOf(
                PhraseScreenAction.SaveCategory,
                PhraseScreenAction.DismissDialog,
                PhraseScreenAction.CategoryName(""),
            ),
            actions,
        )
    }

    /**
     * Speech failure feedback belongs to the app root's shared status, not to the
     * Phrase content; together they keep the Message and a visible way to retry.
     */
    @Test
    fun speechFailureIsShownBesideTheMessageWhichCanBeSpokenAgain() {
        val failed = communication.copy(lastFailure = CommunicationFailure(1, CommunicationFailureKind.Playback))
        val actions = mutableListOf<PhraseScreenAction>()
        val sessionActions = mutableListOf<CommunicationAction>()
        composeRule.setContent {
            AppTheme {
                Column(Modifier.fillMaxSize()) {
                    CommunicationSessionStatus(failed) { sessionActions += it }
                    Box(Modifier.weight(1f)) {
                        PhraseScreenContent(ui = ready.copy(communication = failed), onAction = { actions += it })
                    }
                }
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.communication_playback_failed)).assertIsDisplayed()
        composeRule.onNodeWithText(message).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.playback_play)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.update_dismiss)).performClick()

        assertEquals(listOf<PhraseScreenAction>(PhraseScreenAction.Control(ObfButtonActionEffect.Speak)), actions)
        assertEquals(listOf<CommunicationAction>(CommunicationAction.DismissFailure), sessionActions)
        // The Phrase content does not repeat the root's status.
        assertEquals(1, composeRule.onAllNodesWithText(context.getString(R.string.communication_playback_failed))
            .fetchSemanticsNodes().size)
    }

    private fun render(state: PhraseScreenState): List<PhraseScreenAction> {
        val actions = mutableListOf<PhraseScreenAction>()
        composeRule.setContent {
            AppTheme { PhraseScreenContent(ui = state, onAction = { actions += it }) }
        }
        return actions
    }
}
