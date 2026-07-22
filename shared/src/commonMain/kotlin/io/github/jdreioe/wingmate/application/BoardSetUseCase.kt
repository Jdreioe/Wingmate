package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.BoardRepository
import io.github.jdreioe.wingmate.domain.BoardSetRepository
import io.github.jdreioe.wingmate.domain.FileStorage
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.domain.obf.BoardSetGraph
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfGrid
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    suspend fun listBoardSets(): List<ObfBoardSet> = boardSetRepository.listBoardSets()

    suspend fun getBoardSet(boardSetId: String): ObfBoardSet? = boardSetRepository.getBoardSet(boardSetId)

    suspend fun getBoard(boardId: String): ObfBoard? = boardRepository.getBoard(boardId)

    suspend fun listBoards(boardSetId: String): List<ObfBoard> {
        val boardSet = boardSetRepository.getBoardSet(boardSetId) ?: return emptyList()
        return boardSet.boardIds.mapNotNull { boardRepository.getBoard(it) }
    }

    suspend fun loadBoardSetGraph(boardSetId: String): BoardSetGraph? {
        val boardSet = boardSetRepository.getBoardSet(boardSetId) ?: return null
        val boards = boardSet.boardIds.mapNotNull { boardRepository.getBoard(it) }
        return BoardSetGraph(boardSet = boardSet, boards = boards)
    }

    /**
     * Validate and persist a complete editor draft. The domain serialization is
     * unchanged; this method is the single commit boundary used by the UI.
     */
    suspend fun saveBoardSetGraph(graph: BoardSetGraph): Result<BoardSetGraph> = runCatching {
        val canonicalGraph = graph.canonicalizeBoardLinks()
        validateGraph(canonicalGraph)
        val now = Clock.System.now().toEpochMilliseconds()
        val updatedSet = canonicalGraph.boardSet.copy(updatedAt = now)
        val previousSet = boardSetRepository.getBoardSet(canonicalGraph.boardSet.id)
        val previousBoards = previousSet?.boardIds
            ?.mapNotNull { boardRepository.getBoard(it) }
            .orEmpty()
        val newBoardIds = canonicalGraph.boards.map { it.id }.toSet()
        val removedBoardIds = previousSet?.boardIds.orEmpty().filterNot { it in newBoardIds }

        try {
            canonicalGraph.boards.forEach { boardRepository.saveBoard(it) }
            boardSetRepository.saveBoardSet(updatedSet)
            removedBoardIds.forEach { boardRepository.deleteBoard(it) }
        } catch (error: Throwable) {
            previousBoards.forEach { boardRepository.saveBoard(it) }
            previousSet?.let { boardSetRepository.saveBoardSet(it) }
            newBoardIds.filterNot { id -> previousBoards.any { it.id == id } }
                .forEach { boardRepository.deleteBoard(it) }
            throw error
        }
        canonicalGraph.copy(boardSet = updatedSet).also { speechCache?.cacheGraph(it) }
    }

    suspend fun deleteBoardSet(boardSetId: String) {
        val graph = loadBoardSetGraph(boardSetId) ?: return
        graph.boards.forEach { boardRepository.deleteBoard(it.id) }
        boardSetRepository.deleteBoardSet(boardSetId)
    }

    suspend fun duplicateBoardSet(boardSetId: String): ObfBoardSet? {
        val source = loadBoardSetGraph(boardSetId) ?: return null
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

    suspend fun toggleLocked(boardSetId: String): ObfBoardSet? {
        val boardSet = boardSetRepository.getBoardSet(boardSetId) ?: return null
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
        columns: Int
    ): ObfBoard? {
        val boardSet = boardSetRepository.getBoardSet(boardSetId) ?: return null
        if (boardSet.isLocked) return null

        val normalizedRows = rows.coerceAtLeast(1)
        val normalizedColumns = columns.coerceAtLeast(1)
        val boardId = generateId("board")

        val board = ObfBoard(
            format = "open-board-0.1",
            id = boardId,
            name = name,
            grid = ObfGrid(
                rows = normalizedRows,
                columns = normalizedColumns,
                order = List(normalizedRows) { List(normalizedColumns) { null } }
            )
        )

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

    suspend fun upsertBoardCellButton(
        boardSetId: String,
        boardId: String,
        row: Int,
        column: Int,
        label: String,
        vocalization: String?,
        imageUrl: String?
    ): ObfBoard? {
        val boardSet = boardSetRepository.getBoardSet(boardSetId) ?: return null
        if (boardSet.isLocked) return null
        if (boardId !in boardSet.boardIds) return null

        val board = boardRepository.getBoard(boardId) ?: return null
        val grid = board.grid ?: return null
        if (row !in 0 until grid.rows || column !in 0 until grid.columns) return null

        val existingButtonId = grid.order.getOrNull(row)?.getOrNull(column)
        val existingButton = existingButtonId?.let { id -> board.buttons.find { it.id == id } }

        val targetButtonId = existingButton?.id ?: generateId("btn")
        val normalizedLabel = label.trim()
        if (normalizedLabel.isEmpty()) return null

        val normalizedImageUrl = imageUrl?.trim()?.ifBlank { null }
        var targetImageId = existingButton?.imageId
        val updatedImages = when {
            normalizedImageUrl == null -> {
                targetImageId = null
                board.images
            }
            targetImageId != null -> {
                board.images.map { image ->
                    if (image.id == targetImageId) {
                        image.copy(url = normalizedImageUrl, path = null, data = null)
                    } else {
                        image
                    }
                }
            }
            else -> {
                val imageId = generateId("img")
                targetImageId = imageId
                board.images + ObfImage(
                    id = imageId,
                    url = normalizedImageUrl,
                    path = null,
                    data = null
                )
            }
        }

        val updatedButton = (existingButton ?: ObfButton(id = targetButtonId)).copy(
            label = normalizedLabel,
            vocalization = vocalization?.trim()?.ifBlank { null },
            imageId = targetImageId
        )

        val updatedButtons = if (existingButton == null) {
            board.buttons + updatedButton
        } else {
            board.buttons.map { if (it.id == updatedButton.id) updatedButton else it }
        }

        val updatedGrid = grid.copy(order = replaceGridCell(grid.order, row, column, targetButtonId))
        val updatedBoard = board.copy(
            buttons = updatedButtons,
            grid = updatedGrid,
            images = updatedImages
        )

        boardRepository.saveBoard(updatedBoard)
        boardSetRepository.saveBoardSet(
            boardSet.copy(updatedAt = Clock.System.now().toEpochMilliseconds())
        )
        featureUsageReporter.reportEvent(
            FeatureUsageEvents.BOARD_CELL_UPSERTED,
            "row" to row.toString(),
            "column" to column.toString(),
            "has_image" to (normalizedImageUrl != null).toString()
        )
        speechCache?.cacheField(updatedBoard, updatedButton)
        return updatedBoard
    }

    suspend fun clearBoardCellButton(
        boardSetId: String,
        boardId: String,
        row: Int,
        column: Int
    ): ObfBoard? {
        val boardSet = boardSetRepository.getBoardSet(boardSetId) ?: return null
        if (boardSet.isLocked) return null
        if (boardId !in boardSet.boardIds) return null

        val board = boardRepository.getBoard(boardId) ?: return null
        val grid = board.grid ?: return null
        if (row !in 0 until grid.rows || column !in 0 until grid.columns) return null

        val buttonId = grid.order.getOrNull(row)?.getOrNull(column)
        val removedButton = buttonId?.let { id -> board.buttons.find { it.id == id } }
        val removedImageId = removedButton?.imageId
        val updatedOrder = replaceGridCell(grid.order, row, column, null)
        val updatedButtons = if (!buttonId.isNullOrBlank() && !isButtonReferenced(updatedOrder, buttonId)) {
            board.buttons.filterNot { it.id == buttonId }
        } else {
            board.buttons
        }
        val updatedImages = if (
            !removedImageId.isNullOrBlank() &&
            updatedButtons.none { it.imageId == removedImageId }
        ) {
            board.images.filterNot { it.id == removedImageId }
        } else {
            board.images
        }

        val updatedBoard = board.copy(
            buttons = updatedButtons,
            grid = grid.copy(order = updatedOrder),
            images = updatedImages
        )

        boardRepository.saveBoard(updatedBoard)
        boardSetRepository.saveBoardSet(
            boardSet.copy(updatedAt = Clock.System.now().toEpochMilliseconds())
        )
        featureUsageReporter.reportEvent(
            FeatureUsageEvents.BOARD_CELL_CLEARED,
            "row" to row.toString(),
            "column" to column.toString()
        )
        return updatedBoard
    }

    suspend fun addButtonToRootBoard(
        boardSetId: String,
        label: String,
        vocalization: String?
    ): ObfBoard? {
        val boardSet = boardSetRepository.getBoardSet(boardSetId) ?: return null
        if (boardSet.isLocked) return null

        val board = boardRepository.getBoard(boardSet.rootBoardId) ?: return null
        val buttonId = generateId("btn")
        val button = ObfButton(
            id = buttonId,
            label = label,
            vocalization = vocalization?.ifBlank { null }
        )

        val updatedGrid = board.grid?.let { placeButtonInFirstEmptyCell(it, buttonId) }
        val updatedBoard = board.copy(
            buttons = board.buttons + button,
            grid = updatedGrid ?: board.grid
        )

        boardRepository.saveBoard(updatedBoard)
        boardSetRepository.saveBoardSet(
            boardSet.copy(updatedAt = Clock.System.now().toEpochMilliseconds())
        )
        return updatedBoard
    }

    suspend fun exportRootBoardAsObf(boardSetId: String): String? {
        val boardSet = boardSetRepository.getBoardSet(boardSetId) ?: return null
        val rootBoard = boardRepository.getBoard(boardSet.rootBoardId) ?: return null
        return json.encodeToString(rootBoard)
    }

    suspend fun exportBoardSetAsObz(boardSetId: String): ByteArray? {
        val boardSet = boardSetRepository.getBoardSet(boardSetId) ?: return null
        val allBoards = boardRepository.listBoards()
        val graph = BoardSetGraph(boardSet, allBoards.filter { it.id in boardSet.boardIds })
        if (graph.boards.isEmpty()) return null

        val soundBytes = mutableMapOf<String, ByteArray>()
        if (fileStorage != null) {
            for (board in graph.boards) {
                for (button in board.buttons) {
                    val soundId = button.soundId ?: continue
                    if (soundId in soundBytes) continue
                    // Try multiple storage path patterns
                    val candidates = listOf(
                        "sounds/$soundId",
                        "$boardSetId/sounds/$soundId",
                        "boardsets/$boardSetId/sounds/$soundId"
                    )
                    for (path in candidates) {
                        val bytes = fileStorage.loadBytes(path)
                        if (bytes != null) {
                            soundBytes[soundId] = bytes
                            break
                        }
                    }
                }
            }
        }

        return obzExporter.export(
            boards = graph.boards,
            rootBoardId = boardSet.rootBoardId,
            loadMedia = { path -> fileStorage?.loadBytes(path) },
            soundBytes = soundBytes
        )
    }

    suspend fun getRootBoard(boardSetId: String): ObfBoard? {
        val boardSet = boardSetRepository.getBoardSet(boardSetId) ?: return null
        return boardRepository.getBoard(boardSet.rootBoardId)
    }

    private fun placeButtonInFirstEmptyCell(grid: ObfGrid, buttonId: String): ObfGrid {
        val mutable = grid.order.map { it.toMutableList() }.toMutableList()
        for (row in mutable.indices) {
            for (column in mutable[row].indices) {
                if (mutable[row][column] == null) {
                    mutable[row][column] = buttonId
                    return grid.copy(order = mutable.map { it.toList() })
                }
            }
        }
        return grid
    }

    private fun replaceGridCell(
        order: List<List<String?>>,
        row: Int,
        column: Int,
        value: String?
    ): List<List<String?>> {
        return order.mapIndexed { rowIndex, rowValues ->
            if (rowIndex != row) {
                rowValues
            } else {
                rowValues.mapIndexed { colIndex, cell ->
                    if (colIndex == column) value else cell
                }
            }
        }
    }

    private fun isButtonReferenced(order: List<List<String?>>, buttonId: String): Boolean {
        return order.any { row -> row.any { it == buttonId } }
    }

    private fun generateId(prefix: String): String {
        val now = Clock.System.now().toEpochMilliseconds()
        return "${prefix}_${now}_${Random.nextInt(1000, 9999)}"
    }

    private fun validateGraph(graph: BoardSetGraph) {
        val boardIds = graph.boards.map { it.id }
        require(boardIds.size == boardIds.toSet().size) { "Board IDs must be unique." }
        require(graph.boardSet.rootBoardId in boardIds) { "The root board must belong to the board set." }
        require(graph.boardSet.boardIds.toSet() == boardIds.toSet()) { "Board set membership is inconsistent." }

        graph.boards.forEach { board ->
            val grid = board.grid ?: return@forEach
            require(grid.rows > 0 && grid.columns > 0) { "Board grids must have positive dimensions." }
            require(grid.order.size == grid.rows) { "Board grid row count is inconsistent." }
            require(grid.order.all { it.size == grid.columns }) { "Board grid column count is inconsistent." }
            val buttonIds = board.buttons.map { it.id }.toSet()
            require(grid.order.flatten().filterNotNull().all { it in buttonIds }) {
                "Board grid references a missing button."
            }
            board.buttons.forEach { button ->
                val targetId = button.loadBoard?.id
                require(targetId == null || targetId in boardIds) { "A board link points outside the board set." }
            }
        }
    }
}
