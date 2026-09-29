package io.github.jdreioe.wingmate.ui

import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LanguageSelectionViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val multilingualVoice = Voice(
        name = "en-US-AvaMultilingual",
        primaryLanguage = "en-US",
        supportedLanguages = listOf("en-US", "da-DK"),
    )

    @Test
    fun `primary language change updates settings and the selected voice`() = runTest {
        val operations = FakeVoiceSelectionOperations(
            settings = Settings(primaryLanguage = "en-US"),
            selected = multilingualVoice,
        )
        val viewModel = LanguageSelectionViewModel(operations).apply { onAction(LanguageSelectionAction.Load) }

        viewModel.onAction(LanguageSelectionAction.PrimarySelected("da-DK"))

        assertEquals("da-DK", operations.settings.primaryLanguage)
        assertEquals("da-DK", operations.selected?.selectedLanguage)
        assertEquals("da-DK", (viewModel.state.value as LanguageSelectionUiState.Ready).primary)
    }

    @Test
    fun `enabling the secondary language picks the other supported language`() = runTest {
        val operations = FakeVoiceSelectionOperations(
            settings = Settings(primaryLanguage = "en-US", secondaryLanguage = ""),
            selected = multilingualVoice,
        )
        val viewModel = LanguageSelectionViewModel(operations).apply { onAction(LanguageSelectionAction.Load) }

        viewModel.onAction(LanguageSelectionAction.SecondaryToggled(enabled = true))

        val ready = viewModel.state.value as LanguageSelectionUiState.Ready
        assertTrue(ready.usesSecondary)
        assertEquals("da-DK", operations.settings.secondaryLanguage)
    }

    @Test
    fun `failed save returns to the persisted language and reports the failure`() = runTest {
        val operations = FakeVoiceSelectionOperations(
            settings = Settings(primaryLanguage = "en-US"),
            selected = multilingualVoice,
        )
        val viewModel = LanguageSelectionViewModel(operations).apply { onAction(LanguageSelectionAction.Load) }
        operations.settingsWriteFails = true

        viewModel.onAction(LanguageSelectionAction.PrimarySelected("da-DK"))

        val ready = viewModel.state.value as LanguageSelectionUiState.Ready
        assertEquals("en-US", ready.primary)
        assertTrue(ready.saveFailed)
        assertFalse(ready.usesSecondary)
    }
}
