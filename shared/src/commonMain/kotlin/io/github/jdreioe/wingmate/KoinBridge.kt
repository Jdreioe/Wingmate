package io.github.jdreioe.wingmate

import io.github.jdreioe.wingmate.application.SelectionHighlight
import io.github.jdreioe.wingmate.application.AccessInputController
import io.github.jdreioe.wingmate.application.AccessInputEffect
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.application.BoardSetUseCase
import io.github.jdreioe.wingmate.application.KeyboardPreset
import io.github.jdreioe.wingmate.application.EditingAccessController
import io.github.jdreioe.wingmate.application.CompleteBackupManager
import io.github.jdreioe.wingmate.application.BackupRestoreResult
import io.github.jdreioe.wingmate.platform.ShareService
import io.github.jdreioe.wingmate.application.BoardSetSpeechCacheUseCase
import io.github.jdreioe.wingmate.application.bloc.PhraseListStore
import io.github.jdreioe.wingmate.di.appModule
import io.github.jdreioe.wingmate.initKoin
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.domain.SpeechServiceConfigStatus
import io.github.jdreioe.wingmate.domain.SpeechTextProcessor
import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SpeechPolicy
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.PointerEmphasisStyle
import io.github.jdreioe.wingmate.domain.WordTypeColorScheme
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import io.github.jdreioe.wingmate.domain.TextEditResult
import io.github.jdreioe.wingmate.domain.TextEditingPolicy
import io.github.jdreioe.wingmate.domain.TextSpan
import io.github.jdreioe.wingmate.domain.loggingClassName
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import io.github.jdreioe.wingmate.domain.obf.ObfLoadBoard
import io.github.jdreioe.wingmate.domain.obf.ObfKeyboardLayout
import io.github.jdreioe.wingmate.domain.obf.WordType
import io.github.jdreioe.wingmate.domain.obf.resolvedBackgroundColor
import io.github.jdreioe.wingmate.domain.obf.wordType
import io.github.jdreioe.wingmate.domain.obf.withWordType
import io.github.jdreioe.wingmate.domain.obf.BoardSettingsOverrides
import io.github.jdreioe.wingmate.domain.obf.BoardActivationBehavior
import io.github.jdreioe.wingmate.domain.obf.BoardReturnBehavior
import io.github.jdreioe.wingmate.domain.obf.resolveBoardSettings
import io.github.jdreioe.wingmate.domain.obf.pageSettingsOverrides
import io.github.jdreioe.wingmate.domain.obf.resolveObfLocalizedString
import io.github.jdreioe.wingmate.domain.obf.fieldItems
import io.github.jdreioe.wingmate.domain.obf.availableFieldSpansAt
import io.github.jdreioe.wingmate.domain.obf.withFieldSpan
import io.github.jdreioe.wingmate.domain.obf.nGramPredictionInsertion
import io.github.jdreioe.wingmate.domain.obf.joinSentenceText
import io.github.jdreioe.wingmate.domain.obf.buttonSpeechPart
import io.github.jdreioe.wingmate.domain.obf.shouldAddBoardSelection
import io.github.jdreioe.wingmate.domain.obf.shouldSpeakBoardSelection
import io.github.jdreioe.wingmate.domain.obf.shouldSpeakSelectionImmediately
import io.github.jdreioe.wingmate.domain.obf.applyBoardReturnBehavior
import io.github.jdreioe.wingmate.domain.obf.buildResolvedSentence
import io.github.jdreioe.wingmate.domain.obf.backspaceSentenceSelection
import io.github.jdreioe.wingmate.domain.BoardRepository
import io.github.jdreioe.wingmate.domain.BoardSetRepository
import io.github.jdreioe.wingmate.infrastructure.OpenSymbolsClient
import io.github.jdreioe.wingmate.infrastructure.SymbolSearchClient
import io.github.jdreioe.wingmate.infrastructure.QuickCorePresetService
import io.github.jdreioe.wingmate.infrastructure.BoardImportResult
import io.github.jdreioe.wingmate.infrastructure.AzureSpeechEndpoint
import io.github.jdreioe.wingmate.infrastructure.AzureSpeechEndpointResult
import kotlin.time.Clock
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

data class IosAccessInputResult(
    val activationTargetId: String?,
    val isPaused: Boolean,
    val currentTargetId: String?,
    val dwellProgress: Float,
)

class KoinBridge : KoinComponent {
    private val accessInput = AccessInputController()
    fun phraseListStore(): PhraseListStore = get()
    // Safe variant to avoid throwing across Swift bridge
    fun phraseListStoreOrNull(): PhraseListStore? = try { get<PhraseListStore>() } catch (_: Throwable) { null }

    // --- Shared native text-editing policy ---
    fun mergeTextSpans(spans: List<TextSpan>, textLength: Int): List<TextSpan> =
        TextEditingPolicy.merge(spans, textLength)

    fun addTextSpan(spans: List<TextSpan>, span: TextSpan, textLength: Int): List<TextSpan> =
        TextEditingPolicy.merge(spans + span, textLength)

    fun adjustTextSpansForReplacement(
        textLength: Int,
        edit: TextSpan,
        replacementLength: Int,
        spans: List<TextSpan>,
    ): List<TextSpan> = TextEditingPolicy.adjustForReplacement(textLength, edit, replacementLength, spans)

    fun completePredictedWord(text: String, cursor: Int, suggestion: String): TextEditResult =
        TextEditingPolicy.completeWord(text, cursor, suggestion)

    fun insertPredictedText(text: String, cursor: Int, value: String): TextEditResult =
        TextEditingPolicy.insert(text, cursor, value)

    // --- Sharing helpers ---
    fun shareAudio(path: String) {
        try {
            get<io.github.jdreioe.wingmate.platform.ShareService>().shareAudio(path)
        } catch (_: Throwable) {}
    }

    fun copyAudio(path: String) {
        try {
            get<io.github.jdreioe.wingmate.platform.AudioClipboard>().copyAudioFile(path)
        } catch (_: Throwable) {}
    }

    // --- Simple bridging helpers for Swift UI ---
    /** Split text into speech segments honoring shorthand SSML pauses and language tags. */
    fun processSpeechText(text: String): List<SpeechSegment> = SpeechTextProcessor.processText(text)

    suspend fun speak(text: String) {
        try {
            get<SpeechService>().speak(text)
        } catch (t: Throwable) {
            OperationalLogger.warn("swift_bridge.speak", "failed", exceptionClass = t.loggingClassName())
        }
    }

    suspend fun speakBoardSentence(text: String, cacheAudio: Boolean) {
        try {
            get<SpeechService>().speakWithCachePolicy(text = text, cacheAudio = cacheAudio)
        } catch (t: Throwable) {
            OperationalLogger.warn("swift_bridge.speak_board_sentence", "failed", exceptionClass = t.loggingClassName())
        }
    }

    suspend fun pause() {
        try {
            get<SpeechService>().pause()
        } catch (t: Throwable) {
            OperationalLogger.warn("swift_bridge.pause", "failed", exceptionClass = t.loggingClassName())
        }
    }

    suspend fun stop() {
        try {
            get<SpeechService>().stop()
        } catch (t: Throwable) {
            OperationalLogger.warn("swift_bridge.stop", "failed", exceptionClass = t.loggingClassName())
        }
    }

    suspend fun selectVoiceAndMaybeUpdatePrimary(voice: Voice) {
        val voiceUseCase: VoiceUseCase = get()
        OperationalLogger.debug("voice_selection.update", "started")
        voiceUseCase.select(voice)

        // Optionally align Settings.primaryLanguage with selected voice
        val settingsUseCase: SettingsUseCase = get()
        val current = settingsUseCase.get()
        val candidate = voice.selectedLanguage
            .takeIf { it.isNotEmpty() }
            ?: voice.primaryLanguage?.takeIf { it.isNotEmpty() }
            ?: current.primaryLanguage
        if (candidate != current.primaryLanguage) {
            settingsUseCase.update(current.copy(primaryLanguage = candidate))
        }
    }

    suspend fun updatePrimaryLanguage(lang: String) {
        val settingsUseCase: SettingsUseCase = get()
        val current = settingsUseCase.get()
        if (lang != current.primaryLanguage) {
            settingsUseCase.update(current.copy(primaryLanguage = lang))
        }
    }

    suspend fun getSettings(): Settings = get<SettingsUseCase>().get()

    private suspend fun updateSettings(transform: (Settings) -> Settings) {
        val useCase = get<SettingsUseCase>()
        useCase.update(transform(useCase.get()))
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
    suspend fun usesSystemTts(): Boolean = get<SettingsUseCase>().get().ttsEngine == TtsEngine.SYSTEM
    suspend fun updateUseSystemTts(enabled: Boolean) = updateSettings {
        it.copy(ttsEngine = if (enabled) TtsEngine.SYSTEM else TtsEngine.AZURE_USER_RESOURCE)
    }
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
            behavior = behavior.toBoardActivationBehavior()
        )

    fun accessInputEnter(targetId: String): IosAccessInputResult {
        accessInput.targetEntered(targetId, nowMillis())
        return accessResult(null)
    }

    fun accessInputExit(targetId: String): IosAccessInputResult {
        accessInput.targetExited(targetId, nowMillis())
        return accessResult(null)
    }

    fun accessInputFocus(targetId: String): IosAccessInputResult {
        accessInput.targetFocused(targetId, nowMillis())
        return accessResult(null)
    }

    fun accessInputBlur(targetId: String): IosAccessInputResult {
        accessInput.targetBlurred(targetId, nowMillis())
        return accessResult(null)
    }

    fun accessInputKeyDown(key: String, selectBinding: String, restBinding: String): IosAccessInputResult =
        accessResult(accessInput.keyDown(key, selectBinding, restBinding, nowMillis()))

    fun accessInputKeyUp(key: String): IosAccessInputResult {
        accessInput.keyUp(key)
        return accessResult(null)
    }

    fun accessInputTick(dwellMillis: Long): IosAccessInputResult =
        accessResult(accessInput.tick(nowMillis(), dwellMillis))

    fun accessInputTogglePause(): IosAccessInputResult = accessResult(accessInput.togglePaused(nowMillis()))

    private fun accessResult(effect: AccessInputEffect?): IosAccessInputResult = IosAccessInputResult(
        activationTargetId = (effect as? AccessInputEffect.Activate)?.targetId,
        isPaused = accessInput.state.isPaused,
        currentTargetId = accessInput.state.currentTargetId,
        dwellProgress = accessInput.state.dwellProgress,
    )
    suspend fun updateBoardShowMessageBar(enabled: Boolean) = updateSettings { it.copy(boardShowMessageBar = enabled) }

    private val selectionHighlight = SelectionHighlight()

    /** Record a selection for visual highlight; immediately ends the previous highlight. */
    fun selectionHighlightActivate(buttonId: String) {
        selectionHighlight.activate(buttonId, nowMillis())
    }

    /** Clear any active selection highlight. */
    fun selectionHighlightClear() {
        selectionHighlight.clear()
    }

    /**
     * The currently highlighted button id for the given duration, or null when the
     * highlight has expired or is disabled by a non-positive [durationMillis].
     */
    fun selectionHighlightButtonId(durationMillis: Long): String? =
        selectionHighlight.highlightedTarget(nowMillis(), durationMillis)

    private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
    suspend fun updateUsageLoggingEnabled(enabled: Boolean) {
        updateSettings { it.copy(usageLoggingEnabled = enabled) }
        runCatching { get<io.github.jdreioe.wingmate.domain.AacLogger>().setEnabled(enabled) }
    }
    suspend fun updateHistoryVisible(visible: Boolean) = updateSettings { it.copy(historyVisible = visible) }
    suspend fun updateFeatureUsageReportingEnabled(enabled: Boolean) {
        updateSettings { it.copy(featureUsageReportingEnabled = enabled) }
        runCatching { get<io.github.jdreioe.wingmate.application.FeatureUsageReporter>().setEnabled(enabled) }
    }
    suspend fun startupUsesScreens(): Boolean = get<SettingsUseCase>().get().startupMode == io.github.jdreioe.wingmate.domain.StartupMode.Screens
    suspend fun iosSettingsFlags(): IosSettingsFlags {
        val settings = get<SettingsUseCase>().get()
        return IosSettingsFlags(
            usesSystemTts = settings.ttsEngine == TtsEngine.SYSTEM,
            startupUsesScreens = settings.startupMode == io.github.jdreioe.wingmate.domain.StartupMode.Screens
        )
    }
    suspend fun updateStartupUsesScreens(enabled: Boolean) = updateSettings {
        it.copy(startupMode = if (enabled) io.github.jdreioe.wingmate.domain.StartupMode.Screens else io.github.jdreioe.wingmate.domain.StartupMode.Keyboard)
    }
    suspend fun updateStartupBoardSetId(id: String?) = updateSettings { it.copy(startupBoardSetId = id) }

    // Update both the selected voice's selectedLanguage and the app Settings.primaryLanguage
    suspend fun updateSelectedVoiceLanguage(lang: String) {
        val voiceUseCase: VoiceUseCase = get()
        val settingsUseCase: SettingsUseCase = get()

        // Update selected voice, if any
        val selected = voiceUseCase.selected()
        if (selected != null && selected.selectedLanguage != lang) {
            voiceUseCase.select(selected.copy(selectedLanguage = lang))
        }
        // Align settings primary language
        val current = settingsUseCase.get()
        if (lang != current.primaryLanguage) {
            settingsUseCase.update(current.copy(primaryLanguage = lang))
        }
    }

    suspend fun selectedVoice(): Voice? = get<VoiceUseCase>().selected()

    // Debug helper: return the runtime class name of the bound VoiceRepository
    fun debugVoiceRepositoryName(): String = try { get<io.github.jdreioe.wingmate.domain.VoiceRepository>()::class.simpleName ?: "unknown" } catch (_: Throwable) { "error" }

    suspend fun listVoices(): List<Voice> = get<VoiceUseCase>().list()

    suspend fun refreshVoicesFromAzure(): List<Voice> = get<VoiceUseCase>().refreshFromAzure()

    /** Safe for native UI: deliberately never returns the saved subscription key. */
    suspend fun getSpeechConfig(): SpeechServiceConfigStatus = get<ConfigRepository>().getSpeechConfigStatus()

    suspend fun saveSpeechConfig(config: SpeechServiceConfig) {
        get<ConfigRepository>().saveSpeechConfig(config)
    }

    fun isValidAzureSpeechEndpoint(endpoint: String): Boolean =
        AzureSpeechEndpoint.parse(endpoint) is AzureSpeechEndpointResult.Valid

    suspend fun clearSpeechConfig() {
        get<ConfigRepository>().clearSpeechConfig()
    }

    suspend fun saveAzureSpeechConfig(endpoint: String, subscriptionKey: String) {
        get<ConfigRepository>().saveSpeechConfig(SpeechServiceConfig(endpoint.trim(), subscriptionKey.trim()))
        val settingsUseCase: SettingsUseCase = get()
        val settings = runCatching { settingsUseCase.get() }.getOrDefault(Settings())
        settingsUseCase.update(settings.copy(ttsEngine = TtsEngine.AZURE_USER_RESOURCE))
    }

    // Swift-friendly bridge to update phrase recording path
    fun updatePhraseRecording(phraseId: String, recordingPath: String?) {
        try {
            phraseListStore().accept(PhraseListStore.Intent.UpdatePhraseRecording(id = phraseId, recordingPath = recordingPath))
        } catch (t: Throwable) {
            OperationalLogger.warn("swift_bridge.phrase_recording_update", "failed", exceptionClass = t.loggingClassName())
        }
    }

    // --- BoardSet helpers ---
    suspend fun listBoardSets(): List<ObfBoardSet> = get<BoardSetUseCase>().listBoardSets()
    suspend fun getBoardSet(id: String): ObfBoardSet? = get<BoardSetUseCase>().getBoardSet(id)
    suspend fun deleteBoardSet(id: String) { get<BoardSetUseCase>().deleteBoardSet(id) }
    suspend fun duplicateBoardSet(id: String): ObfBoardSet? = get<BoardSetUseCase>().duplicateBoardSet(id)
    suspend fun toggleBoardSetLocked(id: String): ObfBoardSet? = get<BoardSetUseCase>().toggleLocked(id)
    suspend fun updateBoardSetSentenceCaching(id: String, enabled: Boolean): ObfBoardSet? {
        return get<BoardSetUseCase>().setSentenceCaching(id, enabled)
    }
    suspend fun cacheAllBoardSetFields() = get<BoardSetSpeechCacheUseCase>().cacheAll()
    suspend fun retryBoardSetSpeechCaching() = get<BoardSetSpeechCacheUseCase>().retryPending()
    fun updateBoardSetSpeechCacheOnline(online: Boolean) = get<BoardSetSpeechCacheUseCase>().setOnline(online)
    suspend fun touchBoardSet(id: String): ObfBoardSet? = get<BoardSetUseCase>().touchBoardSet(id)
    suspend fun createBoardSet(name: String, rows: Int, columns: Int): ObfBoardSet = get<BoardSetUseCase>().createBoardSet(name, rows, columns)
    suspend fun createKeyboardBoardSet(name: String, preset: String): ObfBoardSet =
        get<BoardSetUseCase>().createKeyboardBoardSet(
            name,
            if (preset.equals("alphabetical", ignoreCase = true)) KeyboardPreset.Alphabetical else KeyboardPreset.Qwerty
        )
    suspend fun importQuickCorePreset(slug: String, name: String): ObfBoardSet? {
        val imported = get<QuickCorePresetService>().importPreset(slug) as? BoardImportResult.Success
            ?: return null
        return get<BoardSetUseCase>().renameBoardSet(imported.boardSet.id, name.trim()) ?: imported.boardSet
    }
    fun quickCoreProgress(): IosQuickCoreProgress {
        val progress = get<QuickCorePresetService>().progress.value
        return IosQuickCoreProgress(progress.stage, progress.downloadedBytes, progress.totalBytes, progress.fraction)
    }
    suspend fun createBoard(boardSetId: String, name: String, rows: Int, columns: Int): ObfBoard? =
        get<BoardSetUseCase>().createBoard(boardSetId, name, rows, columns)
    suspend fun createKeyboardBoard(
        boardSetId: String,
        name: String,
        rows: Int,
        columns: Int,
        layout: String
    ): ObfBoard? = get<BoardSetUseCase>().createBoard(
        boardSetId,
        name,
        rows,
        columns,
        ObfKeyboardLayout.entries.firstOrNull { it.wireValue == layout } ?: ObfKeyboardLayout.Qwerty
    )
    suspend fun renameBoardSet(boardSetId: String, name: String): ObfBoardSet? =
        get<BoardSetUseCase>().renameBoardSet(boardSetId, name)
    suspend fun renameBoard(boardSetId: String, boardId: String, name: String): ObfBoard? =
        get<BoardSetUseCase>().renameBoard(boardSetId, boardId, name)
    suspend fun resizeBoard(boardSetId: String, boardId: String, rows: Int, columns: Int): ObfBoard? =
        get<BoardSetUseCase>().resizeBoard(boardSetId, boardId, rows, columns)
    suspend fun setRootBoard(boardSetId: String, boardId: String): ObfBoardSet? =
        get<BoardSetUseCase>().setRootBoard(boardSetId, boardId)
    suspend fun deleteBoard(boardSetId: String, boardId: String): ObfBoardSet? =
        get<BoardSetUseCase>().deleteBoard(boardSetId, boardId)
    suspend fun exportBoardSetAsObz(id: String): ByteArray? = get<BoardSetUseCase>().exportBoardSetAsObz(id)

    suspend fun shareBoardSetAsObz(id: String): IosBoardSetExportResult {
        val useCase = get<BoardSetUseCase>()
        val boardSet = useCase.getBoardSet(id)
            ?: return IosBoardSetExportResult(success = false, fileName = null, message = "Board set not found")
        return when (val export = useCase.exportBoardSetAsObzResult(id)) {
            is io.github.jdreioe.wingmate.application.ObzExportResult.Success -> {
                val fileName = "${boardSet.name}.obz"
                val shared = runCatching { get<io.github.jdreioe.wingmate.platform.ShareService>().shareFile(fileName, export.bytes) }
                    .getOrDefault(false)
                if (shared) {
                    IosBoardSetExportResult(success = true, fileName = fileName, message = "Exported $fileName")
                } else {
                    IosBoardSetExportResult(success = false, fileName = fileName, message = "Export cancelled")
                }
            }
            is io.github.jdreioe.wingmate.application.ObzExportResult.Failure -> {
                val resources = export.resources.takeIf { it.isNotEmpty() }?.joinToString(prefix = ": ")
                IosBoardSetExportResult(success = false, fileName = null, message = "Export failed: ${export.context}$resources")
            }
        }
    }

    // --- Swift-friendly board helpers ---
    suspend fun getBoard(id: String): ObfBoard? = get<BoardRepository>().getBoard(id)

    /**
     * Resolve the effective board settings for the given board id, applying app-level
     * defaults, then screen overrides, then page overrides (shared with Android).
     */
    suspend fun resolveBoardSettings(boardId: String): IosResolvedBoardSettings {
        val settings = get<SettingsUseCase>().get()
        val repository = get<BoardRepository>()
        val board = repository.getBoard(boardId)
        val screenOverrides = getBoardSetForBoard(boardId)?.screenSettings ?: BoardSettingsOverrides()
        val pageOverrides = board?.pageSettingsOverrides() ?: BoardSettingsOverrides()
        val resolved = resolveBoardSettings(
            appShowLabels = settings.showLabels,
            appShowSymbols = settings.showSymbols,
            appLabelAtTop = settings.labelAtTop,
            appShowMessageBar = settings.boardShowMessageBar,
            appActivationBehavior = settings.boardActivationBehavior,
            appReturnBehavior = settings.boardReturnBehavior,
            screen = screenOverrides,
            page = pageOverrides
        )
        return IosResolvedBoardSettings(
            showLabels = resolved.showLabels,
            showSymbols = resolved.showSymbols,
            labelAtTop = resolved.labelAtTop,
            showMessageBar = resolved.showMessageBar,
            activationBehavior = resolved.activationBehavior.name,
            returnBehavior = resolved.returnBehavior.name
        )
    }

    private suspend fun getBoardSetForBoard(boardId: String): ObfBoardSet? =
        get<BoardSetRepository>().listBoardSets().firstOrNull { set -> set.boardIds.contains(boardId) }

    fun boardKeyboardLayout(board: ObfBoard): String? = board.keyboardLayout?.wireValue

    fun boardUsesSpellingMode(board: ObfBoard): Boolean = board.spellingMode

    suspend fun saveBoard(board: ObfBoard): Boolean = runCatching {
        get<BoardRepository>().saveBoard(board)
        true
    }.getOrDefault(false)

    suspend fun createEmptyBoard(name: String, rows: Int, columns: Int, locale: String): ObfBoard? = runCatching {
        val board = ObfBoard(
            format = "open-board-0.1",
            id = "board-${kotlin.random.Random.nextLong().toString().replace('-', '0')}",
            locale = locale,
            name = name,
            grid = ObfGrid(rows.coerceAtLeast(1), columns.coerceAtLeast(1), List(rows.coerceAtLeast(1)) { List(columns.coerceAtLeast(1)) { null } })
        )
        get<BoardRepository>().saveBoard(board)
        board
    }.getOrNull()

    suspend fun listBoardCells(boardId: String): List<IosBoardCell> {
        val board = get<BoardRepository>().getBoard(boardId) ?: return emptyList()
        val grid = board.grid ?: return emptyList()
        val buttons = board.buttons.associateBy { it.id }
        val images = board.images.associateBy { it.id }
        val settings = get<SettingsUseCase>().get()
        val locale = settings.primaryLanguage
        return grid.order.flatMapIndexed { row, columns ->
            columns.mapIndexedNotNull { col, buttonId ->
                val id = buttonId ?: return@mapIndexedNotNull null
                val button = buttons[id] ?: return@mapIndexedNotNull null
                val localizedLabel = resolveObfLocalizedString(board.strings, locale, button.label)
                IosBoardCell(
                    row, col, id,
                    localizedLabel,
                    resolveObfLocalizedString(board.strings, locale, button.vocalization),
                    button.backgroundColor,
                    button.resolvedBackgroundColor(
                        settings.wordTypeColorScheme,
                        board.locale ?: locale,
                        localizedLabel,
                    ),
                    button.wordType?.wireValue,
                    button.borderColor, button.loadBoard?.id,
                    button.imageId, button.imageId?.let { images[it]?.url }, button.hidden,
                    button.resolvedActions(),
                    button.soundId,
                    board.sounds.firstOrNull { it.id == button.soundId }?.let { sound ->
                        sound.dataUrl ?: sound.data?.let { "data:audio;base64,$it" } ?: sound.url
                    },
                    button.shape.wireValue
                )
            }
        }
    }

    // --- Grid span / merge operations (shared with Android via core/domain) ---
    suspend fun listBoardFieldItems(boardId: String): List<IosBoardFieldItem> {
        val board = get<BoardRepository>().getBoard(boardId) ?: return emptyList()
        val grid = board.grid ?: return emptyList()
        return grid.fieldItems().map { field ->
            IosBoardFieldItem(
                row = field.row,
                column = field.column,
                rowSpan = field.rowSpan,
                columnSpan = field.columnSpan,
                buttonId = field.buttonId
            )
        }
    }

    suspend fun availableFieldSpans(boardId: String, row: Int, col: Int): List<IosGridFieldSpan> {
        val board = get<BoardRepository>().getBoard(boardId) ?: return emptyList()
        val grid = board.grid ?: return emptyList()
        return grid.availableFieldSpansAt(row, col).map { span ->
            IosGridFieldSpan(rows = span.rows, columns = span.columns)
        }
    }

    /**
     * Grow/shrink the field at [row], [col] to [rowSpan] x [columnSpan]. Returns
     * true on success (persisted via the repository).
     */
    suspend fun resizeBoardField(boardId: String, row: Int, col: Int, rowSpan: Int, columnSpan: Int): Boolean {
        val repo = get<BoardRepository>()
        val board = repo.getBoard(boardId) ?: return false
        val grid = board.grid ?: return false
        val buttonId = grid.order.getOrNull(row)?.getOrNull(col) ?: return false
        val resized = grid.withFieldSpan(row, col, buttonId, rowSpan, columnSpan) ?: return false
        if (resized == grid) return false
        repo.saveBoard(board.copy(grid = resized))
        return true
    }

    // --- Shared board-session logic (same behavior as Android/Linux) ---
    fun nGramPredictionInsertion(sentence: String, suggestion: String): String =
        io.github.jdreioe.wingmate.domain.obf.nGramPredictionInsertion(sentence, suggestion)

    fun boardShouldAddSelection(behavior: String): Boolean =
        shouldAddBoardSelection(behavior.toBoardActivationBehavior())

    fun boardShouldSpeakSelection(behavior: String): Boolean =
        shouldSpeakBoardSelection(behavior.toBoardActivationBehavior())

    fun boardReturnBehavior(
        behavior: String,
        currentBoardId: String?,
        boardStack: List<String>,
        rootBoardId: String
    ): IosBoardReturnResult {
        val (boardId, stack) = applyBoardReturnBehavior(
            behavior.toBoardReturnBehavior(), currentBoardId, boardStack, rootBoardId
        )
        return IosBoardReturnResult(boardId = boardId, boardStack = stack)
    }

    fun boardBackspaceSentence(texts: List<String>, spellingMode: Boolean): List<String> =
        backspaceSentenceSelection(texts, spellingMode)

    fun boardButtonIsVisible(hidden: Boolean, isEditMode: Boolean, showHiddenButtons: Boolean): Boolean =
        !hidden || isEditMode || showHiddenButtons

    fun boardFieldFontScale(rowSpan: Int, columnSpan: Int): Float =
        io.github.jdreioe.wingmate.domain.obf.fieldFontScale(rowSpan, columnSpan)

    fun boardJoinSentenceText(tokens: List<String>, spellingMode: Boolean): String =
        joinSentenceText(tokens, spellingMode)

    suspend fun boardButtonSpeechPart(boardId: String, buttonId: String, textOverride: String?): IosButtonSpeechPart? {
        val board = get<BoardRepository>().getBoard(boardId) ?: return null
        val button = board.buttons.firstOrNull { it.id == buttonId } ?: return null
        val part = board.buttonSpeechPart(button, get<SettingsUseCase>().get().primaryLanguage) ?: return null
        return IosButtonSpeechPart(
            text = textOverride ?: part.text,
            language = part.language,
            recordingPath = part.recordingPath,
            mathMode = part.mathMode
        )
    }

    suspend fun upsertBoardCellButton(
        boardId: String, row: Int, col: Int, label: String?, vocalization: String?,
        backgroundColor: String?, borderColor: String?, linkedBoardId: String?,
        imageUrl: String?, clearImage: Boolean, actions: List<String>, wordType: String?
    ): ObfBoard? {
        val repo = get<BoardRepository>()
        val board = repo.getBoard(boardId) ?: return null
        val grid = board.grid ?: return null
        if (row !in 0 until grid.rows || col !in 0 until grid.columns) return null
        val existingId = grid.order[row][col]
        val existing = board.buttons.firstOrNull { it.id == existingId }
        val buttonId = existingId ?: "btn-${kotlin.random.Random.nextLong().toString().replace('-', '0')}"
        var imageId = if (clearImage) null else existing?.imageId
        var images = board.images
        if (!imageUrl.isNullOrBlank()) {
            imageId = imageId ?: "img-${kotlin.random.Random.nextLong().toString().replace('-', '0')}"
            val image = ObfImage(id = imageId, url = imageUrl)
            images = images.filterNot { it.id == imageId } + image
        }
        val button = (existing ?: ObfButton(id = buttonId)).copy(
            label = label, vocalization = vocalization,
            imageId = imageId, backgroundColor = backgroundColor, borderColor = borderColor,
            loadBoard = linkedBoardId?.let { ObfLoadBoard(id = it) },
            action = actions.singleOrNull(),
            actions = if (actions.size > 1) actions else emptyList()
        ).withWordType(wordType?.let { value -> WordType.entries.firstOrNull { it.wireValue == value } })
        val buttons = board.buttons.filterNot { it.id == buttonId } + button
        val order = grid.order.mapIndexed { r, columns ->
            columns.mapIndexed { c, id -> if (r == row && c == col) buttonId else id }
        }
        return board.copy(buttons = buttons, images = images, grid = grid.copy(order = order)).also {
            repo.saveBoard(it)
            get<BoardSetSpeechCacheUseCase>().cacheField(it, button)
        }
    }

    suspend fun clearBoardCellButton(boardId: String, row: Int, col: Int): ObfBoard? {
        val repo = get<BoardRepository>()
        val board = repo.getBoard(boardId) ?: return null
        val grid = board.grid ?: return null
        if (row !in 0 until grid.rows || col !in 0 until grid.columns) return null
        val removedId = grid.order[row][col]
        val order = grid.order.mapIndexed { r, columns ->
            columns.mapIndexed { c, id -> if (r == row && c == col) null else id }
        }
        val stillUsed = order.flatten().toSet()
        val buttons = board.buttons.filter { it.id != removedId || it.id in stillUsed }
        val usedImages = buttons.mapNotNull { it.imageId }.toSet()
        return board.copy(buttons = buttons, images = board.images.filter { it.id in usedImages }, grid = grid.copy(order = order))
            .also { repo.saveBoard(it) }
    }

    suspend fun setBoardBackgroundColor(
        boardSetId: String,
        boardId: String,
        backgroundColor: String?
    ): ObfBoard? = runCatching {
        val repo = get<BoardRepository>()
        val board = repo.getBoard(boardId) ?: return@runCatching null
        val updated = board.copy(backgroundColor = backgroundColor?.trim()?.takeIf(String::isNotEmpty))
        repo.saveBoard(updated)
        get<BoardSetUseCase>().touchBoardSet(boardSetId)
        updated
    }.getOrNull()

    suspend fun editingAccessState(): io.github.jdreioe.wingmate.application.EditingAccessState =
        get<EditingAccessController>().refresh()

    suspend fun configureEditingAccess(code: String) = get<EditingAccessController>().configure(code)

    suspend fun unlockEditing(code: String): Boolean = get<EditingAccessController>().unlock(code)

    suspend fun disableEditingAccess(code: String): Boolean = get<EditingAccessController>().disable(code)

    fun lockEditingAccess() = get<EditingAccessController>().lock()

    suspend fun recoverEditingAccess() = get<EditingAccessController>().recover()

    suspend fun shareCompleteBackup(): Boolean {
        val bytes = get<CompleteBackupManager>().exportBackup()
        return get<ShareService>().shareFile("wingmate-backup.wingmate-backup", bytes)
    }

    suspend fun restoreCompleteBackup(path: String): String? =
        when (val result = get<CompleteBackupManager>().restoreBackup(path)) {
            is BackupRestoreResult.Success -> {
                phraseListStoreOrNull()?.accept(PhraseListStore.Intent.Refresh)
                null
            }
            is BackupRestoreResult.Failure -> result.message
        }

    companion object {
        private var started: Boolean = false
    fun start() {
            if (started) return
            try {
                initKoin(appModule)
            } catch (_: Throwable) {
                // If already started, ignore
            } finally {
                started = true
            }
        }
    }

    // --- History helpers ---
    // Returns the list of said items mapped as Phrase objects for easy Swift UI rendering
    suspend fun listHistoryAsPhrases(): List<Phrase> {
        return try {
            val said = get<SaidTextRepository>().list().filter { it.visibleInHistory }
            val now = 0L
            said.map { s ->
                Phrase(
                    id = "history-" + (s.id?.toString() ?: (s.createdAt ?: s.date ?: now).toString()),
                    text = s.saidText ?: "",
                    name = null,
                    backgroundColor = "#00000000",
                    parentId = null,
                    createdAt = (s.createdAt ?: s.date ?: now),
                    recordingPath = s.audioFilePath
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    // --- Prediction Helpers ---
    // Bridge to TextPredictionService
    suspend fun predict(context: String, maxWords: Int, maxLetters: Int): io.github.jdreioe.wingmate.domain.PredictionResult {
        return try {
            get<io.github.jdreioe.wingmate.domain.TextPredictionService>().predict(context, maxWords, maxLetters)
        } catch (_: Throwable) {
            io.github.jdreioe.wingmate.domain.PredictionResult()
        }
    }

    suspend fun trainPredictionModel() {
        try {
            val service = get<io.github.jdreioe.wingmate.domain.TextPredictionService>()
            val repo = get<io.github.jdreioe.wingmate.domain.SaidTextRepository>()
            val list = repo.list()
            
            // If it's the n-gram service, we can try to load base dict first
            if (service is io.github.jdreioe.wingmate.infrastructure.SimpleNGramPredictionService) {
                // Determine primary language
                val settings = get<SettingsUseCase>().get()
                val lang = settings.primaryLanguage
                
                // Try to load dict
                 try {
                    val loader = get<io.github.jdreioe.wingmate.infrastructure.DictionaryLoader>()
                    val dict = loader.loadDictionary(lang)
                    if (dict.isNotEmpty()) {
                        service.setBaseLanguage(dict)
                        // Train history on top without clearing
                        service.train(list, false)
                        return
                    }
                } catch (_: Throwable) {}
                 // Fallback: train just history (clearing old)
                service.train(list, true)
            } else {
                service.train(list)
            }
        } catch (t: Throwable) {
            OperationalLogger.warn("prediction_model.train", "failed", exceptionClass = t.loggingClassName())
        }
    }

    suspend fun learnPhrase(text: String) {
        try {
            val service = get<io.github.jdreioe.wingmate.domain.TextPredictionService>()
            if (service is io.github.jdreioe.wingmate.infrastructure.SimpleNGramPredictionService) {
                service.learnPhrase(text)
            }
        } catch (_: Throwable) {}
    }

    // --- Pronunciation Dictionary Helpers ---
    suspend fun listPronunciations(): List<io.github.jdreioe.wingmate.domain.PronunciationEntry> {
        return try {
            get<io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository>().getAll()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun addPronunciation(word: String, phoneme: String, alphabet: String) {
        try {
            get<io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository>().add(
                io.github.jdreioe.wingmate.domain.PronunciationEntry(word, phoneme, alphabet)
            )
        } catch (_: Throwable) {}
    }

    suspend fun deletePronunciation(word: String) {
        try {
            get<io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository>().delete(word)
        } catch (_: Throwable) {}
    }

    // --- OpenSymbols helpers (route through shared client, not Swift) ---
    fun setOpenSymbolsProxyUrl(url: String?) {
        OpenSymbolsClient.setProxyBaseUrl(url)
    }

    suspend fun openSymbolsSearch(
        query: String,
        locale: String,
        symbolPackage: String,
        prioritizeArasaac: Boolean,
    ): IosOpenSymbolsResult {
        return when (
            val result = SymbolSearchClient.search(
                query = query,
                locale = locale,
                packageFilter = SymbolSearchClient.Package.fromWireValue(symbolPackage),
                prioritizeArasaac = prioritizeArasaac,
            )
        ) {
            is SymbolSearchClient.SearchResponse.Success -> IosOpenSymbolsResult(
                symbols = result.symbols.map {
                    IosOpenSymbol(
                        id = it.id,
                        name = it.name,
                        imageUrl = it.imageUrl,
                        source = it.source.name.lowercase(),
                    )
                },
                errorCode = ""
            )
            is SymbolSearchClient.SearchResponse.Failure -> IosOpenSymbolsResult(
                symbols = emptyList(),
                errorCode = result.error.toIosErrorCode()
            )
        }
    }
}

private fun OpenSymbolsClient.SearchError.toIosErrorCode(): String = when (this) {
    OpenSymbolsClient.SearchError.NotConfigured -> "missing_proxy"
    OpenSymbolsClient.SearchError.Throttled,
    OpenSymbolsClient.SearchError.Network,
    OpenSymbolsClient.SearchError.Server,
    -> "search_failed"
}

data class IosOpenSymbol(
    val id: String,
    val name: String? = null,
    val imageUrl: String? = null,
    val source: String = "opensymbols",
)

data class IosOpenSymbolsResult(
    val symbols: List<IosOpenSymbol> = emptyList(),
    val errorCode: String = "",
)

data class IosBoardSetExportResult(
    val success: Boolean,
    val fileName: String? = null,
    val message: String = "",
)

private fun String.toBoardActivationBehavior(): BoardActivationBehavior =
    when (this) {
        "AddOnly" -> BoardActivationBehavior.AddOnly
        "SpeakOnly" -> BoardActivationBehavior.SpeakOnly
        else -> BoardActivationBehavior.SpeakAndAdd
    }

private fun String.toBoardReturnBehavior(): BoardReturnBehavior =
    when (this) {
        "Previous" -> BoardReturnBehavior.Previous
        "StartPage" -> BoardReturnBehavior.StartPage
        else -> BoardReturnBehavior.Stay
    }

data class IosBoardCell(
    val row: Int,
    val col: Int,
    val buttonId: String,
    val label: String?,
    val vocalization: String?,
    val backgroundColor: String?,
    val resolvedBackgroundColor: String?,
    val wordType: String?,
    val borderColor: String?,
    val linkedBoardId: String?,
    val imageId: String?,
    val imageUrl: String?,
    val hidden: Boolean,
    val actions: List<String>,
    val soundId: String? = null,
    val soundDataUrl: String? = null,
    val shape: String = "square",
)

data class IosSettingsFlags(
    val usesSystemTts: Boolean,
    val startupUsesScreens: Boolean
)

data class IosBoardFieldItem(
    val row: Int,
    val column: Int,
    val rowSpan: Int,
    val columnSpan: Int,
    val buttonId: String? = null
)

data class IosGridFieldSpan(
    val rows: Int,
    val columns: Int
)

data class IosBoardReturnResult(
    val boardId: String?,
    val boardStack: List<String>
)

data class IosButtonSpeechPart(
    val text: String,
    val language: String?,
    val recordingPath: String?,
    val mathMode: Boolean
)

data class IosResolvedBoardSettings(
    val showLabels: Boolean,
    val showSymbols: Boolean,
    val labelAtTop: Boolean,
    val showMessageBar: Boolean,
    val activationBehavior: String,
    val returnBehavior: String
)

data class IosQuickCoreProgress(
    val stage: String,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val fraction: Double?,
)
