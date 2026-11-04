package io.github.jdreioe.wingmate.ui

import io.github.jdreioe.wingmate.domain.PiperFile
import io.github.jdreioe.wingmate.domain.PiperVoicePack
import io.github.jdreioe.wingmate.domain.PiperVoiceState
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.piperVoiceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PiperVoicesViewModelTest {
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
    fun `opens on voices for the communicator's language`() = runTest {
        val viewModel = PiperVoicesViewModel(FakePiperVoiceOperations(primaryLanguage = "da-DK"))

        val ready = viewModel.state.value as PiperVoicesUiState.Ready
        assertEquals("da-DK", ready.languageFilter)
        assertEquals(listOf("da_DK-talesyntese-medium"), ready.available.map { it.pack.key })
    }

    @Test
    fun `a voice that cannot be loaded is reported and not selected`() = runTest {
        val operations = FakePiperVoiceOperations(installed = setOf("en_US-lessac-medium")).apply { prepareFails = true }
        val viewModel = PiperVoicesViewModel(operations)

        viewModel.onAction(PiperVoicesAction.Use("en_US-lessac-medium"))

        val ready = viewModel.state.value as PiperVoicesUiState.Ready
        assertEquals("en_US-lessac-medium", ready.unusableKey)
        assertNull(ready.preparingKey)
        assertNull(operations.selected)
    }

    @Test
    fun `using an installed voice selects it as the voice`() = runTest {
        val operations = FakePiperVoiceOperations(installed = setOf("en_US-lessac-medium"))
        val viewModel = PiperVoicesViewModel(operations)

        viewModel.onAction(PiperVoicesAction.Use("en_US-lessac-medium"))

        assertEquals("en_US-lessac-medium", operations.selected?.piperVoiceKey)
        assertEquals("en_US-lessac-medium", (viewModel.state.value as PiperVoicesUiState.Ready).selectedKey)
    }
}

private class FakePiperVoiceOperations(
    private val primaryLanguage: String = "en-US",
    installed: Set<String> = emptySet(),
) : PiperVoiceOperations {
    override val catalog = listOf(
        pack("da_DK-talesyntese-medium", "da-DK"),
        pack("en_US-lessac-medium", "en-US"),
    )
    override val sharedDataBytes = 9_000_000L
    private val mutableStates = MutableStateFlow(installed.associateWith<String, PiperVoiceState> { PiperVoiceState.Installed })
    override val states: StateFlow<Map<String, PiperVoiceState>> = mutableStates
    var prepareFails = false
    var selected: Voice? = null

    override suspend fun refresh() = Unit
    override fun download(key: String) = Unit
    override suspend fun cancel(key: String) = Unit
    override suspend fun delete(key: String) = Unit
    override suspend fun prepare(key: String) {
        check(!prepareFails) { "model failed to load" }
    }
    override suspend fun selectedVoice(): Voice? = selected
    override suspend fun select(voice: Voice) {
        selected = voice
    }
    override suspend fun primaryLanguage(): String = primaryLanguage

    private fun pack(key: String, language: String) = PiperVoicePack(
        key = key,
        languageTag = language,
        speaker = key.split('-')[1],
        quality = "medium",
        speakerCount = 1,
        licence = "CC0",
        files = listOf(PiperFile("$key.onnx", "https://example.test/$key.onnx", 1L, "00")),
    )
}
