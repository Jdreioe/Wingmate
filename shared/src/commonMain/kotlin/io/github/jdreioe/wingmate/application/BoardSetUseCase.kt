package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.BoardRepository
import io.github.jdreioe.wingmate.domain.BoardSetRepository
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
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
    private val featureUsageReporter: FeatureUsageReporter
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

    suspend fun createBoardSet(
        name: String,
        rows: Int,
        columns: Int
    ): ObfBoardSet {
        val now = Clock.System.now().toEpochMilliseconds()
        val boardId = generateId("board")
        val boardSetId = generateId("set")

        val normalizedRows = rows.coerceAtLeast(1)
        val normalizedColumns = columns.coerceAtLeast(1)

        val board = ObfBoard(
            format = "open-board-0.1",
            id = boardId,
            name = "Hjem",
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
}
