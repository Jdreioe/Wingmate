package io.github.jdreioe.wingmate.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import android.view.WindowInsets
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.test.filters.SdkSuppress
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import io.github.jdreioe.wingmate.domain.PredictionResult
import io.github.jdreioe.wingmate.domain.TextPredictionService
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.core.context.unloadKoinModules
import org.koin.dsl.module
import org.junit.Assert.assertFalse
import kotlinx.coroutines.awaitCancellation

/**
 * Wingmate's own suggestions show while typing without an on-screen keyboard
 * (a hardware keyboard). With Gboard open they stay hidden, because Gboard shows
 * its own (#299), so the window here never shows a soft keyboard.
 */
@OptIn(ExperimentalComposeUiApi::class)
@SdkSuppress(minSdkVersion = 30)
class TypingPredictionVisibilityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun suggestionsAreVisibleWhileTypingWithAHardwareKeyboard() {
        val originalService = GlobalContext.get().get<TextPredictionService>()
        val predictionModule = module {
            single<TextPredictionService> {
                object : TextPredictionService {
                    override fun predictions(context: String, maxWords: Int, maxLetters: Int) =
                        flowOf(PredictionResult(words = when (context) {
                            "he" -> listOf("hello")
                            "wa" -> listOf("water")
                            else -> emptyList()
                        }))
                    override fun refresh() = Unit
                }
            }
        }
        loadKoinModules(predictionModule)
        try {
            composeRule.setContent {
                // Model a hardware keyboard without letting the emulator's IME open.
                InterceptPlatformTextInput(interceptor = { _, _ -> awaitCancellation() }) {
                    AppTheme { PhraseScreen() }
                }
            }
            val input = composeRule.onNode(hasSetTextAction())
            input.performClick()
            input.performTextClearance()
            input.performTextInput("he")
            input.assertTextEquals("he")
            assertFalse(
                "Hardware-keyboard fixture must keep the IME hidden",
                composeRule.activity.window.decorView.rootWindowInsets.isVisible(WindowInsets.Type.ime()),
            )
            waitForSuggestion("hello")

            input.performTextClearance()
            input.performTextInput("wa")
            waitForSuggestion("water")
            composeRule.onNodeWithText("hello").assertDoesNotExist()

            input.performTextClearance()
            composeRule.onNodeWithText("water").assertDoesNotExist()
        } finally {
            unloadKoinModules(predictionModule)
            loadKoinModules(module { single<TextPredictionService> { originalService } })
        }
    }

    private fun waitForSuggestion(word: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(androidx.compose.ui.test.hasText(word))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(word).assertIsDisplayed()
    }
}
