package io.github.jdreioe.wingmate.domain.obf

/**
 * A Screen laid out for paper (#227): one [PrintedPage] per Page, root first, so a
 * printed board set works as a low-tech fallback when the device cannot be used.
 * Paper cannot navigate, so a Button that opens another Page names it and its page number.
 */
data class PrintedPage(
    val number: Int,
    val title: String,
    val rows: Int,
    val columns: Int,
    val cells: List<PrintedCell>,
)

data class PrintedCell(
    val row: Int,
    val column: Int,
    val rowSpan: Int,
    val columnSpan: Int,
    val label: String,
    val backgroundColor: String?,
    val image: ObfImage?,
    val opens: PrintedLink?,
)

data class PrintedLink(val title: String, val pageNumber: Int)

/** Pages without a grid print their visible Buttons in this many columns. */
private const val FALLBACK_COLUMNS = 4

fun printableScreen(graph: BoardSetGraph, locale: String?): List<PrintedPage> {
    val pages = (listOfNotNull(graph.rootBoard) + graph.boards)
        .distinctBy { it.id }
    val numbers = pages.withIndex().associate { (index, page) -> page.id to index + 1 }
    fun title(page: ObfBoard) = resolveObfLocalizedString(page.strings, locale, page.name).orEmpty().ifBlank { page.id }

    return pages.map { page ->
        val buttonsById = page.buttons
            .filter { isBoardButtonVisible(it, isEditMode = false, showHiddenButtons = false) }
            .associateBy { it.id }
        val imagesById = page.images.associateBy { it.id }
        val grid = page.grid
        val fields = if (grid != null && !page.isAbsoluteLayout) {
            grid.fieldItems().filter { it.buttonId in buttonsById }
        } else {
            buttonsById.keys.mapIndexed { index, id ->
                ObfGridField(index / FALLBACK_COLUMNS, index % FALLBACK_COLUMNS, 1, 1, id)
            }
        }
        val cells = fields.map { field ->
            val button = buttonsById.getValue(field.buttonId!!)
            val target = graph.resolveLinkedBoard(button.loadBoard)
            PrintedCell(
                row = field.row,
                column = field.column,
                rowSpan = field.rowSpan,
                columnSpan = field.columnSpan,
                label = resolveObfLocalizedString(page.strings, locale, button.label).orEmpty()
                    .ifBlank { resolveObfLocalizedString(page.strings, locale, button.vocalization).orEmpty() },
                backgroundColor = button.backgroundColor,
                image = button.imageId?.let(imagesById::get),
                opens = target?.let { linked -> numbers[linked.id]?.let { PrintedLink(title(linked), it) } },
            )
        }
        PrintedPage(
            number = numbers.getValue(page.id),
            title = title(page),
            rows = if (grid != null && !page.isAbsoluteLayout) grid.rows else (fields.maxOfOrNull { it.row } ?: 0) + 1,
            columns = if (grid != null && !page.isAbsoluteLayout) grid.columns else FALLBACK_COLUMNS,
            cells = cells,
        )
    }
}
