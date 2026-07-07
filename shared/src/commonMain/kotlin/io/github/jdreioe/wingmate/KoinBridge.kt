package io.github.jdreioe.wingmate

import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.application.bloc.PhraseListStore
import io.github.jdreioe.wingmate.di.appModule
import io.github.jdreioe.wingmate.initKoin
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.BoardRepository
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import io.github.jdreioe.wingmate.domain.obf.ObfLoadBoard
import io.github.jdreioe.wingmate.domain.obf.ObfManifest
import io.github.jdreioe.wingmate.infrastructure.ObfParser
import kotlin.random.Random
import kotlin.time.Clock
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

data class BoardCellSummary(
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

class KoinBridge : KoinComponent {
    fun phraseListStore(): PhraseListStore = get()
    // Safe variant to avoid throwing across Swift bridge
    fun phraseListStoreOrNull(): PhraseListStore? = try { get<PhraseListStore>() } catch (_: Throwable) { null }

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
        val candidate = when {
            !voice.selectedLanguage.isNullOrEmpty() -> voice.selectedLanguage!!
            !voice.primaryLanguage.isNullOrEmpty() -> voice.primaryLanguage!!
            else -> current.primaryLanguage
        }
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

    suspend fun updateSecondaryLanguage(lang: String) {
        val settingsUseCase: SettingsUseCase = get()
        val current = settingsUseCase.get()
        if (lang != current.secondaryLanguage) {
            settingsUseCase.update(current.copy(secondaryLanguage = lang))
        }
    }

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

    suspend fun getSettings(): Settings = get<SettingsUseCase>().get()

    suspend fun updateScanningEnabled(enabled: Boolean) {
        val settingsUseCase: SettingsUseCase = get()
        val current = settingsUseCase.get()
        if (current.scanningEnabled != enabled) {
            settingsUseCase.update(current.copy(scanningEnabled = enabled))
        }
    }

    suspend fun updateScanPlaybackAreaEnabled(enabled: Boolean) {
        val settingsUseCase: SettingsUseCase = get()
        val current = settingsUseCase.get()
        if (current.scanPlaybackAreaEnabled != enabled) {
            settingsUseCase.update(current.copy(scanPlaybackAreaEnabled = enabled))
        }
    }

    suspend fun updateScanInputFieldEnabled(enabled: Boolean) {
        val settingsUseCase: SettingsUseCase = get()
        val current = settingsUseCase.get()
        if (current.scanInputFieldEnabled != enabled) {
            settingsUseCase.update(current.copy(scanInputFieldEnabled = enabled))
        }
    }

    suspend fun updateScanPhraseGridEnabled(enabled: Boolean) {
        val settingsUseCase: SettingsUseCase = get()
        val current = settingsUseCase.get()
        if (current.scanPhraseGridEnabled != enabled) {
            settingsUseCase.update(current.copy(scanPhraseGridEnabled = enabled))
        }
    }

    suspend fun updateScanCategoryItemsEnabled(enabled: Boolean) {
        val settingsUseCase: SettingsUseCase = get()
        val current = settingsUseCase.get()
        if (current.scanCategoryItemsEnabled != enabled) {
            settingsUseCase.update(current.copy(scanCategoryItemsEnabled = enabled))
        }
    }

    suspend fun updateScanTopBarEnabled(enabled: Boolean) {
        val settingsUseCase: SettingsUseCase = get()
        val current = settingsUseCase.get()
        if (current.scanTopBarEnabled != enabled) {
            settingsUseCase.update(current.copy(scanTopBarEnabled = enabled))
        }
    }

    suspend fun updateScanPhraseGridOrder(order: String) {
        val normalized = when (order.lowercase()) {
            "column-major" -> "column-major"
            "linear" -> "linear"
            else -> "row-major"
        }
        val settingsUseCase: SettingsUseCase = get()
        val current = settingsUseCase.get()
        if (current.scanPhraseGridOrder != normalized) {
            settingsUseCase.update(current.copy(scanPhraseGridOrder = normalized))
        }
    }

    suspend fun updateScanDwellTimeSeconds(seconds: Float) {
        val clamped = seconds.coerceIn(0.3f, 2.0f)
        val settingsUseCase: SettingsUseCase = get()
        val current = settingsUseCase.get()
        if (current.scanDwellTimeSeconds != clamped) {
            settingsUseCase.update(current.copy(scanDwellTimeSeconds = clamped))
        }
    }

    suspend fun updateScanAutoAdvanceSeconds(seconds: Float) {
        val clamped = seconds.coerceIn(0.5f, 3.0f)
        val settingsUseCase: SettingsUseCase = get()
        val current = settingsUseCase.get()
        if (current.scanAutoAdvanceSeconds != clamped) {
            settingsUseCase.update(current.copy(scanAutoAdvanceSeconds = clamped))
        }
    }

    suspend fun saveSpeechConfig(config: SpeechServiceConfig) {
        get<ConfigRepository>().saveSpeechConfig(config)
    }

    // --- OBF/OBZ helpers for iOS symbol-first workspace ---
    private fun randomBoardId(): String {
        val now = Clock.System.now().toEpochMilliseconds()
        return "board-$now-${Random.nextInt(1000, 9999)}"
    }

    private fun randomImageId(): String {
        val now = Clock.System.now().toEpochMilliseconds()
        return "image-$now-${Random.nextInt(1000, 9999)}"
    }

    private fun imageContentTypeFromUrl(imageUrl: String): String? {
        val normalized = imageUrl.substringBefore("?").lowercase()
        return when {
            normalized.endsWith(".png") -> "image/png"
            normalized.endsWith(".jpg") || normalized.endsWith(".jpeg") -> "image/jpeg"
            normalized.endsWith(".gif") -> "image/gif"
            normalized.endsWith(".webp") -> "image/webp"
            normalized.endsWith(".svg") -> "image/svg+xml"
            else -> null
        }
    }

    suspend fun listBoards(): List<ObfBoard> {
        return try {
            get<BoardRepository>().listBoards()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun getBoard(id: String): ObfBoard? {
        return try {
            get<BoardRepository>().getBoard(id)
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun saveBoard(board: ObfBoard): Boolean {
        return try {
            get<BoardRepository>().saveBoard(board)
            true
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun deleteBoard(id: String): Boolean {
        return try {
            get<BoardRepository>().deleteBoard(id)
            true
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun createEmptyBoard(name: String, rows: Int, columns: Int, locale: String): ObfBoard? {
        return try {
            val safeRows = rows.coerceIn(1, 12)
            val safeColumns = columns.coerceIn(1, 12)
            val order = List(safeRows) { List<String?>(safeColumns) { null } }
            val board = ObfBoard(
                format = "open-board-0.1",
                id = randomBoardId(),
                locale = locale.ifBlank { "en" },
                name = name.ifBlank { "New Board" },
                buttons = emptyList(),
                grid = ObfGrid(rows = safeRows, columns = safeColumns, order = order),
                images = emptyList(),
                sounds = emptyList()
            )
            get<BoardRepository>().saveBoard(board)
            board
        } catch (_: Throwable) {
            null
        }
    }

    private fun normalizedGridOrder(grid: ObfGrid): MutableList<MutableList<String?>> {
        return MutableList(grid.rows) { row ->
            MutableList(grid.columns) { col ->
                grid.order.getOrNull(row)?.getOrNull(col)
            }
        }
    }

    suspend fun listBoardCells(boardId: String): List<BoardCellSummary> {
        return try {
            val board = get<BoardRepository>().getBoard(boardId) ?: return emptyList()
            val grid = board.grid ?: return emptyList()
            val order = normalizedGridOrder(grid)
            val buttonMap = board.buttons.associateBy { it.id }
            val imageMap = board.images.associateBy { it.id }

            val cells = mutableListOf<BoardCellSummary>()
            for (row in 0 until grid.rows) {
                for (col in 0 until grid.columns) {
                    val buttonId = order[row][col] ?: continue
                    val button = buttonMap[buttonId] ?: continue
                    cells.add(
                        BoardCellSummary(
                            row = row,
                            col = col,
                            buttonId = button.id,
                            label = button.label,
                            vocalization = button.vocalization,
                            backgroundColor = button.backgroundColor,
                            borderColor = button.borderColor,
                            linkedBoardId = button.loadBoard?.id,
                            imageId = button.imageId,
                            imageUrl = button.imageId?.let { imageId ->
                                imageMap[imageId]?.url
                                    ?: imageMap[imageId]?.path
                                    ?: imageMap[imageId]?.data
                            }
                        )
                    )
                }
            }
            cells
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun upsertBoardCellButton(
        boardId: String,
        row: Int,
        col: Int,
        label: String?,
        vocalization: String?,
        backgroundColor: String?,
        borderColor: String?,
        linkedBoardId: String?,
        imageUrl: String?,
        clearImage: Boolean
    ): ObfBoard? {
        return try {
            val board = get<BoardRepository>().getBoard(boardId) ?: return null
            val grid = board.grid ?: return null

            if (row !in 0 until grid.rows || col !in 0 until grid.columns) {
                return null
            }

            val order = normalizedGridOrder(grid)
            val existingButtonId = order[row][col]
            val buttonId = existingButtonId ?: randomBoardId()
            val existingButton = board.buttons.firstOrNull { it.id == buttonId }
            val normalizedLinkId = linkedBoardId?.takeIf { it.isNotBlank() }
            val normalizedImageUrl = imageUrl?.takeIf { it.isNotBlank() }
            val images = board.images.toMutableList()
            val existingImageId = existingButton?.imageId

            var nextImageId = existingImageId
            if (clearImage) {
                nextImageId = null
            } else if (normalizedImageUrl != null) {
                val targetImageId = existingImageId ?: randomImageId()
                val contentType = imageContentTypeFromUrl(normalizedImageUrl)
                val existingImage = images.firstOrNull { it.id == targetImageId }
                val updatedImage = if (existingImage != null) {
                    existingImage.copy(
                        url = normalizedImageUrl,
                        path = null,
                        data = null,
                        contentType = contentType ?: existingImage.contentType
                    )
                } else {
                    ObfImage(
                        id = targetImageId,
                        contentType = contentType,
                        url = normalizedImageUrl,
                        path = null,
                        data = null
                    )
                }
                val imageIdx = images.indexOfFirst { it.id == targetImageId }
                if (imageIdx >= 0) {
                    images[imageIdx] = updatedImage
                } else {
                    images.add(updatedImage)
                }
                nextImageId = targetImageId
            }

            val updatedButton = if (existingButton != null) {
                existingButton.copy(
                    label = label,
                    vocalization = vocalization,
                    backgroundColor = backgroundColor,
                    borderColor = borderColor,
                    imageId = nextImageId,
                    loadBoard = normalizedLinkId?.let { ObfLoadBoard(id = it) }
                )
            } else {
                ObfButton(
                    id = buttonId,
                    label = label,
                    vocalization = vocalization,
                    backgroundColor = backgroundColor,
                    borderColor = borderColor,
                    imageId = nextImageId,
                    loadBoard = normalizedLinkId?.let { ObfLoadBoard(id = it) }
                )
            }

            val updatedButtons = if (existingButton != null) {
                board.buttons.map { if (it.id == buttonId) updatedButton else it }
            } else {
                board.buttons + updatedButton
            }

            order[row][col] = buttonId
            val updatedGrid = grid.copy(order = order)
            val updatedImages = if (clearImage && existingImageId != null && updatedButtons.none { it.imageId == existingImageId }) {
                images.filterNot { it.id == existingImageId }
            } else {
                images
            }
            val updatedBoard = board.copy(buttons = updatedButtons, grid = updatedGrid, images = updatedImages)
            get<BoardRepository>().saveBoard(updatedBoard)
            updatedBoard
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun clearBoardCellButton(boardId: String, row: Int, col: Int): ObfBoard? {
        return try {
            val board = get<BoardRepository>().getBoard(boardId) ?: return null
            val grid = board.grid ?: return null

            if (row !in 0 until grid.rows || col !in 0 until grid.columns) {
                return null
            }

            val order = normalizedGridOrder(grid)
            val buttonId = order[row][col] ?: return board
            order[row][col] = null

            val buttonIsReferenced = order.any { orderRow -> orderRow.any { it == buttonId } }
            val updatedButtons = if (buttonIsReferenced) {
                board.buttons
            } else {
                board.buttons.filterNot { it.id == buttonId }
            }

            val removedImageId = board.buttons.firstOrNull { it.id == buttonId }?.imageId
            val updatedImages = if (removedImageId != null && updatedButtons.none { it.imageId == removedImageId }) {
                board.images.filterNot { it.id == removedImageId }
            } else {
                board.images
            }

            val updatedGrid = grid.copy(order = order)
            val updatedBoard = board.copy(buttons = updatedButtons, grid = updatedGrid, images = updatedImages)
            get<BoardRepository>().saveBoard(updatedBoard)
            updatedBoard
        } catch (_: Throwable) {
            null
        }
    }

    fun parseBoardJson(jsonContent: String): ObfBoard? {
        return runCatching {
            get<ObfParser>().parseBoard(jsonContent).getOrNull()
        }.getOrNull()
    }

    fun parseManifestJson(jsonContent: String): ObfManifest? {
        return runCatching {
            get<ObfParser>().parseManifest(jsonContent).getOrNull()
        }.getOrNull()
    }

    // Swift-friendly bridge to update phrase recording path
    fun updatePhraseRecording(phraseId: String, recordingPath: String?) {
        try {
            phraseListStore().accept(PhraseListStore.Intent.UpdatePhraseRecording(id = phraseId, recordingPath = recordingPath))
        } catch (t: Throwable) {
            logger.warn(t) { "updatePhraseRecording() failed; swallowing to avoid Swift bridge crash" }
        }
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
            val said = get<SaidTextRepository>().list()
            val now = Clock.System.now().toEpochMilliseconds()
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
            get<io.github.jdreioe.wingmate.domain.TextPredictionService>().predict(context, maxWords, maxWords)
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

