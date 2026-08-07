package io.github.jdreioe.wingmate.domain.obf

/**
 * A rectangular field within an OBF board grid.
 *
 * OBF encodes merged cells by repeating the same button id across the occupied
 * cells of `ObfGrid.order`. This module provides the shared span computation and
 * editing operations that every native client (Android, iOS, Linux) uses so that
 * merging/splitting behaves identically everywhere.
 */
data class GridFieldSpan(
    val rows: Int,
    val columns: Int
)

/**
 * A resolved grid field: the anchor cell plus its span and the occupying button.
 * Empty cells are represented with [buttonId] == null and a 1x1 span.
 */
data class ObfGridField(
    val row: Int,
    val column: Int,
    val rowSpan: Int,
    val columnSpan: Int,
    val buttonId: String?
)

/**
 * The normalized `rows` x `columns` matrix, padded with nulls when `order` is
 * ragged or smaller than the declared dimensions.
 */
fun ObfGrid.normalizedOrder(): List<List<String?>> =
    List(rows.coerceAtLeast(0)) { rowIndex ->
        List(columns.coerceAtLeast(0)) { columnIndex ->
            order.getOrNull(rowIndex)?.getOrNull(columnIndex)
        }
    }

/**
 * Expand the normalized matrix into its fields: a button id repeated over a
 * contiguous rectangle becomes a single spanning field; otherwise each cell is
 * its own field.
 */
fun ObfGrid.fieldItems(): List<ObfGridField> {
    val rows = rows.coerceAtLeast(1)
    val columns = columns.coerceAtLeast(1)
    val order = normalizedOrder()
    val visited = mutableSetOf<Pair<Int, Int>>()
    return buildList {
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                if ((row to column) in visited) continue
                val buttonId = order[row][column]
                if (buttonId == null) {
                    visited += row to column
                    add(ObfGridField(row, column, 1, 1, null))
                    continue
                }
                val occurrences = buildList {
                    for (candidateRow in 0 until rows) {
                        for (candidateColumn in 0 until columns) {
                            if (order[candidateRow][candidateColumn] == buttonId) {
                                add(candidateRow to candidateColumn)
                            }
                        }
                    }
                }
                val minRow = occurrences.minOf { it.first }
                val maxRow = occurrences.maxOf { it.first }
                val minColumn = occurrences.minOf { it.second }
                val maxColumn = occurrences.maxOf { it.second }
                val isRectangle = (minRow..maxRow).all { candidateRow ->
                    (minColumn..maxColumn).all { candidateColumn ->
                        order[candidateRow][candidateColumn] == buttonId
                    }
                }
                if (isRectangle) {
                    visited += occurrences
                    add(
                        ObfGridField(
                            row = minRow,
                            column = minColumn,
                            rowSpan = maxRow - minRow + 1,
                            columnSpan = maxColumn - minColumn + 1,
                            buttonId = buttonId
                        )
                    )
                } else {
                    visited += row to column
                    add(ObfGridField(row, column, 1, 1, buttonId))
                }
            }
        }
    }
}

/** The span of the field occupying [row], [column], or 1x1 when empty. */
fun ObfGrid.fieldSpanAt(row: Int, column: Int): GridFieldSpan {
    val buttonId = order.getOrNull(row)?.getOrNull(column)
        ?: return GridFieldSpan(rows = 1, columns = 1)
    val occupiedCells = normalizedOrder().flatMapIndexed { rowIndex, values ->
        values.mapIndexedNotNull { columnIndex, value ->
            if (value == buttonId) rowIndex to columnIndex else null
        }
    }
    if (occupiedCells.isEmpty()) return GridFieldSpan(rows = 1, columns = 1)
    val minRow = occupiedCells.minOf { it.first }
    val maxRow = occupiedCells.maxOf { it.first }
    val minColumn = occupiedCells.minOf { it.second }
    val maxColumn = occupiedCells.maxOf { it.second }
    return GridFieldSpan(
        rows = maxRow - minRow + 1,
        columns = maxColumn - minColumn + 1
    )
}

/** The top-left anchor cell of the field occupying [row], [column]. */
fun ObfGrid.fieldAnchorAt(row: Int, column: Int): Pair<Int, Int>? {
    val buttonId = order.getOrNull(row)?.getOrNull(column) ?: return null
    return normalizedOrder().flatMapIndexed { rowIndex, values ->
        values.mapIndexedNotNull { columnIndex, value ->
            if (value == buttonId) rowIndex to columnIndex else null
        }
    }.minWithOrNull(compareBy({ it.first }, { it.second }))
}

/**
 * Every span the field anchored at [row], [column] may take, given the free cells
 * around it. The anchor cell's own button may grow into adjacent empty cells.
 */
fun ObfGrid.availableFieldSpansAt(row: Int, column: Int): List<GridFieldSpan> {
    if (row !in 0 until rows || column !in 0 until columns) return emptyList()
    val normalized = normalizedOrder()
    val existingId = normalized[row][column]
    return buildList {
        for (rowSpan in 1..(rows - row)) {
            for (columnSpan in 1..(columns - column)) {
                val available = (row until row + rowSpan).all { rowIndex ->
                    (column until column + columnSpan).all { columnIndex ->
                        normalized[rowIndex][columnIndex] == null ||
                            normalized[rowIndex][columnIndex] == existingId
                    }
                }
                if (available) add(GridFieldSpan(rowSpan, columnSpan))
            }
        }
    }.sortedWith(compareBy<GridFieldSpan> { it.rows * it.columns }.thenBy { it.rows }.thenBy { it.columns })
}

/**
 * Grow/shrink the field at [row], [column] to the given span, writing [buttonId]
 * over the new rectangle and clearing the old cells. Returns null when the target
 * is out of bounds or overlaps another occupied field.
 */
fun ObfGrid.withFieldSpan(
    row: Int,
    column: Int,
    buttonId: String,
    rowSpan: Int,
    columnSpan: Int
): ObfGrid? {
    if (row !in 0 until rows || column !in 0 until columns) return null
    val safeRowSpan = rowSpan.coerceAtLeast(1)
    val safeColumnSpan = columnSpan.coerceAtLeast(1)
    if (row + safeRowSpan > rows || column + safeColumnSpan > columns) return null
    val normalized = normalizedOrder()
    val existingId = normalized[row][column]
    val targetIsAvailable = (row until row + safeRowSpan).all { rowIndex ->
        (column until column + safeColumnSpan).all { columnIndex ->
            normalized[rowIndex][columnIndex] == null || normalized[rowIndex][columnIndex] == existingId
        }
    }
    if (!targetIsAvailable) return null
    val cleared = normalized.map { values ->
        values.map { value -> if (value == existingId && existingId != null) null else value }.toMutableList()
    }
    for (rowIndex in row until row + safeRowSpan) {
        for (columnIndex in column until column + safeColumnSpan) {
            cleared[rowIndex][columnIndex] = buttonId
        }
    }
    return copy(order = cleared)
}

/** Resize the grid, refusing when occupied cells would be removed. */
fun ObfGrid.resized(newRows: Int, newColumns: Int): ObfGrid? {
    if (newRows !in 1..20 || newColumns !in 1..20) return null
    val normalized = normalizedOrder()
    val removesOccupiedCell = normalized.indices.any { rowIndex ->
        normalized[rowIndex].indices.any { columnIndex ->
            normalized[rowIndex][columnIndex] != null &&
                (rowIndex >= newRows || columnIndex >= newColumns)
        }
    }
    if (removesOccupiedCell) return null
    return copy(
        rows = newRows,
        columns = newColumns,
        order = List(newRows) { rowIndex ->
            List(newColumns) { columnIndex ->
                normalized.getOrNull(rowIndex)?.getOrNull(columnIndex)
            }
        }
    )
}

/**
 * Swap the fields at [fromRow], [fromColumn] and [toRow], [toColumn], preserving
 * each field's span. Returns null when either field cannot be placed at the other's
 * anchor (out of bounds or occupied).
 */
fun ObfGrid.moveOrSwapField(
    fromRow: Int,
    fromColumn: Int,
    toRow: Int,
    toColumn: Int
): ObfGrid? {
    if (fromRow !in 0 until rows || fromColumn !in 0 until columns) return null
    if (toRow !in 0 until rows || toColumn !in 0 until columns) return null
    val normalized = normalizedOrder()
    val sourceId = normalized[fromRow][fromColumn] ?: return null
    val targetId = normalized[toRow][toColumn]
    if (sourceId == targetId) return this

    fun anchorAndSpan(buttonId: String): Pair<Pair<Int, Int>, GridFieldSpan>? {
        val cells = normalized.flatMapIndexed { rowIndex, values ->
            values.mapIndexedNotNull { columnIndex, value ->
                if (value == buttonId) rowIndex to columnIndex else null
            }
        }
        if (cells.isEmpty()) return null
        val minRow = cells.minOf { it.first }
        val maxRow = cells.maxOf { it.first }
        val minColumn = cells.minOf { it.second }
        val maxColumn = cells.maxOf { it.second }
        val rectangular = (minRow..maxRow).all { rowIndex ->
            (minColumn..maxColumn).all { columnIndex ->
                normalized[rowIndex][columnIndex] == buttonId
            }
        }
        if (!rectangular) return null
        return (minRow to minColumn) to GridFieldSpan(
            rows = maxRow - minRow + 1,
            columns = maxColumn - minColumn + 1
        )
    }

    val (sourceAnchor, sourceSpan) = anchorAndSpan(sourceId) ?: return null
    val targetPlacement = targetId?.let(::anchorAndSpan)
    val targetAnchor = targetPlacement?.first ?: (toRow to toColumn)
    val targetSpan = targetPlacement?.second
    val clearedIds = setOfNotNull(sourceId, targetId)
    val updated = normalized.map { row ->
        row.map { value -> if (value in clearedIds) null else value }.toMutableList()
    }

    fun canPlace(anchor: Pair<Int, Int>, span: GridFieldSpan): Boolean {
        val (row, column) = anchor
        if (row < 0 || column < 0 || row + span.rows > rows || column + span.columns > columns) {
            return false
        }
        return (row until row + span.rows).all { rowIndex ->
            (column until column + span.columns).all { columnIndex ->
                updated[rowIndex][columnIndex] == null
            }
        }
    }

    if (!canPlace(targetAnchor, sourceSpan)) return null
    if (targetSpan != null && !canPlace(sourceAnchor, targetSpan)) return null
    if (targetSpan != null) {
        val sourceDestinationRows = targetAnchor.first until targetAnchor.first + sourceSpan.rows
        val sourceDestinationColumns = targetAnchor.second until targetAnchor.second + sourceSpan.columns
        val targetDestinationRows = sourceAnchor.first until sourceAnchor.first + targetSpan.rows
        val targetDestinationColumns = sourceAnchor.second until sourceAnchor.second + targetSpan.columns
        val destinationsOverlap = sourceDestinationRows.any { it in targetDestinationRows } &&
            sourceDestinationColumns.any { it in targetDestinationColumns }
        if (destinationsOverlap) return null
    }

    fun place(buttonId: String, anchor: Pair<Int, Int>, span: GridFieldSpan) {
        val (row, column) = anchor
        for (rowIndex in row until row + span.rows) {
            for (columnIndex in column until column + span.columns) {
                updated[rowIndex][columnIndex] = buttonId
            }
        }
    }

    place(sourceId, targetAnchor, sourceSpan)
    if (targetId != null && targetSpan != null) place(targetId, sourceAnchor, targetSpan)
    return copy(order = updated)
}
