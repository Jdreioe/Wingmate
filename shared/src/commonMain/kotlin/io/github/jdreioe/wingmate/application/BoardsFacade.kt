package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.BoardRepository
import io.github.jdreioe.wingmate.domain.obf.BoardReturnBehavior
import io.github.jdreioe.wingmate.domain.obf.BoardSettingsOverrides
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import io.github.jdreioe.wingmate.domain.obf.ObfKeyboardLayout
import io.github.jdreioe.wingmate.domain.obf.ObfLoadBoard
import io.github.jdreioe.wingmate.domain.obf.WordType
import io.github.jdreioe.wingmate.domain.obf.applyBoardReturnBehavior
import io.github.jdreioe.wingmate.domain.obf.availableFieldSpansAt
import io.github.jdreioe.wingmate.domain.obf.backspaceSentenceSelection
import io.github.jdreioe.wingmate.domain.obf.fieldItems
import io.github.jdreioe.wingmate.domain.obf.fieldFontScale
import io.github.jdreioe.wingmate.domain.obf.joinSentenceText
import io.github.jdreioe.wingmate.domain.obf.pageSettingsOverrides
import io.github.jdreioe.wingmate.domain.obf.resolveBoardSettings
import io.github.jdreioe.wingmate.domain.obf.resolveObfLocalizedString
import io.github.jdreioe.wingmate.domain.obf.resolvedBackgroundColor
import io.github.jdreioe.wingmate.domain.obf.withFieldSpan
import io.github.jdreioe.wingmate.domain.obf.withWordType
import io.github.jdreioe.wingmate.domain.obf.wordType
import io.github.jdreioe.wingmate.infrastructure.BoardImportResult
import io.github.jdreioe.wingmate.infrastructure.QuickCorePresetService
import io.github.jdreioe.wingmate.platform.ShareService

data class IosBoardSetExportResult(
    val success: Boolean,
    val fileName: String? = null,
    val message: String = "",
)

data class IosResolvedBoardSettings(
    val showLabels: Boolean,
    val showSymbols: Boolean,
    val labelAtTop: Boolean,
    val showMessageBar: Boolean,
    val showSpeakButton: Boolean,
    val activationBehavior: String,
    val returnBehavior: String,
)

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

data class IosBoardFieldItem(
    val row: Int,
    val column: Int,
    val rowSpan: Int,
    val columnSpan: Int,
    val buttonId: String? = null,
)

data class IosGridFieldSpan(
    val rows: Int,
    val columns: Int,
)

data class IosBoardReturnResult(
    val boardId: String?,
    val boardStack: List<String>,
)

data class IosQuickCoreProgress(
    val stage: String,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val fraction: Double?,
)

/**
 * A feature-scoped native boundary around board sets, board editing, and the
 * shared board-session logic (same behavior as Android/Linux).
 *
 * Keep Koin out of this file: the constructor receives exactly the use cases
 * and services this feature needs. Failures propagate as suspend exceptions
 * (including coroutine cancellation) instead of being swallowed or converted.
 */
class BoardsFacade(
    private val boardSetUseCase: BoardSetUseCase,
    private val boardRepository: BoardRepository,
    private val settingsUseCase: SettingsUseCase,
    private val boardSetSpeechCache: BoardSetSpeechCacheUseCase,
    private val quickCorePresetService: QuickCorePresetService,
    private val shareService: ShareService,
) {
    // --- BoardSet helpers ---
    suspend fun listBoardSets(): List<ObfBoardSet> = boardSetUseCase.listBoardSets()
    suspend fun deleteBoardSet(id: String) = boardSetUseCase.deleteBoardSet(id)
    suspend fun duplicateBoardSet(id: String): ObfBoardSet? = boardSetUseCase.duplicateBoardSet(id)
    suspend fun toggleBoardSetLocked(id: String): ObfBoardSet? = boardSetUseCase.toggleLocked(id)
    suspend fun updateBoardSetSentenceCaching(id: String, enabled: Boolean): ObfBoardSet? =
        boardSetUseCase.setSentenceCaching(id, enabled)
    suspend fun cacheAllBoardSetFields() = boardSetSpeechCache.cacheAll()
    suspend fun retryBoardSetSpeechCaching() = boardSetSpeechCache.retryPending()
    fun updateBoardSetSpeechCacheOnline(online: Boolean) = boardSetSpeechCache.setOnline(online)
    suspend fun touchBoardSet(id: String): ObfBoardSet? = boardSetUseCase.touchBoardSet(id)
    suspend fun createBoardSet(name: String, rows: Int, columns: Int): ObfBoardSet =
        boardSetUseCase.createBoardSet(name, rows, columns)
    suspend fun createKeyboardBoardSet(name: String, preset: String): ObfBoardSet =
        boardSetUseCase.createKeyboardBoardSet(
            name,
            if (preset.equals("alphabetical", ignoreCase = true)) KeyboardPreset.Alphabetical else KeyboardPreset.Qwerty,
        )
    suspend fun importQuickCorePreset(slug: String, name: String): ObfBoardSet? {
        val imported = quickCorePresetService.importPreset(slug) as? BoardImportResult.Success
            ?: return null
        return boardSetUseCase.renameBoardSet(imported.boardSet.id, name.trim()) ?: imported.boardSet
    }
    fun quickCoreProgress(): IosQuickCoreProgress {
        val progress = quickCorePresetService.progress.value
        return IosQuickCoreProgress(progress.stage, progress.downloadedBytes, progress.totalBytes, progress.fraction)
    }
    suspend fun createBoard(boardSetId: String, name: String, rows: Int, columns: Int): ObfBoard? =
        boardSetUseCase.createBoard(boardSetId, name, rows, columns)
    suspend fun createKeyboardBoard(
        boardSetId: String,
        name: String,
        rows: Int,
        columns: Int,
        layout: String,
    ): ObfBoard? = boardSetUseCase.createBoard(
        boardSetId,
        name,
        rows,
        columns,
        ObfKeyboardLayout.entries.firstOrNull { it.wireValue == layout } ?: ObfKeyboardLayout.Qwerty,
    )
    suspend fun renameBoardSet(boardSetId: String, name: String): ObfBoardSet? =
        boardSetUseCase.renameBoardSet(boardSetId, name)
    suspend fun renameBoard(boardSetId: String, boardId: String, name: String): ObfBoard? =
        boardSetUseCase.renameBoard(boardSetId, boardId, name)
    suspend fun resizeBoard(boardSetId: String, boardId: String, rows: Int, columns: Int): ObfBoard? =
        boardSetUseCase.resizeBoard(boardSetId, boardId, rows, columns)
    suspend fun setRootBoard(boardSetId: String, boardId: String): ObfBoardSet? =
        boardSetUseCase.setRootBoard(boardSetId, boardId)
    suspend fun deleteBoard(boardSetId: String, boardId: String): ObfBoardSet? =
        boardSetUseCase.deleteBoard(boardSetId, boardId)

    suspend fun shareBoardSetAsObz(id: String): IosBoardSetExportResult {
        val boardSet = boardSetUseCase.getBoardSet(id)
            ?: return IosBoardSetExportResult(success = false, fileName = null, message = "Board set not found")
        return when (val export = boardSetUseCase.exportBoardSetAsObzResult(id)) {
            is ObzExportResult.Success -> {
                val fileName = "${boardSet.name}.obz"
                val shared = runCatching { shareService.shareFile(fileName, export.bytes) }
                    .getOrDefault(false)
                if (shared) {
                    IosBoardSetExportResult(success = true, fileName = fileName, message = "Exported $fileName")
                } else {
                    IosBoardSetExportResult(success = false, fileName = fileName, message = "Export cancelled")
                }
            }
            is ObzExportResult.Failure -> {
                val resources = export.resources.takeIf { it.isNotEmpty() }?.joinToString(prefix = ": ")
                IosBoardSetExportResult(success = false, fileName = null, message = "Export failed: ${export.context}$resources")
            }
        }
    }

    // --- Swift-friendly board helpers ---
    suspend fun getBoard(id: String): ObfBoard? = boardRepository.getBoard(id)

    /**
     * Resolve the effective board settings for the given board id, applying app-level
     * defaults, then screen overrides, then page overrides (shared with Android).
     */
    suspend fun resolveBoardSettings(boardId: String): IosResolvedBoardSettings {
        val settings = settingsUseCase.get()
        val board = boardRepository.getBoard(boardId)
        val screenOverrides = boardSetUseCase.listBoardSets()
            .firstOrNull { set -> set.boardIds.contains(boardId) }
            ?.screenSettings ?: BoardSettingsOverrides()
        val pageOverrides = board?.pageSettingsOverrides() ?: BoardSettingsOverrides()
        val resolved = resolveBoardSettings(
            appShowLabels = settings.showLabels,
            appShowSymbols = settings.showSymbols,
            appLabelAtTop = settings.labelAtTop,
            appShowMessageBar = settings.boardShowMessageBar,
            appShowSpeakButton = settings.boardShowSpeakButton,
            appActivationBehavior = settings.boardActivationBehavior,
            appReturnBehavior = settings.boardReturnBehavior,
            screen = screenOverrides,
            page = pageOverrides,
        )
        return IosResolvedBoardSettings(
            showLabels = resolved.showLabels,
            showSymbols = resolved.showSymbols,
            labelAtTop = resolved.labelAtTop,
            showMessageBar = resolved.showMessageBar,
            showSpeakButton = resolved.showSpeakButton,
            activationBehavior = resolved.activationBehavior.name,
            returnBehavior = resolved.returnBehavior.name,
        )
    }

    fun boardKeyboardLayout(board: ObfBoard): String? = board.keyboardLayout?.wireValue

    fun boardUsesSpellingMode(board: ObfBoard): Boolean = board.spellingMode

    suspend fun saveBoard(board: ObfBoard): Boolean = runCatching {
        boardRepository.saveBoard(board)
        true
    }.getOrDefault(false)

    suspend fun listBoardCells(boardId: String): List<IosBoardCell> {
        val board = boardRepository.getBoard(boardId) ?: return emptyList()
        val grid = board.grid ?: return emptyList()
        val buttons = board.buttons.associateBy { it.id }
        val images = board.images.associateBy { it.id }
        val settings = settingsUseCase.get()
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
                    button.shape.wireValue,
                )
            }
        }
    }

    // --- Grid span / merge operations (shared with Android via core/domain) ---
    suspend fun listBoardFieldItems(boardId: String): List<IosBoardFieldItem> {
        val board = boardRepository.getBoard(boardId) ?: return emptyList()
        val grid = board.grid ?: return emptyList()
        return grid.fieldItems().map { field ->
            IosBoardFieldItem(
                row = field.row,
                column = field.column,
                rowSpan = field.rowSpan,
                columnSpan = field.columnSpan,
                buttonId = field.buttonId,
            )
        }
    }

    suspend fun availableFieldSpans(boardId: String, row: Int, col: Int): List<IosGridFieldSpan> {
        val board = boardRepository.getBoard(boardId) ?: return emptyList()
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
        val board = boardRepository.getBoard(boardId) ?: return false
        val grid = board.grid ?: return false
        val buttonId = grid.order.getOrNull(row)?.getOrNull(col) ?: return false
        val resized = grid.withFieldSpan(row, col, buttonId, rowSpan, columnSpan) ?: return false
        if (resized == grid) return false
        boardRepository.saveBoard(board.copy(grid = resized))
        return true
    }

    // --- Shared board-session logic (same behavior as Android/Linux) ---
    fun nGramPredictionInsertion(sentence: String, suggestion: String): String =
        io.github.jdreioe.wingmate.domain.obf.nGramPredictionInsertion(sentence, suggestion)

    fun boardReturnBehavior(
        behavior: String,
        currentBoardId: String?,
        boardStack: List<String>,
        rootBoardId: String,
    ): IosBoardReturnResult {
        val (boardId, stack) = applyBoardReturnBehavior(
            behavior.toBoardReturnBehavior(), currentBoardId, boardStack, rootBoardId,
        )
        return IosBoardReturnResult(boardId = boardId, boardStack = stack)
    }

    fun boardBackspaceSentence(texts: List<String>, spellingMode: Boolean): List<String> =
        backspaceSentenceSelection(texts, spellingMode)

    fun boardButtonIsVisible(hidden: Boolean, isEditMode: Boolean, showHiddenButtons: Boolean): Boolean =
        !hidden || isEditMode || showHiddenButtons

    fun boardFieldFontScale(rowSpan: Int, columnSpan: Int): Float =
        fieldFontScale(rowSpan, columnSpan)

    fun boardJoinSentenceText(tokens: List<String>, spellingMode: Boolean): String =
        joinSentenceText(tokens, spellingMode)

    suspend fun upsertBoardCellButton(
        boardId: String, row: Int, col: Int, label: String?, vocalization: String?,
        backgroundColor: String?, borderColor: String?, linkedBoardId: String?,
        imageUrl: String?, clearImage: Boolean, actions: List<String>, wordType: String?,
    ): ObfBoard? {
        val board = boardRepository.getBoard(boardId) ?: return null
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
            actions = if (actions.size > 1) actions else emptyList(),
        ).withWordType(wordType?.let { value -> WordType.entries.firstOrNull { it.wireValue == value } })
        val buttons = board.buttons.filterNot { it.id == buttonId } + button
        val order = grid.order.mapIndexed { r, columns ->
            columns.mapIndexed { c, id -> if (r == row && c == col) buttonId else id }
        }
        val usedImageIds = buttons.mapNotNull { it.imageId }.toSet()
        return board.copy(
            buttons = buttons,
            images = images.filter { it.id in usedImageIds },
            grid = grid.copy(order = order),
        ).also {
            boardRepository.saveBoard(it)
            boardSetSpeechCache.cacheField(it, button)
        }
    }

    suspend fun clearBoardCellButton(boardId: String, row: Int, col: Int): ObfBoard? {
        val board = boardRepository.getBoard(boardId) ?: return null
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
            .also { boardRepository.saveBoard(it) }
    }

    suspend fun setBoardBackgroundColor(
        boardSetId: String,
        boardId: String,
        backgroundColor: String?,
    ): ObfBoard? = runCatching {
        val board = boardRepository.getBoard(boardId) ?: return@runCatching null
        val updated = board.copy(backgroundColor = backgroundColor?.trim()?.takeIf(String::isNotEmpty))
        boardRepository.saveBoard(updated)
        boardSetUseCase.touchBoardSet(boardSetId)
        updated
    }.getOrNull()
}

private fun String.toBoardReturnBehavior(): BoardReturnBehavior =
    when (this) {
        "Previous" -> BoardReturnBehavior.Previous
        "StartPage" -> BoardReturnBehavior.StartPage
        else -> BoardReturnBehavior.Stay
    }
