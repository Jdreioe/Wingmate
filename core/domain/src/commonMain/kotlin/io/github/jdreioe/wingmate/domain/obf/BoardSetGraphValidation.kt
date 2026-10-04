package io.github.jdreioe.wingmate.domain.obf

/**
 * Checks every invariant required before a Screen graph crosses a persistence seam.
 * Import, editor saves, and backup restore all use this same definition.
 */
fun BoardSetGraph.requireValid() {
    val boardIds = boards.map { it.id }
    require(boardIds.size == boardIds.toSet().size) { "Page IDs must be unique." }
    require(boardSet.rootBoardId in boardIds) { "The starting Page must belong to the Screen." }
    require(boardSet.boardIds.toSet() == boardIds.toSet()) { "Screen membership is inconsistent." }

    boards.forEach { board ->
        board.requireValidPageElements()

        val grid = board.grid ?: return@forEach
        require(grid.rows > 0 && grid.columns > 0) { "Page grids must have positive dimensions." }
        require(grid.order.size == grid.rows) { "Page grid row count is inconsistent." }
        require(grid.order.all { it.size == grid.columns }) { "Page grid column count is inconsistent." }
        val buttonIds = board.buttons.map { it.id }.toSet()
        require(grid.order.flatten().filterNotNull().all { it in buttonIds }) {
            "Page grid references a missing Button."
        }
        board.buttons.forEach { button ->
            val targetId = button.loadBoard?.id
            require(targetId == null || targetId in boardIds) { "A Page link points outside the Screen." }
        }
    }
}

private fun ObfBoard.requireValidPageElements() {
    val elements = pageElements()
    require(elements.map { it.id }.distinct().size == elements.size) {
        "Page element IDs must be unique."
    }
    require(elements.all {
        it.row >= 0 && it.column >= 0 && it.rowSpan > 0 && it.columnSpan > 0
    }) { "Page elements must have valid responsive grid coordinates." }

    val buttonIds = buttons.map { it.id }.toSet()
    elements.forEach { element ->
        when (val content = element.content) {
            is PageElementContent.ActionStrip -> require(content.configuration.buttonIds.all { it in buttonIds }) {
                "Action strip references a missing Button."
            }
            is PageElementContent.Unsupported -> require(content.type !in PageElementTypes.supported) {
                "${content.type} configuration is invalid."
            }
            else -> Unit
        }
    }
}
