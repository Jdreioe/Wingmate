package io.github.jdreioe.wingmate.desktop

import io.github.jdreioe.wingmate.domain.obf.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.time.Clock

@Serializable
data class EditorCell(
    val row: Int, val column: Int, val rowSpan: Int, val columnSpan: Int,
    val label: String = "", val vocalization: String = "", val color: String = "",
    val hidden: Boolean = false, val linkedPage: String = "", val occupied: Boolean = false,
)
@Serializable
data class EditorPage(val id: String, val name: String)
@Serializable
data class EditorView(
    val screenName: String, val pageId: String, val pageName: String,
    val rootPageId: String, val pages: List<EditorPage>,
    val rows: Int, val columns: Int, val cells: List<EditorCell>, val dirty: Boolean,
    val unsupportedElements: List<String>,
)
@Serializable
data class EditorCommand(
    val operation: String, val id: String = "", val name: String = "",
    val rows: Int = 3, val columns: Int = 4, val row: Int = 0, val column: Int = 0,
    val toRow: Int = 0, val toColumn: Int = 0, val rowSpan: Int = 1, val columnSpan: Int = 1,
    val label: String = "", val vocalization: String = "", val color: String = "",
    val hidden: Boolean = false, val linkedPage: String = "",
)

/** The draft never reaches persistence before Save. All layout rules stay in shared Kotlin. */
internal class DesktopEditor(private val store: DesktopStore) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private var draft: BoardSetGraph? = null
    private var original: BoardSetGraph? = null
    private var pageId: String = ""

    suspend fun command(value: String): String {
        val command = json.decodeFromString<EditorCommand>(value)
        when (command.operation) {
            "begin" -> {
                check(draft == null) { "Save or discard the current draft first" }
                val set = store.getBoardSet(command.id) ?: error("Screen not found")
                requireEditable(set)
                val graph = BoardSetGraph(set, set.boardIds.map { store.getBoard(it) ?: error("Page not found") })
                graph.requireValid()
                original = graph
                draft = graph
                pageId = set.rootBoardId
            }
            "new" -> {
                check(draft == null) { "Save or discard the current draft first" }
                dimensions(command.rows, command.columns)
                val page = newPage("Starting Page", command.rows, command.columns)
                val now = Clock.System.now().toEpochMilliseconds()
                draft = BoardSetGraph(ObfBoardSet(id("screen"), command.name.ifBlank { "New Screen" }, page.id,
                    listOf(page.id), createdAt = now, updatedAt = now), listOf(page))
                original = null
                pageId = page.id
            }
            "discard" -> { draft = null; original = null; return "{\"ok\":true}" }
            "save" -> {
                val graph = requireDraft()
                original?.let {
                    val current = store.getBoardSet(it.boardSet.id) ?: error("Screen was removed")
                    requireEditable(current)
                    check(current == it.boardSet && it.boards.all { board -> store.getBoard(board.id) == board }) {
                        "Screen changed since editing began; discard and reopen it"
                    }
                }
                graph.requireValid()
                val state = store.snapshot()
                val ids = graph.boardSet.boardIds.toSet()
                store.restore(state.copy(
                    boards = state.boards.filterNot { it.id in ids } + graph.boards,
                    boardSets = state.boardSets.filterNot { it.id == graph.boardSet.id } +
                        graph.boardSet.copy(updatedAt = Clock.System.now().toEpochMilliseconds()),
                ))
                draft = null
                original = null
                return json.encodeToString(mapOf("id" to graph.boardSet.id))
            }
            else -> edit(command)
        }
        return json.encodeToString(view())
    }

    private fun requireEditable(set: ObfBoardSet) {
        require(!set.isLocked) { "This Screen is locked" }
        require(set.kind == ScreenKind.User) { "System Screens cannot be edited here" }
    }
    private fun requireDraft() = draft ?: error("No editor draft is open")
    private fun dimensions(rows: Int, columns: Int) {
        require(rows in 1..20 && columns in 1..20) { "Grid dimensions must be between 1 and 20" }
    }
    private fun newPage(name: String, rows: Int, columns: Int) = ObfBoard(
        format = "open-board-0.1", id = id("page"), name = name,
        grid = ObfGrid(rows, columns, List(rows) { List(columns) { null } }),
    )
    private fun id(prefix: String) = "${prefix}_${Clock.System.now().toEpochMilliseconds()}_${Random.nextLong()}"

    private fun edit(c: EditorCommand) {
        val graph = requireDraft()
        val page = graph.boardsById[pageId] ?: error("Page not found")
        val grid = page.grid ?: error("Page has no Grid")
        val updated = when (c.operation) {
            "page" -> {
                require(c.id in graph.boardsById) { "Page not found" }
                pageId = c.id
                graph
            }
            "renameScreen" -> { require(c.name.isNotBlank()) { "Enter a Screen name" }; renameDraftBoardSet(graph, c.name) }
            "renamePage" -> { require(c.name.isNotBlank()) { "Enter a Page name" }; renameDraftBoard(graph, pageId, c.name) }
            "addPage" -> {
                dimensions(c.rows, c.columns)
                val added = newPage(c.name.ifBlank { "New Page" }, c.rows, c.columns)
                pageId = added.id
                graph.copy(boardSet = graph.boardSet.copy(boardIds = graph.boardSet.boardIds + added.id), boards = graph.boards + added)
            }
            "root" -> graph.copy(boardSet = graph.boardSet.copy(rootBoardId = pageId))
            "resizeGrid" -> {
                dimensions(c.rows, c.columns)
                resizeDraftBoard(graph, pageId, c.rows, c.columns).also {
                    require(it.boardsById[pageId]?.grid?.let { g -> g.rows == c.rows && g.columns == c.columns } == true) {
                        "The Grid cannot shrink over existing Buttons"
                    }
                }
            }
            "button", "clear", "move", "span" -> {
                require(c.row in 0 until grid.rows && c.column in 0 until grid.columns) { "Cell is outside the Grid" }
                val anchor = grid.fieldAnchorAt(c.row, c.column) ?: (c.row to c.column)
                when (c.operation) {
                    "button" -> {
                        require(c.linkedPage.isEmpty() || c.linkedPage in graph.boardsById) { "Linked Page not found" }
                        val existingId = grid.order[anchor.first][anchor.second]
                        val existing = page.buttons.firstOrNull { it.id == existingId }
                        val button = (existing ?: ObfButton(id = id("button"))).copy(
                            label = c.label, vocalization = c.vocalization.ifBlank { null },
                            backgroundColor = c.color.ifBlank { null }, hidden = c.hidden,
                            loadBoard = if (c.linkedPage == (existing?.loadBoard?.id ?: "")) existing?.loadBoard
                                else c.linkedPage.takeIf { it.isNotBlank() }?.let { ObfLoadBoard(id = it) },
                        )
                        val span = grid.fieldSpanAt(anchor.first, anchor.second)
                        val changed = page.copy(buttons = page.buttons.filterNot { it.id == button.id } + button,
                            grid = grid.withFieldSpan(anchor.first, anchor.second, button.id, span.rows, span.columns)
                                ?: error("Button does not fit"))
                        graph.copy(boards = graph.boards.map { if (it.id == pageId) changed else it })
                    }
                    "clear" -> clearDraftCell(graph, pageId, anchor.first, anchor.second)
                    "move" -> moveDraftField(graph, pageId, anchor.first, anchor.second, c.toRow, c.toColumn).also {
                        require(it != graph || (anchor.first == c.toRow && anchor.second == c.toColumn)) { "Button cannot move to that Cell" }
                    }
                    else -> resizeDraftField(graph, pageId, anchor.first, anchor.second, c.rowSpan, c.columnSpan).also {
                        val span = it.boardsById[pageId]?.grid?.fieldSpanAt(anchor.first, anchor.second)
                        require(span?.rows == c.rowSpan && span.columns == c.columnSpan) { "Button does not fit that span" }
                    }
                }
            }
            else -> error("Unknown editor operation")
        }
        updated.requireValid()
        draft = updated
    }

    private fun view(): EditorView {
        val graph = requireDraft()
        val page = graph.boardsById[pageId] ?: error("Page not found")
        val grid = page.grid ?: error("Page has no Grid")
        return EditorView(graph.boardSet.name, pageId, page.name.orEmpty(), graph.boardSet.rootBoardId,
            graph.boards.map { EditorPage(it.id, it.name.orEmpty()) }, grid.rows, grid.columns,
            grid.fieldItems().map { field ->
                val button = page.buttons.firstOrNull { it.id == field.buttonId }
                EditorCell(field.row, field.column, field.rowSpan, field.columnSpan,
                    button?.label.orEmpty(), button?.vocalization.orEmpty(), button?.backgroundColor.orEmpty(),
                    button?.hidden ?: false, button?.loadBoard?.id.orEmpty(), button != null)
            }, graph != original, page.pageElements().map { "${it.id} (row ${it.row + 1}, column ${it.column + 1})" })
    }
}
