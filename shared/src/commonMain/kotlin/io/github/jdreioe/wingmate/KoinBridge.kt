package io.github.jdreioe.wingmate

import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.application.BoardSetUseCase
import io.github.jdreioe.wingmate.application.BoardSetSpeechCacheUseCase
import io.github.jdreioe.wingmate.application.bloc.PhraseListStore
import io.github.jdreioe.wingmate.di.appModule
import io.github.jdreioe.wingmate.initKoin
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import io.github.jdreioe.wingmate.domain.TextEditResult
import io.github.jdreioe.wingmate.domain.TextEditingPolicy
import io.github.jdreioe.wingmate.domain.TextSpan
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import io.github.jdreioe.wingmate.domain.obf.ObfLoadBoard
import io.github.jdreioe.wingmate.domain.BoardRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

class KoinBridge : KoinComponent {
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
    suspend fun speak(text: String) {
        try {
            get<SpeechService>().speak(text)
        } catch (t: Throwable) {
            logger.warn(t) { "speak() failed; swallowing to avoid Swift bridge crash" }
        }
    }

    suspend fun speakBoardSentence(text: String, cacheAudio: Boolean) {
        try {
            get<SpeechService>().speakWithCachePolicy(text = text, cacheAudio = cacheAudio)
        } catch (t: Throwable) {
            logger.warn(t) { "speakBoardSentence() failed; swallowing to avoid Swift bridge crash" }
        }
    }

    suspend fun pause() {
        try {
            get<SpeechService>().pause()
        } catch (t: Throwable) {
            logger.warn(t) { "pause() failed; swallowing to avoid Swift bridge crash" }
        }
    }

    suspend fun stop() {
        try {
            get<SpeechService>().stop()
        } catch (t: Throwable) {
            logger.warn(t) { "stop() failed; swallowing to avoid Swift bridge crash" }
        }
    }

    suspend fun selectVoiceAndMaybeUpdatePrimary(voice: Voice) {
        val voiceUseCase: VoiceUseCase = get()
    try { println("DEBUG: KoinBridge.selectVoiceAndMaybeUpdatePrimary() called for '\${voice.name}' selectedLang='\${voice.selectedLanguage}'") } catch (_: Throwable) {}
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
    suspend fun updateHoldToSelectMillis(millis: Long) = updateSettings { it.copy(holdToSelectMillis = millis.coerceIn(0, 2_000)) }
    suspend fun updateDwellToSelectMillis(millis: Long) = updateSettings { it.copy(dwellToSelectMillis = millis.coerceIn(0, 5_000)) }
    suspend fun updateSelectionSoundEnabled(enabled: Boolean) = updateSettings { it.copy(selectionSoundEnabled = enabled) }
    suspend fun updateAuditoryFishingEnabled(enabled: Boolean) = updateSettings { it.copy(auditoryFishingEnabled = enabled) }
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

    suspend fun getSpeechConfig(): SpeechServiceConfig? = get<ConfigRepository>().getSpeechConfig()

    suspend fun saveSpeechConfig(config: SpeechServiceConfig) {
        get<ConfigRepository>().saveSpeechConfig(config)
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
            logger.warn(t) { "updatePhraseRecording() failed; swallowing to avoid Swift bridge crash" }
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
    suspend fun createBoard(boardSetId: String, name: String, rows: Int, columns: Int): ObfBoard? =
        get<BoardSetUseCase>().createBoard(boardSetId, name, rows, columns)
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

    // --- Swift-friendly board helpers ---
    suspend fun getBoard(id: String): ObfBoard? = get<BoardRepository>().getBoard(id)

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
        return grid.order.flatMapIndexed { row, columns ->
            columns.mapIndexedNotNull { col, buttonId ->
                val id = buttonId ?: return@mapIndexedNotNull null
                val button = buttons[id] ?: return@mapIndexedNotNull null
                IosBoardCell(
                    row, col, id, button.label, button.vocalization,
                    button.backgroundColor, button.borderColor, button.loadBoard?.id,
                    button.imageId, button.imageId?.let { images[it]?.url }
                )
            }
        }
    }

    suspend fun upsertBoardCellButton(
        boardId: String, row: Int, col: Int, label: String?, vocalization: String?,
        backgroundColor: String?, borderColor: String?, linkedBoardId: String?,
        imageUrl: String?, clearImage: Boolean
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
        val button = ObfButton(
            id = buttonId, label = label, vocalization = vocalization,
            imageId = imageId, backgroundColor = backgroundColor, borderColor = borderColor,
            loadBoard = linkedBoardId?.let { ObfLoadBoard(id = it) }
        )
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
            logger.warn(t) { "trainPredictionModel() failed" }
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
}

private val logger = KotlinLogging.logger {}

data class IosBoardCell(
    val row: Int,
    val col: Int,
    val buttonId: String,
    val label: String?,
    val vocalization: String?,
    val backgroundColor: String?,
    val borderColor: String?,
    val linkedBoardId: String?,
    val imageId: String?,
    val imageUrl: String?
)

data class IosSettingsFlags(
    val usesSystemTts: Boolean,
    val startupUsesScreens: Boolean
)
