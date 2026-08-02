package io.github.jdreioe.wingmate.ui

import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfGrid

internal fun boardCellButton(
    grid: ObfGrid,
    buttonsById: Map<String, ObfButton>,
    isVisible: (ObfButton) -> Boolean,
    row: Int,
    column: Int
): ObfButton? =
    grid.order.getOrNull(row)?.getOrNull(column)?.let { buttonsById[it] }?.takeIf(isVisible)

internal fun firstFocusableBoardCell(
    grid: ObfGrid,
    buttonsById: Map<String, ObfButton>,
    isVisible: (ObfButton) -> Boolean
): Pair<Int, Int>? {
    for (row in 0 until grid.rows) {
        for (column in 0 until grid.columns) {
            if (boardCellButton(grid, buttonsById, isVisible, row, column) != null) return row to column
        }
    }
    return null
}

/**
 * Moves one step in the given direction, skipping cells that have no visible
 * button. When [current] is null, the first focusable cell (row-major) is
 * returned. Stops at the grid edge and returns [current] if there is no
 * focusable cell in the requested direction.
 */
internal fun stepFocusableBoardCell(
    grid: ObfGrid,
    buttonsById: Map<String, ObfButton>,
    isVisible: (ObfButton) -> Boolean,
    current: Pair<Int, Int>?,
    deltaRow: Int,
    deltaColumn: Int
): Pair<Int, Int>? {
    if (current == null) return firstFocusableBoardCell(grid, buttonsById, isVisible)
    var r = current.first
    var c = current.second
    for (i in 0 until grid.rows + grid.columns) {
        r += deltaRow
        c += deltaColumn
        if (r !in 0 until grid.rows || c !in 0 until grid.columns) return current
        if (boardCellButton(grid, buttonsById, isVisible, r, c) != null) return r to c
    }
    return current
}