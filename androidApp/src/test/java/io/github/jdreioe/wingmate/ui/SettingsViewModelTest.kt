package io.github.jdreioe.wingmate.ui

import androidx.lifecycle.SavedStateHandle
import io.github.jdreioe.wingmate.application.BackupRestoreResult
import io.github.jdreioe.wingmate.application.EditingAccessState
import io.github.jdreioe.wingmate.application.FeatureUsageEvents
import io.github.jdreioe.wingmate.domain.PronunciationEntry
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.domain.StartupMode
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.obf.BoardActivationBehavior
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.infrastructure.ArasaacDownloadProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
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
    fun `initialize loads settings and status into the ready state`() = runTest {
        val operations = FakeSettingsOperations().apply {
            settings = Settings(ttsEngine = TtsEngine.GOOGLE_CLOUD, gridColumns = 5)
            azureConfigured = true
            googleConfigured = true
            boardSets += boardSet("set-1")
        }
        val viewModel = SettingsViewModel(SavedStateHandle(), operations)

        viewModel.onAction(SettingsAction.Initialize)

        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertFalse(state.loadFailed)
        assertEquals(TtsEngine.GOOGLE_CLOUD, state.settings.ttsEngine)
        assertEquals(5, state.settings.gridColumns)
        assertTrue(state.azureCredentialConfigured)
        assertTrue(state.googleCredentialConfigured)
        assertEquals(listOf("set-1"), state.boardSets.map { it.id })
    }

    @Test
    fun `load failure is retryable`() = runTest {
        val operations = FakeSettingsOperations()
        operations.loadError = IllegalStateException("disk broken")
        val viewModel = SettingsViewModel(SavedStateHandle(), operations)

        viewModel.onAction(SettingsAction.Initialize)
        assertTrue(viewModel.state.value.loadFailed)

        operations.loadError = null
        viewModel.onAction(SettingsAction.RetryLoad)
        assertFalse(viewModel.state.value.isLoading)
        assertFalse(viewModel.state.value.loadFailed)
    }

    @Test
    fun `value changes persist and update the shown state`() = runTest {
        val operations = FakeSettingsOperations()
        val viewModel = SettingsViewModel(SavedStateHandle(), operations)
        viewModel.onAction(SettingsAction.Initialize)

        viewModel.onAction(SettingsAction.SpeechPolicyChanged(
            io.github.jdreioe.wingmate.domain.SpeechPolicy.SentenceOnly
        ))
        viewModel.onAction(SettingsAction.StartupModeChanged(StartupMode.Screens))

        assertEquals(
            io.github.jdreioe.wingmate.domain.SpeechPolicy.SentenceOnly,
            viewModel.state.value.settings.speechPolicy,
        )
        assertEquals(StartupMode.Screens, operations.settings.startupMode)
        assertEquals(StartupMode.Screens, viewModel.state.value.settings.startupMode)
    }

    @Test
    fun `failed save stays visible with the user's choice until retry succeeds`() = runTest {
        val operations = FakeSettingsOperations()
        val viewModel = SettingsViewModel(SavedStateHandle(), operations)
        viewModel.onAction(SettingsAction.Initialize)

        operations.failNextUpdate = true
        viewModel.onAction(SettingsAction.HighContrastModeChanged(true))
        runCurrent()

        // The choice remains visible and the failure is surfaced for retry.
        assertTrue(viewModel.state.value.saveFailed)
        assertTrue(viewModel.state.value.settings.highContrastMode)
        assertFalse(operations.settings.highContrastMode)

        operations.failNextUpdate = false
        viewModel.onAction(SettingsAction.RetrySave)

        assertFalse(viewModel.state.value.saveFailed)
        assertTrue(operations.settings.highContrastMode)
    }

    @Test
    fun `back pops speech sub-pages, categories, then closes settings`() = runTest {
        val viewModel = SettingsViewModel(SavedStateHandle(), FakeSettingsOperations())
        viewModel.onAction(SettingsAction.Initialize)

        viewModel.onAction(SettingsAction.CategorySelected(SettingsTab.Display))
        viewModel.onAction(SettingsAction.CategorySelected(SettingsTab.Speech))
        viewModel.onAction(SettingsAction.VoiceSelectionOpened)

        viewModel.onAction(SettingsAction.BackClicked)
        assertEquals(SettingsRoute.Category(SettingsTab.Speech), viewModel.state.value.route)

        viewModel.onAction(SettingsAction.BackClicked)
        assertEquals(SettingsRoute.Home, viewModel.state.value.route)

        viewModel.onAction(SettingsAction.CategorySelected(SettingsTab.Privacy))
        viewModel.onAction(SettingsAction.PronunciationOpened)
        viewModel.onAction(SettingsAction.BackClicked)
        assertEquals(SettingsRoute.Home, viewModel.state.value.route)

        val closed = async { viewModel.events.first() }
        viewModel.onAction(SettingsAction.BackClicked)
        assertEquals(SettingsEvent.Close, closed.await())
    }

    @Test
    fun `category route survives process recreation`() {
        val savedState = SavedStateHandle()
        val first = SettingsViewModel(savedState, FakeSettingsOperations())
        first.onAction(SettingsAction.CategorySelected(SettingsTab.Accessibility))

        val restored = SettingsViewModel(savedState, FakeSettingsOperations())

        assertEquals(
            SettingsRoute.Category(SettingsTab.Accessibility),
            restored.state.value.route,
        )
    }

    @Test
    fun `azure credentials save after the debounce clears the draft`() = runTest {
        val operations = FakeSettingsOperations()
        val viewModel = SettingsViewModel(SavedStateHandle(), operations)
        viewModel.onAction(SettingsAction.Initialize)

        val endpoint = "https://eastus.api.cognitive.microsoft.com"
        viewModel.onAction(SettingsAction.AzureEndpointChanged(endpoint))
        viewModel.onAction(SettingsAction.AzureSubscriptionKeyChanged("key-123"))
        advanceTimeBy(400)
        runCurrent()

        assertEquals(listOf(endpoint), operations.savedAzure.map { it.endpoint })
        assertTrue(viewModel.state.value.azureCredentialConfigured)
        assertEquals("", viewModel.state.value.azureSubscriptionKey)
        assertFalse(viewModel.state.value.azureEndpointInvalid)
    }

    @Test
    fun `invalid azure endpoint shows an error without saving`() = runTest {
        val operations = FakeSettingsOperations()
        val viewModel = SettingsViewModel(SavedStateHandle(), operations)
        viewModel.onAction(SettingsAction.Initialize)

        viewModel.onAction(SettingsAction.ReplaceAzureCredentialsClicked)
        viewModel.onAction(SettingsAction.AzureEndpointChanged("http://insecure.example.com"))
        viewModel.onAction(SettingsAction.AzureSubscriptionKeyChanged("key-123"))
        advanceTimeBy(400)
        runCurrent()

        assertTrue(viewModel.state.value.azureEndpointInvalid)
        assertTrue(operations.savedAzure.isEmpty())
        assertFalse(viewModel.state.value.azureCredentialConfigured)
    }

    @Test
    fun `arasaac download records progress outcome and failures`() = runTest {
        val operations = FakeSettingsOperations().apply {
            downloadResult = ArasaacDownloadProgress(completed = 8, total = 10, failed = 2)
        }
        val viewModel = SettingsViewModel(SavedStateHandle(), operations)
        viewModel.onAction(SettingsAction.Initialize)

        viewModel.onAction(SettingsAction.DownloadArasaacClicked)

        assertNull(viewModel.state.value.arasaacProgress)
        assertTrue(viewModel.state.value.arasaacDownloadFailed)
        assertEquals(2, viewModel.state.value.arasaacFailedCount)
        assertEquals(8, viewModel.state.value.cachedArasaacSymbols)
    }

    @Test
    fun `dictionary edits reload entries`() = runTest {
        val operations = FakeSettingsOperations()
        val viewModel = SettingsViewModel(SavedStateHandle(), operations)
        viewModel.onAction(SettingsAction.Initialize)

        viewModel.onAction(SettingsAction.PronunciationOpened)
        viewModel.onAction(SettingsAction.DictionaryEntryAdded("cat", "kæt", "ipa"))

        assertEquals(
            listOf(PronunciationEntry("cat", "kæt", "ipa")),
            viewModel.state.value.dictionaryEntries,
        )

        viewModel.onAction(SettingsAction.DictionaryEntryDeleted("cat"))
        assertTrue(viewModel.state.value.dictionaryEntries.isEmpty())
    }

    @Test
    fun `labels cannot be turned off while symbols stay visible`() = runTest {
        val operations = FakeSettingsOperations().apply {
            settings = Settings(showLabels = true, showSymbols = false)
        }
        val viewModel = SettingsViewModel(SavedStateHandle(), operations)
        viewModel.onAction(SettingsAction.Initialize)

        viewModel.onAction(SettingsAction.ShowLabelsChanged(false))

        assertTrue(viewModel.state.value.settings.showLabels)
    }

    @Test
    fun `consent change enables reporting and reports the event`() = runTest {
        val operations = FakeSettingsOperations()
        val viewModel = SettingsViewModel(SavedStateHandle(), operations)
        viewModel.onAction(SettingsAction.Initialize)

        viewModel.onAction(SettingsAction.FeatureReportingChanged(true))

        assertTrue(viewModel.state.value.settings.featureUsageReportingEnabled)
        assertTrue(operations.reportingEnabled.last())
        assertEquals(
            FeatureUsageEvents.ANALYTICS_CONSENT_CHANGED,
            operations.reportedEvents.last(),
        )
    }

    @Test
    fun `board set sentence caching updates the cached list`() = runTest {
        val original = boardSet("set-1")
        val updated = original.copy(cacheWholeSentences = true)
        val operations = FakeSettingsOperations().apply {
            boardSets += original
            sentenceCachingResult = updated
        }
        val viewModel = SettingsViewModel(SavedStateHandle(), operations)
        viewModel.onAction(SettingsAction.Initialize)

        viewModel.onAction(SettingsAction.BoardSetSentenceCachingChanged("set-1", true))

        assertEquals(listOf(updated), viewModel.state.value.boardSets)
    }

    private fun boardSet(id: String) = ObfBoardSet(
        id = id,
        name = "Set $id",
        rootBoardId = "$id-root",
        boardIds = listOf("$id-root"),
        createdAt = 1L,
        updatedAt = 2L,
    )

    private class FakeSettingsOperations : SettingsOperations {
        override val arasaacAvailable: Boolean = true
        override val partnerDeviceConnected: Flow<Boolean> = MutableStateFlow(false)
        override val editingAccessState: StateFlow<EditingAccessState>? =
            MutableStateFlow(EditingAccessState())

        var settings: Settings = Settings()
        var loadError: Exception? = null
        var failNextUpdate = false
        var azureConfigured = false
        var googleConfigured = false
        val boardSets = mutableListOf<ObfBoardSet>()
        var sentenceCachingResult: ObfBoardSet? = null
        val savedAzure = mutableListOf<SpeechServiceConfig>()
        var downloadResult = ArasaacDownloadProgress(completed = 0, total = 0)
        var downloadError: Exception? = null
        val dictionary = mutableListOf<PronunciationEntry>()
        val reportingEnabled = mutableListOf<Boolean>()
        val reportedEvents = mutableListOf<String>()

        override suspend fun getSettings(): Settings {
            loadError?.let { throw it }
            return settings
        }

        override suspend fun updateSettings(update: (Settings) -> Settings) {
            if (failNextUpdate) throw IllegalStateException("persist failed")
            settings = update(settings)
        }

        override suspend fun azureStatus(): Pair<String, Boolean> = "" to azureConfigured

        override suspend fun googleCredentialConfigured(): Boolean = googleConfigured

        override suspend fun saveAzureCredentials(
            endpoint: String,
            subscriptionKey: String,
        ): Boolean {
            savedAzure += SpeechServiceConfig(endpoint, subscriptionKey)
            return true
        }

        override suspend fun clearGoogleCredentials() {
            googleConfigured = false
        }

        override suspend fun listBoardSets(): List<ObfBoardSet> = boardSets.toList()

        override suspend fun setSentenceCaching(id: String, enabled: Boolean): ObfBoardSet? =
            sentenceCachingResult

        override suspend fun cachedArasaacSymbolCount(): Int = 0

        override suspend fun downloadArasaacSymbols(
            languageTag: String,
            onProgress: (ArasaacDownloadProgress) -> Unit,
        ): ArasaacDownloadProgress {
            onProgress(ArasaacDownloadProgress(completed = 4, total = 10))
            downloadError?.let { throw it }
            return downloadResult
        }

        override suspend fun dictionaryEntries(): List<PronunciationEntry> = dictionary.toList()

        override suspend fun addDictionaryEntry(entry: PronunciationEntry) {
            dictionary += entry
        }

        override suspend fun deleteDictionaryEntry(word: String) {
            dictionary.removeAll { it.word == word }
        }

        override suspend fun speakPronunciation(word: String, phoneme: String, alphabet: String) = Unit

        override suspend fun guessPronunciation(word: String, languageTag: String): String? = null

        override fun setUsageReportingEnabled(enabled: Boolean) {
            reportingEnabled += enabled
        }

        override fun reportEvent(event: String, metadata: Map<String, String>) {
            reportedEvents += event
        }

        override suspend fun exportBackup(): ByteArray = ByteArray(0)
        override fun shareBackupFile(fileName: String, bytes: ByteArray): Boolean = true
        override suspend fun pickRestoreFile(): String? = null
        override suspend fun restoreBackup(path: String): BackupRestoreResult? = null

        override suspend fun refreshEditingAccess() = Unit
        override fun lockEditingAccess() = Unit
    }
}
