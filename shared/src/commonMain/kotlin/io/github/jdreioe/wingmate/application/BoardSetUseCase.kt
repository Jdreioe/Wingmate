package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.BoardRepository
import io.github.jdreioe.wingmate.domain.BoardSetRepository
import io.github.jdreioe.wingmate.domain.FileStorage
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.domain.obf.BoardSetGraph
import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import io.github.jdreioe.wingmate.domain.obf.ObfKeyboardLayout
import io.github.jdreioe.wingmate.domain.obf.ScreenKind
import io.github.jdreioe.wingmate.domain.obf.requireValid
import io.github.jdreioe.wingmate.domain.obf.OBF_SCREEN_SETTINGS_EXTENSION
import io.github.jdreioe.wingmate.domain.obf.encodeBoardSettings
import kotlin.random.Random
import kotlin.time.Clock

class BoardSetUseCase(
    private val boardSetRepository: BoardSetRepository,
    private val boardRepository: BoardRepository,
    private val featureUsageReporter: FeatureUsageReporter,
    private val obzExporter: ObzExporter = ObzExporter(),
    private val fileStorage: FileStorage? = null,
    private val speechCache: BoardSetSpeechCacheUseCase? = null,
) {
    /** User-created Screens only. System Screens have dedicated entry points. */
    suspend fun listBoardSets(): List<ObfBoardSet> =
        boardSetRepository.listBoardSets().filter { it.kind == ScreenKind.User }

    suspend fun getBoardSet(boardSetId: String): ObfBoardSet? = boardSetRepository.getBoardSet(boardSetId)

    suspend fun loadBoardSetGraph(boardSetId: String): BoardSetGraph? {
        val boardSet = boardSetRepository.getBoardSet(boardSetId) ?: return null
        val boards = boardSet.boardIds.mapNotNull { boardRepository.getBoard(it) }
        return BoardSetGraph(boardSet = boardSet, boards = boards)
    }

    /**
     * Validate and persist a complete editor draft. The domain serialization is
     * unchanged; this method is the single commit boundary used by the UI.
     *
     * Persistence never awaits speech-cache work; call [warmSpeechCache] from a
     * background scope when audio should be pre-warmed.
     */
    suspend fun saveBoardSetGraph(graph: BoardSetGraph): Result<BoardSetGraph> = runCatching {
        val canonicalGraph = graph.canonicalizeBoardLinks()
        canonicalGraph.requireValid()
        val now = Clock.System.now().toEpochMilliseconds()
        val updatedSet = canonicalGraph.boardSet.copy(updatedAt = now)
        val previousSet = boardSetRepository.getBoardSet(canonicalGraph.boardSet.id)
        val previousBoards = previousSet?.boardIds
            ?.mapNotNull { boardRepository.getBoard(it) }
            .orEmpty()
        val newBoardIds = canonicalGraph.boards.map { it.id }.toSet()
        val removedBoardIds = previousSet?.boardIds.orEmpty().filterNot { it in newBoardIds }

        try {
            boardRepository.saveBoards(canonicalGraph.boards)
            boardSetRepository.saveBoardSet(updatedSet)
            removedBoardIds.forEach { boardRepository.deleteBoard(it) }
        } catch (error: Throwable) {
            boardRepository.saveBoards(previousBoards)
            previousSet?.let { boardSetRepository.saveBoardSet(it) }
            newBoardIds.filterNot { id -> previousBoards.any { it.id == id } }
                .forEach { boardRepository.deleteBoard(it) }
            throw error
        }
        canonicalGraph.copy(boardSet = updatedSet)
    }

    /** Pre-warms the TTS audio cache for every button; safe to run in a background scope. */
    suspend fun warmSpeechCache(graph: BoardSetGraph) {
        speechCache?.cacheGraph(graph)
    }

    suspend fun deleteBoardSet(boardSetId: String) {
        val graph = loadBoardSetGraph(boardSetId) ?: return
        if (graph.boardSet.kind != ScreenKind.User) return
        graph.boards.forEach { boardRepository.deleteBoard(it.id) }
        boardSetRepository.deleteBoardSet(boardSetId)
    }

    suspend fun duplicateBoardSet(boardSetId: String): ObfBoardSet? {
        val source = loadBoardSetGraph(boardSetId) ?: return null
        if (source.boardSet.kind != ScreenKind.User) return null
        val now = Clock.System.now().toEpochMilliseconds()
        val newSetId = generateId("set")
        val idMap = source.boards.associate { it.id to generateId("board") }
        val copiedBoards = source.boards.map { board ->
            val copiedButtons = board.buttons.map { button ->
                val targetId = button.loadBoard?.id
                button.copy(
                    loadBoard = button.loadBoard?.copy(
                        id = targetId?.let(idMap::get) ?: targetId
                    )
                )
            }
            board.copy(
                id = idMap.getValue(board.id),
                name = board.name,
                buttons = copiedButtons
            )
        }
        val copiedSet = source.boardSet.copy(
            id = newSetId,
            name = "${source.boardSet.name} (copy)",
            rootBoardId = idMap.getValue(source.boardSet.rootBoardId),
            boardIds = source.boardSet.boardIds.mapNotNull(idMap::get),
            isLocked = false,
            createdAt = now,
            updatedAt = now
        )
        return saveBoardSetGraph(BoardSetGraph(copiedSet, copiedBoards)).getOrThrow().boardSet
    }

    suspend fun createBoardSet(
        name: String,
        rows: Int,
        columns: Int,
        rootBoardName: String = "Home"
    ): ObfBoardSet {
        val now = Clock.System.now().toEpochMilliseconds()
        val boardId = generateId("board")
        val boardSetId = generateId("set")

        val normalizedRows = rows.coerceAtLeast(1)
        val normalizedColumns = columns.coerceAtLeast(1)

        val board = ObfBoard(
            format = "open-board-0.1",
            id = boardId,
            name = rootBoardName,
            grid = ObfGrid(
                rows = normalizedRows,
                columns = normalizedColumns,
                order = List(normalizedRows) { List(normalizedColumns) { null } }
            )
        )

        val boardSet = ObfBoardSet(
            id = boardSetId,
            name = name,
            rootBoardId = boardId,
            boardIds = listOf(boardId),
            createdAt = now,
            updatedAt = now
        )

        boardRepository.saveBoard(board)
        boardSetRepository.saveBoardSet(boardSet)
        featureUsageReporter.reportEvent(
            FeatureUsageEvents.BOARDSET_CREATED,
            "rows" to normalizedRows.toString(),
            "columns" to normalizedColumns.toString()
        )
        return boardSet
    }

    suspend fun createBoardSetFromBoards(
        name: String,
        boards: List<ObfBoard>
    ): ObfBoardSet {
        require(boards.isNotEmpty()) { "A board set must contain at least one board" }

        val now = Clock.System.now().toEpochMilliseconds()
        val boardSetId = generateId("set")
        val boardIdMap = boards.associate { it.id to generateId("board") }
        val importedBoards = boards.map { board ->
            board.copy(
                id = boardIdMap.getValue(board.id),
                buttons = board.buttons.map { button ->
                    val linkedBoardId = button.loadBoard?.id
                    button.copy(
                        loadBoard = button.loadBoard?.copy(
                            id = linkedBoardId?.let(boardIdMap::get) ?: linkedBoardId
                        )
                    )
                }
            )
        }

        val boardSet = ObfBoardSet(
            id = boardSetId,
            name = name,
            rootBoardId = importedBoards.first().id,
            boardIds = importedBoards.map { it.id },
            createdAt = now,
            updatedAt = now
        )
        val saved = saveBoardSetGraph(BoardSetGraph(boardSet, importedBoards)).getOrThrow().boardSet
        featureUsageReporter.reportEvent(
            FeatureUsageEvents.BOARDSET_CREATED,
            "mode" to "starter",
            "board_count" to importedBoards.size.toString()
        )
        return saved
    }

    suspend fun createCalculatorBoardSet(name: String = "Calculator"): ObfBoardSet =
        createBoardSetFromBoards(name, CalculatorBoardTemplate.boards())

    suspend fun createKeyboardBoardSet(
        name: String = "Keyboard",
        preset: KeyboardPreset = KeyboardPreset.Qwerty
    ): ObfBoardSet =
        createBoardSetFromBoards(name, KeyboardBoardTemplate.boards(preset))

    suspend fun toggleLocked(boardSetId: String): ObfBoardSet? {
        val boardSet = boardSetRepository.getBoardSet(boardSetId) ?: return null
        if (boardSet.kind != ScreenKind.User) return null
        val updated = boardSet.copy(
            isLocked = !boardSet.isLocked,
            updatedAt = Clock.System.now().toEpochMilliseconds()
        )
        boardSetRepository.saveBoardSet(updated)
        featureUsageReporter.reportEvent(
            FeatureUsageEvents.BOARDSET_LOCK_TOGGLED,
            "locked" to updated.isLocked.toString()
        )
        return updated
    }

    suspend fun setSentenceCaching(boardSetId: String, enabled: Boolean): ObfBoardSet? {
        val boardSet = boardSetRepository.getBoardSet(boardSetId) ?: return null
        if (boardSet.cacheWholeSentences == enabled) return boardSet
        val updated = boardSet.copy(
            cacheWholeSentences = enabled,
            updatedAt = Clock.System.now().toEpochMilliseconds()
        )
        boardSetRepository.saveBoardSet(updated)
        return updated
    }

    suspend fun touchBoardSet(boardSetId: String): ObfBoardSet? {
        val boardSet = boardSetRepository.getBoardSet(boardSetId) ?: return null
        val updated = boardSet.copy(updatedAt = Clock.System.now().toEpochMilliseconds())
        boardSetRepository.saveBoardSet(updated)
        featureUsageReporter.reportEvent(
            FeatureUsageEvents.BOARDSET_TOUCHED,
            "board_count" to updated.boardIds.size.toString()
        )
        return updated
    }

    suspend fun createBoard(
        boardSetId: String,
        name: String,
        rows: Int,
        columns: Int,
        keyboardLayout: ObfKeyboardLayout? = null
    ): ObfBoard? {
        val boardSet = boardSetRepository.getBoardSet(boardSetId) ?: return null
        if (boardSet.isLocked) return null

        val normalizedRows = rows.coerceAtLeast(1)
        val normalizedColumns = columns.coerceAtLeast(1)
        val boardId = generateId("board")

        var board = ObfBoard(
            format = "open-board-0.1",
            id = boardId,
            name = name,
            grid = ObfGrid(
                rows = normalizedRows,
                columns = normalizedColumns,
                order = List(normalizedRows) { List(normalizedColumns) { null } }
            )
        )
        if (keyboardLayout != null) {
            board = board
                .withCompactGrid(true)
                .withSpellingMode(true)
                .withKeyboardLayout(keyboardLayout)
        }

        boardRepository.saveBoard(board)
        boardSetRepository.saveBoardSet(
            boardSet.copy(
                boardIds = boardSet.boardIds + boardId,
                updatedAt = Clock.System.now().toEpochMilliseconds()
            )
        )
        featureUsageReporter.reportEvent(
            FeatureUsageEvents.BOARD_CREATED,
            "rows" to normalizedRows.toString(),
            "columns" to normalizedColumns.toString()
        )
        return board
    }

    suspend fun renameBoardSet(boardSetId: String, name: String): ObfBoardSet? {
        val boardSet = boardSetRepository.getBoardSet(boardSetId) ?: return null
        if (boardSet.isLocked) return null
        val normalized = name.trim().ifBlank { return null }
        val updated = boardSet.copy(name = normalized, updatedAt = Clock.System.now().toEpochMilliseconds())
        boardSetRepository.saveBoardSet(updated)
        return updated
    }

    suspend fun renameBoard(boardSetId: String, boardId: String, name: String): ObfBoard? {
        val boardSet = boardSetRepository.getBoardSet(boardSetId) ?: return null
        if (boardSet.isLocked || boardId !in boardSet.boardIds) return null
        val board = boardRepository.getBoard(boardId) ?: return null
        val normalized = name.trim().ifBlank { return null }
        val updated = board.copy(name = normalized)
        boardRepository.saveBoard(updated)
        touchBoardSet(boardSetId)
        return updated
    }

    suspend fun resizeBoard(boardSetId: String, boardId: String, rows: Int, columns: Int): ObfBoard? {
        val boardSet = boardSetRepository.getBoardSet(boardSetId) ?: return null
        if (boardSet.isLocked || boardId !in boardSet.boardIds) return null
        val board = boardRepository.getBoard(boardId) ?: return null
        val oldGrid = board.grid
        val safeRows = rows.coerceIn(1, 12)
        val safeColumns = columns.coerceIn(1, 12)
        val order = List(safeRows) { row ->
            List(safeColumns) { column -> oldGrid?.order?.getOrNull(row)?.getOrNull(column) }
        }
        val retainedIds = order.flatten().filterNotNull().toSet()
        val buttons = board.buttons.filter { it.id in retainedIds }
        val imageIds = buttons.mapNotNull { it.imageId }.toSet()
        val updated = board.copy(
            grid = ObfGrid(safeRows, safeColumns, order),
            buttons = buttons,
            images = board.images.filter { it.id in imageIds }
        )
        boardRepository.saveBoard(updated)
        touchBoardSet(boardSetId)
        return updated
    }

    suspend fun setRootBoard(boardSetId: String, boardId: String): ObfBoardSet? {
        val boardSet = boardSetRepository.getBoardSet(boardSetId) ?: return null
        if (boardSet.isLocked || boardId !in boardSet.boardIds) return null
        val updated = boardSet.copy(rootBoardId = boardId, updatedAt = Clock.System.now().toEpochMilliseconds())
        boardSetRepository.saveBoardSet(updated)
        return updated
    }

    suspend fun deleteBoard(boardSetId: String, boardId: String): ObfBoardSet? {
        val graph = loadBoardSetGraph(boardSetId) ?: return null
        if (graph.boardSet.isLocked || graph.boards.size <= 1 || graph.boardSet.rootBoardId == boardId) return null
        if (boardId !in graph.boardSet.boardIds) return null
        val remainingBoards = graph.boards.filterNot { it.id == boardId }.map { board ->
            board.copy(buttons = board.buttons.map { button ->
                if (button.loadBoard?.id == boardId) button.copy(loadBoard = null) else button
            })
        }
        val updatedSet = graph.boardSet.copy(
            boardIds = graph.boardSet.boardIds.filterNot { it == boardId },
            updatedAt = Clock.System.now().toEpochMilliseconds()
        )
        return saveBoardSetGraph(BoardSetGraph(updatedSet, remainingBoards)).getOrThrow().boardSet
    }

    suspend fun exportBoardSetAsObzResult(boardSetId: String): ObzExportResult {
        val boardSet = boardSetRepository.getBoardSet(boardSetId)
            ?: return ObzExportResult.Failure(ObzExportErrorCode.ROOT_NOT_FOUND, "Board set '$boardSetId' was not found")
        if (boardSet.kind != ScreenKind.User) {
            return ObzExportResult.Failure(
                ObzExportErrorCode.ROOT_NOT_FOUND,
                "System Screens cannot be exported as vocabulary packages",
            )
        }
        val allBoards = boardRepository.listBoards()
        val graph = BoardSetGraph(boardSet, allBoards.filter { it.id in boardSet.boardIds })
        if (graph.boards.isEmpty()) {
            return ObzExportResult.Failure(ObzExportErrorCode.ROOT_NOT_FOUND, "Board set '$boardSetId' has no boards")
        }
        return obzExporter.exportResult(
            boards = graph.boards,
            rootBoardId = boardSet.rootBoardId,
            loadMedia = { path -> fileStorage?.loadBytes(path) },
            manifestExtensions = if (boardSet.screenSettings.isEmpty) {
                emptyMap()
            } else {
                mapOf(OBF_SCREEN_SETTINGS_EXTENSION to encodeBoardSettings(boardSet.screenSettings))
            }
        )
    }

    private fun generateId(prefix: String): String {
        val now = Clock.System.now().toEpochMilliseconds()
        return "${prefix}_${now}_${Random.nextInt(1000, 9999)}"
    }

}
