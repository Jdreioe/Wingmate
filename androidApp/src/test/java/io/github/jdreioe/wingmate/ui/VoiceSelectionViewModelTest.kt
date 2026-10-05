package io.github.jdreioe.wingmate.ui

import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSelectionViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `failed cloud refresh still shows cached voices`() = runTest {
        val operations = FakeVoiceSelectionOperations(
            settings = Settings(ttsEngine = TtsEngine.AZURE_USER_RESOURCE),
            cached = listOf(Voice(name = "da-DK-Christel", primaryLanguage = "da-DK")),
        ).apply { refreshFails = true }
        val viewModel = VoiceSelectionViewModel(operations)

        viewModel.onAction(VoiceSelectionAction.Load)

        val ready = viewModel.state.value as VoiceSelectionUiState.Ready
        assertEquals(listOf("da-DK-Christel"), ready.voices.map { it.name })
    }

    @Test
    fun `failed cloud refresh with no cached voices is retryable`() = runTest {
        val operations = FakeVoiceSelectionOperations(
            settings = Settings(ttsEngine = TtsEngine.AZURE_USER_RESOURCE),
        ).apply { refreshFails = true }
        val viewModel = VoiceSelectionViewModel(operations)

        viewModel.onAction(VoiceSelectionAction.Load)
        assertEquals(VoiceSelectionUiState.LoadFailed, viewModel.state.value)

        operations.refreshFails = false
        operations.refreshed = listOf(Voice(name = "en-US-Jenny", primaryLanguage = "en-US"))
        viewModel.onAction(VoiceSelectionAction.Load)

        assertTrue(viewModel.state.value is VoiceSelectionUiState.Ready)
    }

    @Test
    fun `choosing a multilingual voice prefers the filter language and aligns the primary language`() = runTest {
        val multilingual = Voice(
            name = "en-US-AvaMultilingual",
            primaryLanguage = "en-US",
            supportedLanguages = listOf("en-US", "da-DK"),
        )
        val operations = FakeVoiceSelectionOperations(
            settings = Settings(ttsEngine = TtsEngine.AZURE_USER_RESOURCE, primaryLanguage = "en-US"),
            refreshed = listOf(multilingual),
        )
        val viewModel = VoiceSelectionViewModel(operations)
        viewModel.onAction(VoiceSelectionAction.Load)

        viewModel.onAction(VoiceSelectionAction.VoiceSelected(multilingual, filterLanguage = "da-DK"))

        assertEquals("da-DK", operations.selected?.selectedLanguage)
        assertEquals("da-DK", operations.settings.primaryLanguage)
        assertEquals(VoiceSelectionEvent.VoiceChosen, viewModel.events.first())
    }

    @Test
    fun `failed selection keeps the list and reports the failure`() = runTest {
        val voice = Voice(name = "system-default", primaryLanguage = "en-US")
        val operations = FakeVoiceSelectionOperations(settings = Settings(), system = listOf(voice))
            .apply { selectFails = true }
        val viewModel = VoiceSelectionViewModel(operations)
        viewModel.onAction(VoiceSelectionAction.Load)

        viewModel.onAction(VoiceSelectionAction.VoiceSelected(voice, filterLanguage = null))

        val ready = viewModel.state.value as VoiceSelectionUiState.Ready
        assertTrue(ready.saveFailed)
        assertEquals(listOf(voice), ready.voices)
    }
}

internal class FakeVoiceSelectionOperations(
    var settings: Settings = Settings(),
    var system: List<Voice> = emptyList(),
    var refreshed: List<Voice> = emptyList(),
    var cached: List<Voice> = emptyList(),
    var selected: Voice? = null,
) : VoiceSelectionOperations {
    var refreshFails = false
    var selectFails = false
    var settingsWriteFails = false

    override suspend fun settings(): Settings = settings
    override suspend fun updateSettings(transform: (Settings) -> Settings) {
        check(!settingsWriteFails) { "write failed" }
        settings = transform(settings)
    }
    override suspend fun systemVoices(): List<Voice> = system
    override suspend fun refreshCloudVoices(engine: TtsEngine): List<Voice> {
        check(!refreshFails) { "offline" }
        return refreshed
    }
    override suspend fun cachedVoices(engine: TtsEngine): List<Voice> = cached
    override suspend fun selectedVoice(): Voice? = selected
    override suspend fun selectVoice(voice: Voice) {
        check(!selectFails) { "write failed" }
        selected = voice
    }
    override fun reportEvent(event: String, vararg metadata: Pair<String, String>) = Unit
}
