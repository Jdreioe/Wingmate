package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.BoardRepository
import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.obf.usedImageIds
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
import io.github.jdreioe.wingmate.domain.obf.fieldItems
import io.github.jdreioe.wingmate.domain.obf.fieldFontScale
import io.github.jdreioe.wingmate.domain.obf.pageSettingsOverrides
import io.github.jdreioe.wingmate.domain.obf.resolveBoardSettings
import io.github.jdreioe.wingmate.domain.obf.resolveObfLocalizedString
import io.github.jdreioe.wingmate.domain.obf.resolvedBackgroundColor
import io.github.jdreioe.wingmate.domain.obf.withFieldSpan
import io.github.jdreioe.wingmate.domain.obf.withWordType
import io.github.jdreioe.wingmate.domain.obf.wordType
import io.github.jdreioe.wingmate.infrastructure.BoardImportErrorCode
import io.github.jdreioe.wingmate.infrastructure.BoardImportResult
import io.github.jdreioe.wingmate.infrastructure.QuickCorePreset
import io.github.jdreioe.wingmate.infrastructure.QuickCorePresetService
import io.github.jdreioe.wingmate.platform.ShareService
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException

enum class BoardSetExportStatus {
    /** The share sheet opened with the exported file. */
    Success,
    NotFound,

    /** The Screen could not be packaged, for example because media is missing. */
    ExportFailure,

    /** The file was exported but the share sheet could not open; retrying may help. */
    ShareFailure,
}

data class BoardSetExportResult(
    val status: BoardSetExportStatus,
    val fileName: String? = null,
) {
    val isSuccess: Boolean get() = status == BoardSetExportStatus.Success
    val isRetryable: Boolean get() = status == BoardSetExportStatus.ShareFailure
}

data class IosResolvedBoardSettings(
    val showLabels: Boolean,
    val showSymbols: Boolean,
    val labelAtTop: Boolean,
    val showMessageBar: Boolean,
    val showSpeakButton: Boolean,
    val messageBarEditable: Boolean,
    val activationBehavior: String,
    val returnBehavior: String,
    val speechPolicy: String? = null,
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
 * shared board-session logic used by Android and iOS.
 *
 * Keep Koin out of this file: the constructor receives exactly the use cases
 * and services this feature needs. Suspend members throw only [NativeFailure]
 * or coroutine cancellation. Edits fail with [NativeFailureKind.NotFound] when
 * the Screen or Page is gone and [NativeFailureKind.Validation] when
 * [BoardSetUseCase] rejects them, for example because the Screen is locked.
 * Reads of a missing Page return nothing instead.
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
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun listBoardSets(): List<ObfBoardSet> = nativeCall { boardSetUseCase.listBoardSets() }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun deleteBoardSet(id: String) = nativeCall {
        requireBoardSet(id)
        boardSetUseCase.deleteBoardSet(id)
    }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun duplicateBoardSet(id: String): ObfBoardSet = nativeCall {
        requireBoardSet(id)
        boardSetUseCase.duplicateBoardSet(id).orRejected()
    }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun toggleBoardSetLocked(id: String): ObfBoardSet = nativeCall {
        requireBoardSet(id)
        boardSetUseCase.toggleLocked(id).orRejected()
    }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun updateBoardSetSentenceCaching(id: String, enabled: Boolean): ObfBoardSet = nativeCall {
        boardSetUseCase.setSentenceCaching(id, enabled) ?: throw NativeFailure(NativeFailureKind.NotFound)
    }

    /** Cache warming synthesizes with the cloud voice, so its failures are network failures. */
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun cacheAllBoardSetFields() = nativeCall(NativeFailureKind.Network) { boardSetSpeechCache.cacheAll() }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun retryBoardSetSpeechCaching() =
        nativeCall(NativeFailureKind.Network) { boardSetSpeechCache.retryPending() }
    fun updateBoardSetSpeechCacheOnline(online: Boolean) = boardSetSpeechCache.setOnline(online)
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun touchBoardSet(id: String): ObfBoardSet = nativeCall {
        boardSetUseCase.touchBoardSet(id) ?: throw NativeFailure(NativeFailureKind.NotFound)
    }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun createBoardSet(name: String, rows: Int, columns: Int): ObfBoardSet =
        nativeCall { boardSetUseCase.createBoardSet(name, rows, columns) }
    @Throws(NativeFailure::class)
    fun previewSpeech(name: String, text: String, paragraphsPerPage: Int): List<io.github.jdreioe.wingmate.domain.obf.SpeechImportPage> {
        if (paragraphsPerPage !in 1..io.github.jdreioe.wingmate.domain.obf.SpeechScreenImport.MAX_PARAGRAPHS_PER_PAGE) {
            throw NativeFailure(NativeFailureKind.Validation)
        }
        return io.github.jdreioe.wingmate.domain.obf.SpeechScreenImport.preview(name, text, paragraphsPerPage)
    }

    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun importSpeech(
        name: String, text: String, paragraphsPerPage: Int, previousLabel: String, nextLabel: String,
    ): ObfBoardSet = nativeCall {
        if (name.isBlank() || previewSpeech(name, text, paragraphsPerPage).isEmpty()) {
            throw NativeFailure(NativeFailureKind.Validation)
        }
        boardSetUseCase.importSpeech(name, text, paragraphsPerPage, previousLabel, nextLabel)
    }

    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun createKeyboardBoardSet(name: String, preset: String): ObfBoardSet = nativeCall {
        boardSetUseCase.createKeyboardBoardSet(
            name,
            if (preset.equals("alphabetical", ignoreCase = true)) KeyboardPreset.Alphabetical else KeyboardPreset.Qwerty,
        )
    }

    /**
     * Downloads and imports a Quick Core Screen named [name]. Fails with
     * [NativeFailureKind.Cancelled] when the import was cancelled,
     * [NativeFailureKind.NotFound] for an unknown preset,
     * [NativeFailureKind.Network] when the download fails, and
     * [NativeFailureKind.Validation] when the archive is not a usable Screen.
     */
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun importQuickCorePreset(slug: String, name: String): ObfBoardSet = nativeCall {
        if (QuickCorePreset.fromSlug(slug) == null) throw NativeFailure(NativeFailureKind.NotFound)
        when (val imported = quickCorePresetService.importPreset(slug)) {
            is BoardImportResult.Success ->
                boardSetUseCase.renameBoardSet(imported.boardSet.id, name.trim()) ?: imported.boardSet
            BoardImportResult.Cancelled -> {
                // The importer reports its own coroutine's cancellation as Cancelled; keep that cancellation.
                currentCoroutineContext().ensureActive()
                throw NativeFailure(NativeFailureKind.Cancelled)
            }
            is BoardImportResult.Failure -> throw NativeFailure(imported.code.toQuickCoreFailureKind())
        }
    }
    fun quickCoreProgress(): IosQuickCoreProgress {
        val progress = quickCorePresetService.progress.value
        return IosQuickCoreProgress(progress.stage, progress.downloadedBytes, progress.totalBytes, progress.fraction)
    }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun createBoard(boardSetId: String, name: String, rows: Int, columns: Int): ObfBoard = nativeCall {
        requireBoardSet(boardSetId)
        boardSetUseCase.createBoard(boardSetId, name, rows, columns).orRejected()
    }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun createKeyboardBoard(
        boardSetId: String,
        name: String,
        rows: Int,
        columns: Int,
        layout: String,
    ): ObfBoard = nativeCall {
        requireBoardSet(boardSetId)
        boardSetUseCase.createBoard(
            boardSetId,
            name,
            rows,
            columns,
            ObfKeyboardLayout.entries.firstOrNull { it.wireValue == layout } ?: ObfKeyboardLayout.Qwerty,
        ).orRejected()
    }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun renameBoardSet(boardSetId: String, name: String): ObfBoardSet = nativeCall {
        requireBoardSet(boardSetId)
        boardSetUseCase.renameBoardSet(boardSetId, name).orRejected()
    }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun renameBoard(boardSetId: String, boardId: String, name: String): ObfBoard = nativeCall {
        requireBoardInSet(boardSetId, boardId)
        boardSetUseCase.renameBoard(boardSetId, boardId, name).orRejected()
    }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun resizeBoard(boardSetId: String, boardId: String, rows: Int, columns: Int): ObfBoard = nativeCall {
        requireBoardInSet(boardSetId, boardId)
        boardSetUseCase.resizeBoard(boardSetId, boardId, rows, columns).orRejected()
    }
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun setRootBoard(boardSetId: String, boardId: String): ObfBoardSet = nativeCall {
        requireBoardInSet(boardSetId, boardId)
        boardSetUseCase.setRootBoard(boardSetId, boardId).orRejected()
    }

    /** Fails with [NativeFailureKind.Validation] for a locked Screen, its start Page, or its last Page. */
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun deleteBoard(boardSetId: String, boardId: String): ObfBoardSet = nativeCall {
        requireBoardInSet(boardSetId, boardId)
        boardSetUseCase.deleteBoard(boardSetId, boardId).orRejected()
    }

    /** Export problems are [BoardSetExportStatus]es; only storage failures throw. */
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun shareBoardSetAsObz(id: String): BoardSetExportResult = nativeCall {
        val boardSet = boardSetUseCase.getBoardSet(id)
            ?: return@nativeCall BoardSetExportResult(BoardSetExportStatus.NotFound)
        when (val export = boardSetUseCase.exportBoardSetAsObzResult(id)) {
            is ObzExportResult.Success -> {
                val fileName = "${boardSet.name}.obz"
                val shared = try {
                    shareService.shareFile(fileName, export.bytes)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    false
                }
                BoardSetExportResult(
                    if (shared) BoardSetExportStatus.Success else BoardSetExportStatus.ShareFailure,
                    fileName,
                )
            }
            is ObzExportResult.Failure -> {
                OperationalLogger.warn("boards.export_obz", "failed", count = export.resources.size)
                BoardSetExportResult(BoardSetExportStatus.ExportFailure)
            }
        }
    }

    // --- Swift-friendly board helpers ---
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun getBoard(id: String): ObfBoard? = nativeCall { boardRepository.getBoard(id) }

    /**
     * Resolve the effective board settings for the given board id, applying app-level
     * defaults, then screen overrides, then page overrides (shared with Android).
     */
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun resolveBoardSettings(boardId: String): IosResolvedBoardSettings = nativeCall {
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
            appMessageBarEditable = settings.boardMessageBarEditable,
            appActivationBehavior = settings.boardActivationBehavior,
            appReturnBehavior = settings.boardReturnBehavior,
            screen = screenOverrides,
            page = pageOverrides,
        )
        IosResolvedBoardSettings(
            showLabels = resolved.showLabels,
            showSymbols = resolved.showSymbols,
            labelAtTop = resolved.labelAtTop,
            showMessageBar = resolved.showMessageBar,
            showSpeakButton = resolved.showSpeakButton,
            messageBarEditable = resolved.messageBarEditable,
            activationBehavior = resolved.activationBehavior.name,
            returnBehavior = resolved.returnBehavior.name,
            speechPolicy = resolved.speechPolicy?.name,
        )
    }

    fun boardKeyboardLayout(board: ObfBoard): String? = board.keyboardLayout?.wireValue

    fun boardUsesSpellingMode(board: ObfBoard): Boolean = board.spellingMode

    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun listBoardCells(boardId: String): List<IosBoardCell> = nativeCall {
        val board = boardRepository.getBoard(boardId) ?: return@nativeCall emptyList()
        val grid = board.grid ?: return@nativeCall emptyList()
        val buttons = board.buttons.associateBy { it.id }
        val images = board.images.associateBy { it.id }
        val settings = settingsUseCase.get()
        val locale = settings.primaryLanguage
        grid.order.flatMapIndexed { row, columns ->
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
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun listBoardFieldItems(boardId: String): List<IosBoardFieldItem> = nativeCall {
        val board = boardRepository.getBoard(boardId) ?: return@nativeCall emptyList()
        val grid = board.grid ?: return@nativeCall emptyList()
        grid.fieldItems().map { field ->
            IosBoardFieldItem(
                row = field.row,
                column = field.column,
                rowSpan = field.rowSpan,
                columnSpan = field.columnSpan,
                buttonId = field.buttonId,
            )
        }
    }

    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun availableFieldSpans(boardId: String, row: Int, col: Int): List<IosGridFieldSpan> = nativeCall {
        val board = boardRepository.getBoard(boardId) ?: return@nativeCall emptyList()
        val grid = board.grid ?: return@nativeCall emptyList()
        grid.availableFieldSpansAt(row, col).map { span ->
            IosGridFieldSpan(rows = span.rows, columns = span.columns)
        }
    }

    /**
     * Grow/shrink the field at [row], [col] to [rowSpan] x [columnSpan]. Fails with
     * [NativeFailureKind.Validation] when there is no Button there or the span
     * does not fit; a span that is already set changes nothing.
     */
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun resizeBoardField(boardId: String, row: Int, col: Int, rowSpan: Int, columnSpan: Int) = nativeCall {
        val board = requireBoard(boardId)
        val grid = board.grid.orRejected()
        val buttonId = grid.order.getOrNull(row)?.getOrNull(col).orRejected()
        val resized = grid.withFieldSpan(row, col, buttonId, rowSpan, columnSpan).orRejected()
        if (resized != grid) boardRepository.saveBoard(board.copy(grid = resized))
    }

    // --- Shared board-session logic ---
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

    fun boardButtonIsVisible(hidden: Boolean, isEditMode: Boolean, showHiddenButtons: Boolean): Boolean =
        !hidden || isEditMode || showHiddenButtons

    fun boardFieldFontScale(rowSpan: Int, columnSpan: Int): Float =
        fieldFontScale(rowSpan, columnSpan)

    /** Fails with [NativeFailureKind.Validation] when [row], [col] is outside the Page's grid. */
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun upsertBoardCellButton(
        boardId: String, row: Int, col: Int, label: String?, vocalization: String?,
        backgroundColor: String?, borderColor: String?, linkedBoardId: String?,
        imageUrl: String?, clearImage: Boolean, actions: List<String>, wordType: String?,
    ): ObfBoard = nativeCall {
        val board = requireBoard(boardId)
        val grid = board.cellGrid(row, col)
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
        val usedImageIds = board.usedImageIds(buttons)
        board.copy(
            buttons = buttons,
            images = images.filter { it.id in usedImageIds },
            grid = grid.copy(order = order),
        ).also {
            boardRepository.saveBoard(it)
            boardSetSpeechCache.cacheField(it, button)
        }
    }

    /** Fails with [NativeFailureKind.Validation] when [row], [col] is outside the Page's grid. */
    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun clearBoardCellButton(boardId: String, row: Int, col: Int): ObfBoard = nativeCall {
        val board = requireBoard(boardId)
        val grid = board.cellGrid(row, col)
        val removedId = grid.order[row][col]
        val order = grid.order.mapIndexed { r, columns ->
            columns.mapIndexed { c, id -> if (r == row && c == col) null else id }
        }
        val stillUsed = order.flatten().toSet()
        val buttons = board.buttons.filter { it.id != removedId || it.id in stillUsed }
        val usedImages = board.usedImageIds(buttons)
        board.copy(buttons = buttons, images = board.images.filter { it.id in usedImages }, grid = grid.copy(order = order))
            .also { boardRepository.saveBoard(it) }
    }

    @Throws(NativeFailure::class, CancellationException::class)
    suspend fun setBoardBackgroundColor(
        boardSetId: String,
        boardId: String,
        backgroundColor: String?,
    ): ObfBoard = nativeCall {
        requireBoardInSet(boardSetId, boardId)
        requireBoard(boardId)
            .copy(backgroundColor = backgroundColor?.trim()?.takeIf(String::isNotEmpty))
            .also {
                boardRepository.saveBoard(it)
                boardSetUseCase.touchBoardSet(boardSetId)
            }
    }

    private suspend fun requireBoardSet(id: String): ObfBoardSet =
        boardSetUseCase.getBoardSet(id) ?: throw NativeFailure(NativeFailureKind.NotFound)

    private suspend fun requireBoardInSet(boardSetId: String, boardId: String) {
        if (boardId !in requireBoardSet(boardSetId).boardIds) throw NativeFailure(NativeFailureKind.NotFound)
    }

    private suspend fun requireBoard(id: String): ObfBoard =
        boardRepository.getBoard(id) ?: throw NativeFailure(NativeFailureKind.NotFound)
}

/** The Page's grid when [row], [col] lies inside it. */
private fun ObfBoard.cellGrid(row: Int, col: Int): ObfGrid {
    val grid = this.grid.orRejected()
    if (row !in 0 until grid.rows || col !in 0 until grid.columns) throw NativeFailure(NativeFailureKind.Validation)
    return grid
}

/** A null from a shared rule that refused the change. Check existence first so null only means refusal. */
private fun <T : Any> T?.orRejected(): T = this ?: throw NativeFailure(NativeFailureKind.Validation)

/**
 * For Quick Core, [BoardImportErrorCode.FILE_UNREADABLE] mostly means the download
 * failed or came back empty, so retrying is the useful response. Every other
 * code describes an archive Wingmate refuses to import.
 */
private fun BoardImportErrorCode.toQuickCoreFailureKind(): NativeFailureKind = when (this) {
    BoardImportErrorCode.FILE_UNREADABLE -> NativeFailureKind.Network
    BoardImportErrorCode.PERSISTENCE_FAILED -> NativeFailureKind.Persistence
    else -> NativeFailureKind.Validation
}

private fun String.toBoardReturnBehavior(): BoardReturnBehavior =
    when (this) {
        "Previous" -> BoardReturnBehavior.Previous
        "StartPage" -> BoardReturnBehavior.StartPage
        else -> BoardReturnBehavior.Stay
    }
