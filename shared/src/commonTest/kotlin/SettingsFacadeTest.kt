import io.github.jdreioe.wingmate.application.DefaultEditingAccessStore
import io.github.jdreioe.wingmate.application.EditingAccessController
import io.github.jdreioe.wingmate.application.FeatureUsageReporter
import io.github.jdreioe.wingmate.application.InMemorySecureEditingCredentialStorage
import io.github.jdreioe.wingmate.application.SettingsFacade
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.domain.AacLogger
import io.github.jdreioe.wingmate.domain.SpeechPolicy
import io.github.jdreioe.wingmate.domain.StartupMode
import io.github.jdreioe.wingmate.domain.WordTypeColorScheme
import io.github.jdreioe.wingmate.infrastructure.InMemorySettingsRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsFacadeTest {
    @Test
    fun gridColumnsAreClampedToSupportedRange() = runBlocking {
        val facade = facade()

        facade.updateGridColumns(10)
        facade.updateGridColumns(0)

        assertEquals(1, facade.getSettings().gridColumns)
    }

    @Test
    fun timingSettingsAreClampedToUpperBounds() = runBlocking {
        val facade = facade()

        facade.updateHoldToSelectMillis(10_000)
        facade.updateDwellToSelectMillis(60_000)
        facade.updateSelectionDebounceMillis(60_000)
        facade.updateSelectionHighlightMillis(60_000)

        assertEquals(2_000, facade.getSettings().holdToSelectMillis)
        assertEquals(5_000, facade.getSettings().dwellToSelectMillis)
        assertEquals(1_000, facade.getSettings().selectionDebounceMillis)
        assertEquals(5_000, facade.getSettings().selectionHighlightMillis)
    }

    @Test
    fun invalidEnumNamesFallBackToSafeDefaults() = runBlocking {
        val facade = facade()

        facade.updateWordTypeColorScheme("not-a-scheme")
        facade.updateSpeechPolicy("not-a-policy")
        facade.updatePointerEmphasis("not-a-style", scale = 9f)

        val settings = facade.getSettings()
        assertEquals(WordTypeColorScheme.None, settings.wordTypeColorScheme)
        assertEquals(SpeechPolicy.Immediate, settings.speechPolicy)
        assertEquals(3f, settings.pointerEmphasisScale)
    }

    @Test
    fun validWordTypeColorSchemeIsApplied() = runBlocking {
        val facade = facade()

        facade.updateWordTypeColorScheme(WordTypeColorScheme.Fitzgerald.name)

        assertEquals(WordTypeColorScheme.Fitzgerald, facade.getSettings().wordTypeColorScheme)
    }

    @Test
    fun speechPolicySpeaksSelectionHonorsPolicyAndActivationBehavior() {
        val facade = facade()

        assertTrue(facade.speechPolicySpeaksSelection("Immediate", "SpeakAndAdd"))
        assertTrue(facade.speechPolicySpeaksSelection("Immediate", "SpeakOnly"))
        assertFalse(facade.speechPolicySpeaksSelection("Immediate", "AddOnly"))
        assertFalse(facade.speechPolicySpeaksSelection("SentenceOnly", "SpeakAndAdd"))
        assertTrue(facade.speechPolicySpeaksSelection("not-a-policy", "SpeakAndAdd"))
    }

    @Test
    fun startupModeTogglesAndReadsBackAsScreens() = runBlocking {
        val facade = facade()

        assertFalse(facade.startupUsesScreens())

        facade.updateStartupUsesScreens(true)

        assertTrue(facade.startupUsesScreens())
        assertEquals(StartupMode.Screens, facade.getSettings().startupMode)
        assertTrue(facade.iosSettingsFlags().startupUsesScreens)
    }

    @Test
    fun featureReportersAreToggledWithTheirSettings() = runBlocking {
        val logger = RecordingAacLogger()
        val reporter = RecordingFeatureUsageReporter()
        val facade = facade(logger = logger, reporter = reporter)

        facade.updateUsageLoggingEnabled(true)
        facade.updateFeatureUsageReportingEnabled(true)

        assertEquals(listOf(true), logger.setEnabledCalls)
        assertEquals(listOf(true), reporter.setEnabledCalls)
        assertTrue(facade.getSettings().usageLoggingEnabled)
        assertTrue(facade.getSettings().featureUsageReportingEnabled)
    }

    private fun facade(
        settings: InMemorySettingsRepository = InMemorySettingsRepository(),
        logger: AacLogger = RecordingAacLogger(),
        reporter: FeatureUsageReporter = RecordingFeatureUsageReporter(),
    ): SettingsFacade = SettingsFacade(
        settingsUseCase = SettingsUseCase(settings),
        editingAccess = EditingAccessController(
            DefaultEditingAccessStore(InMemorySecureEditingCredentialStorage())
        ),
        aacLogger = logger,
        featureUsageReporter = reporter,
    )

    private class RecordingAacLogger : AacLogger {
        val setEnabledCalls = mutableListOf<Boolean>()
        override fun logButtonClick(label: String, boardId: String?, phraseId: String?) = Unit
        override fun logSentenceSpeak(sentence: String) = Unit
        override fun setEnabled(enabled: Boolean) {
            setEnabledCalls += enabled
        }
    }

    private class RecordingFeatureUsageReporter : FeatureUsageReporter {
        val setEnabledCalls = mutableListOf<Boolean>()
        override fun setEnabled(enabled: Boolean) {
            setEnabledCalls += enabled
        }
        override fun report(event: String, metadata: Map<String, String>) = Unit
    }
}