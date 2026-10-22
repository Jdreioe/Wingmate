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
import kotlin.coroutines.cancellation.CancellationException

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
 * and services this feature needs. Suspend members throw only [NativeFailure]
 * or coroutine cancellation; settings live on this device, so failures are
 * [NativeFailureKind.Persistence] unless a shared rule rejects the request.
 */
class SettingsFacade(
    private val settingsUseCase: SettingsUseCase,
    private val editingAccess: EditingAccessController,
    private val aacLogger: AacLogger,
    private val featureUsageReporter: FeatureUsageReporter,
) {
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun getSettings(): Settings = nativeCall { settingsUseCase.get() }

    private suspend fun updateSettings(transform: (Settings) -> Settings) {
        nativeCall { settingsUseCase.update(transform(settingsUseCase.get())) }
    }

    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateSecondaryLanguage(lang: String) = updateSettings { it.copy(secondaryLanguage = lang) }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateScanningEnabled(enabled: Boolean) = updateSettings { it.copy(scanningEnabled = enabled) }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateScanPlaybackAreaEnabled(enabled: Boolean) = updateSettings { it.copy(scanPlaybackAreaEnabled = enabled) }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateScanInputFieldEnabled(enabled: Boolean) = updateSettings { it.copy(scanInputFieldEnabled = enabled) }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateScanPhraseGridEnabled(enabled: Boolean) = updateSettings { it.copy(scanPhraseGridEnabled = enabled) }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateScanCategoryItemsEnabled(enabled: Boolean) = updateSettings { it.copy(scanCategoryItemsEnabled = enabled) }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateScanTopBarEnabled(enabled: Boolean) = updateSettings { it.copy(scanTopBarEnabled = enabled) }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateScanPhraseGridOrder(order: String) = updateSettings { it.copy(scanPhraseGridOrder = order) }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateScanDwellTimeSeconds(seconds: Float) = updateSettings { it.copy(scanDwellTimeSeconds = seconds) }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateScanAutoAdvanceSeconds(seconds: Float) = updateSettings { it.copy(scanAutoAdvanceSeconds = seconds) }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateShowLabels(enabled: Boolean) = updateSettings { it.copy(showLabels = enabled) }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateShowSymbols(enabled: Boolean) = updateSettings { it.copy(showSymbols = enabled) }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateLabelAtTop(enabled: Boolean) = updateSettings { it.copy(labelAtTop = enabled) }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateGridColumns(columns: Int) = updateSettings { it.copy(gridColumns = columns.coerceIn(1, 6)) }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateHighContrastMode(enabled: Boolean) = updateSettings { it.copy(highContrastMode = enabled) }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateWordTypeColorScheme(scheme: String) = updateSettings {
        it.copy(wordTypeColorScheme = enumNamed<WordTypeColorScheme>(scheme))
    }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateHoldToSelectMillis(millis: Long) = updateSettings { it.copy(holdToSelectMillis = millis.coerceIn(0, 2_000)) }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateDwellToSelectMillis(millis: Long) = updateSettings { it.copy(dwellToSelectMillis = millis.coerceIn(0, 5_000)) }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateSelectKeyBinding(binding: String) = updateSettings { it.copy(selectKeyBinding = binding) }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateRestModeKeyBinding(binding: String) = updateSettings { it.copy(restModeKeyBinding = binding) }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updatePointerEmphasis(style: String, scale: Float) = updateSettings {
        it.copy(
            pointerEmphasisStyle = enumNamed<PointerEmphasisStyle>(style),
            pointerEmphasisScale = scale.coerceIn(1f, 3f),
        )
    }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateSelectionDebounceMillis(millis: Long) = updateSettings { it.copy(selectionDebounceMillis = millis.coerceIn(0, 1_000)) }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateSelectionSoundEnabled(enabled: Boolean) = updateSettings { it.copy(selectionSoundEnabled = enabled) }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateAuditoryFishingEnabled(enabled: Boolean) = updateSettings { it.copy(auditoryFishingEnabled = enabled) }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateSpeechPolicy(policy: String) = updateSettings {
        it.copy(speechPolicy = enumNamed<SpeechPolicy>(policy))
    }

    /**
     * Whether a single selection should speak immediately, given the global
     * speech policy and the resolved board activation behavior. Sentence-only
     * never speaks during composition; an unknown policy name reads as Immediate.
     */
    fun speechPolicySpeaksSelection(policy: String, behavior: String): Boolean =
        shouldSpeakSelectionImmediately(
            policy = SpeechPolicy.entries.find { it.name == policy } ?: SpeechPolicy.Immediate,
            behavior = behavior.toBoardActivationBehavior(),
        )

    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateBoardShowMessageBar(enabled: Boolean) = updateSettings { it.copy(boardShowMessageBar = enabled) }

    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateBoardShowSpeakButton(enabled: Boolean) = updateSettings { it.copy(boardShowSpeakButton = enabled) }

    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateUsageLoggingEnabled(enabled: Boolean) {
        updateSettings { it.copy(usageLoggingEnabled = enabled) }
        nativeCall { aacLogger.setEnabled(enabled) }
    }

    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateHistoryVisible(visible: Boolean) = updateSettings { it.copy(historyVisible = visible) }

    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateFeatureUsageReportingEnabled(enabled: Boolean) {
        updateSettings { it.copy(featureUsageReportingEnabled = enabled) }
        nativeCall { featureUsageReporter.setEnabled(enabled) }
    }

    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun iosSettingsFlags(): IosSettingsFlags {
        val settings = getSettings()
        return IosSettingsFlags(
            usesSystemTts = settings.ttsEngine == TtsEngine.SYSTEM,
            ttsEngine = settings.ttsEngine.name,
            startupUsesScreens = settings.startupMode == StartupMode.Screens,
        )
    }

    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateStartupUsesScreens(enabled: Boolean) = updateSettings {
        it.copy(startupMode = if (enabled) StartupMode.Screens else StartupMode.Keyboard)
    }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateStartupBoardSetId(id: String?) = updateSettings { it.copy(startupBoardSetId = id) }

    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun editingAccessState(): EditingAccessState = nativeCall { editingAccess.refresh() }

    /**
     * Sets a new Editing access code. Fails with [NativeFailureKind.Validation] when
     * the code is not 4 to 8 digits or editing is still locked behind the old code.
     */
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun configureEditingAccess(code: String) = nativeCall {
        if (!isValidEditingAccessCode(code) || editingAccess.requiresUnlock()) {
            throw NativeFailure(NativeFailureKind.Validation)
        }
        editingAccess.configure(code)
    }

    /** False when the code is wrong; a failure means the code could not be checked. */
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun unlockEditing(code: String): Boolean = nativeCall { editingAccess.unlock(code) }

    /** False when the code is wrong; a failure means access could not be changed. */
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun disableEditingAccess(code: String): Boolean = nativeCall { editingAccess.disable(code) }
    fun lockEditingAccess() = editingAccess.lock()
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun recoverEditingAccess() = nativeCall { editingAccess.recover() }
}

/**
 * The [E] entry named [name]. An unknown name fails with [NativeFailureKind.Validation]
 * so an enum setting update leaves the saved value unchanged.
 */
private inline fun <reified E : Enum<E>> enumNamed(name: String): E =
    enumValues<E>().firstOrNull { it.name == name } ?: throw NativeFailure(NativeFailureKind.Validation)

private fun String.toBoardActivationBehavior(): BoardActivationBehavior =
    when (this) {
        "AddOnly" -> BoardActivationBehavior.AddOnly
        "SpeakOnly" -> BoardActivationBehavior.SpeakOnly
        else -> BoardActivationBehavior.SpeakAndAdd
    }
