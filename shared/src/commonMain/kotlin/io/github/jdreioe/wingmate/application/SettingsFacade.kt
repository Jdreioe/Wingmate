package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.AacLogger
import io.github.jdreioe.wingmate.domain.PointerEmphasisStyle
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SpeechPolicy
import io.github.jdreioe.wingmate.domain.StartupMode
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.WordTypeColorScheme
import io.github.jdreioe.wingmate.domain.obf.BoardActivationBehavior
import io.github.jdreioe.wingmate.domain.obf.shouldSpeakSelectionImmediately

/** Swift-friendly snapshot of the toggle-flag settings the iOS UI uses for parity. */
data class IosSettingsFlags(
    val usesSystemTts: Boolean,
    val ttsEngine: String,
    val startupUsesScreens: Boolean,
)

/**
 * A feature-scoped native boundary around app settings and editing access.
 *
 * Keep Koin out of this file: the constructor receives exactly the use cases
 * and services this feature needs. Failures propagate as suspend exceptions
 * (including coroutine cancellation) instead of being swallowed or converted.
 */
class SettingsFacade(
    private val settingsUseCase: SettingsUseCase,
    private val editingAccess: EditingAccessController,
    private val aacLogger: AacLogger,
    private val featureUsageReporter: FeatureUsageReporter,
) {
    suspend fun getSettings(): Settings = settingsUseCase.get()

    private suspend fun updateSettings(transform: (Settings) -> Settings) {
        settingsUseCase.update(transform(settingsUseCase.get()))
    }

    suspend fun updateSecondaryLanguage(lang: String) = updateSettings { it.copy(secondaryLanguage = lang) }
    suspend fun updateScanningEnabled(enabled: Boolean) = updateSettings { it.copy(scanningEnabled = enabled) }
    suspend fun updateScanPlaybackAreaEnabled(enabled: Boolean) = updateSettings { it.copy(scanPlaybackAreaEnabled = enabled) }
    suspend fun updateScanInputFieldEnabled(enabled: Boolean) = updateSettings { it.copy(scanInputFieldEnabled = enabled) }
    suspend fun updateScanPhraseGridEnabled(enabled: Boolean) = updateSettings { it.copy(scanPhraseGridEnabled = enabled) }
    suspend fun updateScanCategoryItemsEnabled(enabled: Boolean) = updateSettings { it.copy(scanCategoryItemsEnabled = enabled) }
    suspend fun updateScanTopBarEnabled(enabled: Boolean) = updateSettings { it.copy(scanTopBarEnabled = enabled) }
    suspend fun updateScanPhraseGridOrder(order: String) = updateSettings { it.copy(scanPhraseGridOrder = order) }
    suspend fun updateScanDwellTimeSeconds(seconds: Float) = updateSettings { it.copy(scanDwellTimeSeconds = seconds) }
    suspend fun updateScanAutoAdvanceSeconds(seconds: Float) = updateSettings { it.copy(scanAutoAdvanceSeconds = seconds) }
    suspend fun updateShowLabels(enabled: Boolean) = updateSettings { it.copy(showLabels = enabled) }
    suspend fun updateShowSymbols(enabled: Boolean) = updateSettings { it.copy(showSymbols = enabled) }
    suspend fun updateLabelAtTop(enabled: Boolean) = updateSettings { it.copy(labelAtTop = enabled) }
    suspend fun updateGridColumns(columns: Int) = updateSettings { it.copy(gridColumns = columns.coerceIn(1, 6)) }
    suspend fun updateHighContrastMode(enabled: Boolean) = updateSettings { it.copy(highContrastMode = enabled) }
    suspend fun updateWordTypeColorScheme(scheme: String) = updateSettings {
        it.copy(wordTypeColorScheme = runCatching { WordTypeColorScheme.valueOf(scheme) }
            .getOrDefault(WordTypeColorScheme.None))
    }
    suspend fun updateHoldToSelectMillis(millis: Long) = updateSettings { it.copy(holdToSelectMillis = millis.coerceIn(0, 2_000)) }
    suspend fun updateDwellToSelectMillis(millis: Long) = updateSettings { it.copy(dwellToSelectMillis = millis.coerceIn(0, 5_000)) }
    suspend fun updateSelectKeyBinding(binding: String) = updateSettings { it.copy(selectKeyBinding = binding) }
    suspend fun updateRestModeKeyBinding(binding: String) = updateSettings { it.copy(restModeKeyBinding = binding) }
    suspend fun updatePointerEmphasis(style: String, scale: Float) = updateSettings {
        it.copy(
            pointerEmphasisStyle = runCatching { PointerEmphasisStyle.valueOf(style) }.getOrDefault(PointerEmphasisStyle.System),
            pointerEmphasisScale = scale.coerceIn(1f, 3f),
        )
    }
    suspend fun updateSelectionDebounceMillis(millis: Long) = updateSettings { it.copy(selectionDebounceMillis = millis.coerceIn(0, 1_000)) }
    suspend fun updateSelectionSoundEnabled(enabled: Boolean) = updateSettings { it.copy(selectionSoundEnabled = enabled) }
    suspend fun updateAuditoryFishingEnabled(enabled: Boolean) = updateSettings { it.copy(auditoryFishingEnabled = enabled) }
    suspend fun updateSelectionHighlightMillis(millis: Long) = updateSettings { it.copy(selectionHighlightMillis = millis.coerceIn(0, 5_000)) }
    suspend fun updateSpeechPolicy(policy: String) = updateSettings {
        it.copy(speechPolicy = runCatching { SpeechPolicy.valueOf(policy) }.getOrDefault(SpeechPolicy.Immediate))
    }

    /**
     * Whether a single selection should speak immediately, given the global
     * speech policy and the resolved board activation behavior. Sentence-only
     * never speaks during composition.
     */
    fun speechPolicySpeaksSelection(policy: String, behavior: String): Boolean =
        shouldSpeakSelectionImmediately(
            policy = runCatching { SpeechPolicy.valueOf(policy) }.getOrDefault(SpeechPolicy.Immediate),
            behavior = behavior.toBoardActivationBehavior(),
        )

    suspend fun updateBoardShowMessageBar(enabled: Boolean) = updateSettings { it.copy(boardShowMessageBar = enabled) }

    suspend fun updateBoardShowSpeakButton(enabled: Boolean) = updateSettings { it.copy(boardShowSpeakButton = enabled) }

    suspend fun updateUsageLoggingEnabled(enabled: Boolean) {
        updateSettings { it.copy(usageLoggingEnabled = enabled) }
        aacLogger.setEnabled(enabled)
    }

    suspend fun updateHistoryVisible(visible: Boolean) = updateSettings { it.copy(historyVisible = visible) }

    suspend fun updateFeatureUsageReportingEnabled(enabled: Boolean) {
        updateSettings { it.copy(featureUsageReportingEnabled = enabled) }
        featureUsageReporter.setEnabled(enabled)
    }

    suspend fun startupUsesScreens(): Boolean = settingsUseCase.get().startupMode == StartupMode.Screens

    suspend fun iosSettingsFlags(): IosSettingsFlags {
        val settings = settingsUseCase.get()
        return IosSettingsFlags(
            usesSystemTts = settings.ttsEngine == TtsEngine.SYSTEM,
            ttsEngine = settings.ttsEngine.name,
            startupUsesScreens = settings.startupMode == StartupMode.Screens,
        )
    }

    suspend fun updateStartupUsesScreens(enabled: Boolean) = updateSettings {
        it.copy(startupMode = if (enabled) StartupMode.Screens else StartupMode.Keyboard)
    }
    suspend fun updateStartupBoardSetId(id: String?) = updateSettings { it.copy(startupBoardSetId = id) }

    suspend fun editingAccessState(): EditingAccessState = editingAccess.refresh()
    suspend fun configureEditingAccess(code: String) = editingAccess.configure(code)
    suspend fun unlockEditing(code: String): Boolean = editingAccess.unlock(code)
    suspend fun disableEditingAccess(code: String): Boolean = editingAccess.disable(code)
    fun lockEditingAccess() = editingAccess.lock()
    suspend fun recoverEditingAccess() = editingAccess.recover()
}

private fun String.toBoardActivationBehavior(): BoardActivationBehavior =
    when (this) {
        "AddOnly" -> BoardActivationBehavior.AddOnly
        "SpeakOnly" -> BoardActivationBehavior.SpeakOnly
        else -> BoardActivationBehavior.SpeakAndAdd
    }
