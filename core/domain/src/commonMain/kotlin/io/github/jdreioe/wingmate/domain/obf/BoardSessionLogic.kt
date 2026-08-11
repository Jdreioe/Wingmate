package io.github.jdreioe.wingmate.domain.obf

/**
 * Shared board-session logic used identically by every native client
 * (Android Compose, iOS SwiftUI, Linux). Keeping these here means prediction
 * insertion, sentence composition, activation/return behavior, and edit-mode
 * cell taps behave the same everywhere.
 */

/**
 * Text inserted when a prediction button is activated: completes the current
 * word when the suggestion extends it, otherwise inserts a new word with a
 * leading space.
 */
fun nGramPredictionInsertion(sentence: String, suggestion: String): String {
    val word = suggestion.trim()
    if (word.isEmpty()) return ""
    val prefix = sentence.takeLastWhile { !it.isWhitespace() }
    return when {
        prefix.isEmpty() -> word
        word.startsWith(prefix, ignoreCase = true) -> word.drop(prefix.length)
        else -> " $word"
    }
}

/** Whether the activation behavior adds the selection to the sentence. */
fun shouldAddBoardSelection(behavior: BoardActivationBehavior): Boolean =
    behavior != BoardActivationBehavior.SpeakOnly

/** Whether the activation behavior speaks the selection. */
fun shouldSpeakBoardSelection(behavior: BoardActivationBehavior): Boolean =
    behavior != BoardActivationBehavior.AddOnly

/**
 * Resolve the board to show after a selection, given the return behavior and the
 * navigation history. Returns the next board id and the updated stack.
 */
fun applyBoardReturnBehavior(
    behavior: BoardReturnBehavior,
    currentBoardId: String?,
    boardStack: List<String>,
    rootBoardId: String
): Pair<String?, List<String>> = when (behavior) {
    BoardReturnBehavior.Stay -> currentBoardId to boardStack
    BoardReturnBehavior.Previous -> {
        if (boardStack.isEmpty()) {
            currentBoardId to boardStack
        } else {
            boardStack.last() to boardStack.dropLast(1)
        }
    }
    BoardReturnBehavior.StartPage -> rootBoardId to emptyList()
}

/**
 * Undo the last AAC selection. Word-based boards remove the complete trailing
 * token, while spelling boards remove one character from their composed text.
 */
fun backspaceSentenceSelection(
    texts: List<String>,
    spellingMode: Boolean = false
): List<String> {
    if (texts.isEmpty()) return texts
    if (!spellingMode) return texts.dropLast(1)
    val last = texts.last()
    if (last.length <= 1) return texts.dropLast(1)
    return texts.dropLast(1) + last.dropLast(1)
}

/**
 * Compose the sentence text from the selected buttons, resolving each through the
 * board's `strings` localization table and honoring spelling mode.
 */
fun buildResolvedSentence(
    buttons: List<ObfButton>,
    strings: Map<String, Map<String, String>>,
    spellingMode: Boolean,
    primaryLanguage: String
): String {
    val tokens = buttons.mapNotNull { button ->
        resolveObfLocalizedString(
            strings = strings,
            locale = primaryLanguage,
            rawValue = button.vocalization ?: button.label
        )?.takeIf { it.isNotEmpty() }
    }
    return joinSentenceText(tokens, spellingMode)
}

/**
 * Join composed sentence tokens, honoring spelling mode (no separators) versus
 * normal mode. Word screens auto-insert a space after every word. The separator
 * is only omitted when a token already carries its own whitespace at the join
 * boundary, i.e. the board specified the spacing itself. Spelling boards (the
 * keyboard preset) opt out entirely so characters join without spaces. Shared
 * by every client's sentence bar.
 */
fun joinSentenceText(tokens: List<String>, spellingMode: Boolean): String =
    if (spellingMode) {
        tokens.joinToString("")
    } else {
        buildString {
            tokens.forEachIndexed { index, token ->
                if (index > 0 &&
                    !tokens[index - 1].hasTrailingWhitespace() &&
                    !token.hasLeadingWhitespace()
                ) {
                    append(' ')
                }
                append(token)
            }
        }
    }

/** Whether the token already ends in whitespace (it carries its own trailing space). */
private fun String.hasTrailingWhitespace(): Boolean = isNotEmpty() && this[lastIndex].isWhitespace()

/** Whether the token already begins in whitespace (it carries its own leading space). */
private fun String.hasLeadingWhitespace(): Boolean = isNotEmpty() && this[0].isWhitespace()

/**
 * A resolved speech fragment for one board button: the localized text to speak,
 * the button's language override, an optional recorded-audio path, and math mode.
 */
data class ButtonSpeechPart(
    val text: String,
    val language: String?,
    val recordingPath: String?,
    val mathMode: Boolean
)

/** Resolve one board button into its speech fragment. */
fun ObfBoard.buttonSpeechPart(button: ObfButton, primaryLanguage: String): ButtonSpeechPart? {
    val text = resolveObfLocalizedString(
        strings = strings,
        locale = primaryLanguage,
        rawValue = button.vocalization ?: button.label
    )?.takeIf { it.isNotEmpty() } ?: return null
    val recordingPath = button.soundId
        ?.let { soundId -> sounds.firstOrNull { it.id == soundId } }
        ?.path
        ?.takeIf { it.isNotBlank() }
    return ButtonSpeechPart(
        text = text,
        language = button.locale,
        recordingPath = recordingPath,
        mathMode = button.mathMode
    )
}

/** The ids of the board's prediction buttons, in grid order. */
fun orderedPredictionButtonIds(
    board: ObfBoard?,
    showHiddenButtons: Boolean
): List<String> {
    val activeBoard = board ?: return emptyList()
    fun isPredictor(button: ObfButton): Boolean =
        button.type == ObfButtonType.NGramPrediction ||
            parseObfButtonActions(button).any { it === ObfButtonActionEffect.Predictions }
    val orderedIds = buildList {
        activeBoard.grid?.order?.forEach { row ->
            row.forEach { id ->
                if (id != null && id !in this) add(id)
            }
        }
        if (isEmpty()) {
            addAll(activeBoard.buttons.map { it.id })
        }
    }
    return orderedIds.filter { id ->
        val button = activeBoard.buttons.firstOrNull { it.id == id }
        button != null &&
            (button.hidden && !showHiddenButtons).not() &&
            isPredictor(button)
    }
}

/** Outcome of an edit-mode tap on a grid cell. */
sealed interface CellTapResult {
    data class OpenDialog(
        val row: Int,
        val column: Int,
        val button: ObfButton?
    ) : CellTapResult
}

/**
 * Resolve an edit-mode tap. Occupied fields open immediately at their top-left
 * anchor, including taps on any cell covered by a span. The UI may keep that
 * anchor selected after the dialog closes so resize controls remain available.
 */
fun resolveCellTap(
    grid: ObfGrid?,
    row: Int,
    column: Int,
    button: ObfButton?
): CellTapResult {
    val anchor = button?.let { grid?.fieldAnchorAt(row, column) }
    return anchor?.let { CellTapResult.OpenDialog(it.first, it.second, button) }
        ?: CellTapResult.OpenDialog(row, column, button)
}

/** Whether a board button should be rendered, honoring edit mode and a temporary reveal session. */
fun isBoardButtonVisible(
    button: ObfButton,
    isEditMode: Boolean,
    showHiddenButtons: Boolean
): Boolean = !button.hidden || isEditMode || showHiddenButtons

/**
 * Font-scale factor for a merged field's label/vocalization so larger fields
 * keep readable text. Pure display rule shared by every client.
 */
fun fieldFontScale(rowSpan: Int, columnSpan: Int): Float {
    val area = rowSpan.coerceAtLeast(1).toFloat() * columnSpan.coerceAtLeast(1)
    return kotlin.math.sqrt(kotlin.math.sqrt(area)).coerceIn(1f, 2f)
}

// --- Draft graph editing (board-set editor session) ---

fun renameDraftBoardSet(graph: BoardSetGraph, name: String): BoardSetGraph {
    val normalizedName = name.trim()
    if (normalizedName.isEmpty() || normalizedName == graph.boardSet.name) return graph
    return graph.copy(boardSet = graph.boardSet.copy(name = normalizedName))
}

fun renameDraftBoard(graph: BoardSetGraph, boardId: String, name: String): BoardSetGraph {
    return graph.copy(
        boards = graph.boards.map { board ->
            if (board.id == boardId) board.copy(name = name.trim()) else board
        }
    )
}

fun resizeDraftBoard(
    graph: BoardSetGraph,
    boardId: String,
    rows: Int,
    columns: Int
): BoardSetGraph {
    val board = graph.boardsById[boardId] ?: return graph
    val grid = board.grid ?: return graph
    val homeButton = board.buttons.firstOrNull { button ->
        button.resolvedActions().any { it.trim().equals(":home", ignoreCase = true) } ||
            button.loadBoard?.id == graph.boardSet.rootBoardId
    }
    val resizedGrid = if (homeButton == null) {
        grid.resized(rows, columns)
    } else {
        val homeCell = grid.normalizedOrder().flatMapIndexed { rowIndex, values ->
            values.mapIndexedNotNull { columnIndex, value ->
                if (value == homeButton.id) rowIndex to columnIndex else null
            }
        }.firstOrNull()
        val homeSpan = homeCell?.let { grid.fieldSpanAt(it.first, it.second) }
            ?: GridFieldSpan(rows = 1, columns = 1)
        val withoutHome = grid.copy(
            order = grid.normalizedOrder().map { values ->
                values.map { value -> if (value == homeButton.id) null else value }
            }
        )
        var resized = withoutHome.resized(rows, columns) ?: return graph
        val targetRow = rows - homeSpan.rows
        if (targetRow < 0 || homeSpan.columns > columns) return graph
        val targetId = resized.order[targetRow][0]
        if (targetId != null) {
            val emptyCells = resized.order.flatMapIndexed { rowIndex, values ->
                values.mapIndexedNotNull { columnIndex, value ->
                    if (value == null && (rowIndex != targetRow || columnIndex != 0)) {
                        rowIndex to columnIndex
                    } else {
                        null
                    }
                }
            }
            resized = emptyCells.firstNotNullOfOrNull { (emptyRow, emptyColumn) ->
                resized.moveOrSwapField(targetRow, 0, emptyRow, emptyColumn)
            } ?: return graph
        }
        resized.withFieldSpan(
            row = targetRow,
            column = 0,
            buttonId = homeButton.id,
            rowSpan = homeSpan.rows,
            columnSpan = homeSpan.columns
        )
    } ?: return graph
    if (resizedGrid == board.grid) return graph
    return graph.copy(
        boards = graph.boards.map { current ->
            if (current.id == boardId) current.copy(grid = resizedGrid) else current
        }
    )
}

fun moveDraftField(
    graph: BoardSetGraph,
    boardId: String,
    fromRow: Int,
    fromColumn: Int,
    toRow: Int,
    toColumn: Int
): BoardSetGraph {
    val board = graph.boardsById[boardId] ?: return graph
    val movedGrid = board.grid?.moveOrSwapField(fromRow, fromColumn, toRow, toColumn) ?: return graph
    if (movedGrid == board.grid) return graph
    return graph.copy(
        boards = graph.boards.map { current ->
            if (current.id == boardId) current.copy(grid = movedGrid) else current
        }
    )
}

fun resizeDraftField(
    graph: BoardSetGraph,
    boardId: String,
    row: Int,
    column: Int,
    rowSpan: Int,
    columnSpan: Int
): BoardSetGraph {
    val board = graph.boardsById[boardId] ?: return graph
    val grid = board.grid ?: return graph
    val buttonId = grid.order.getOrNull(row)?.getOrNull(column) ?: return graph
    val resizedGrid = grid.withFieldSpan(row, column, buttonId, rowSpan, columnSpan) ?: return graph
    if (resizedGrid == grid) return graph
    return graph.copy(
        boards = graph.boards.map { current ->
            if (current.id == boardId) current.copy(grid = resizedGrid) else current
        }
    )
}

fun BoardSetGraph.withHomeFieldsBottomLeft(): BoardSetGraph {
    val rootBoardId = boardSet.rootBoardId
    val normalizedBoards = boards.map { board ->
        val grid = board.grid ?: return@map board
        val homeButton = board.buttons.firstOrNull { button ->
            button.resolvedActions().any { it.trim().equals(":home", ignoreCase = true) } ||
                button.loadBoard?.id == rootBoardId
        } ?: return@map board
        val homeCells = grid.normalizedOrder().flatMapIndexed { rowIndex, values ->
            values.mapIndexedNotNull { columnIndex, value ->
                if (value == homeButton.id) rowIndex to columnIndex else null
            }
        }
        if (homeCells.isEmpty()) return@map board
        val anchor = homeCells.minOf { it.first } to homeCells.minOf { it.second }
        val span = grid.fieldSpanAt(anchor.first, anchor.second)
        val bottomLeft = (grid.rows - span.rows).coerceAtLeast(0) to 0
        val pinnedGrid = grid.moveOrSwapField(
            fromRow = anchor.first,
            fromColumn = anchor.second,
            toRow = bottomLeft.first,
            toColumn = bottomLeft.second
        ) ?: grid
        if (pinnedGrid == grid) board else board.copy(grid = pinnedGrid)
    }
    return if (normalizedBoards == boards) this else copy(boards = normalizedBoards)
}

fun updateDraftCell(
    graph: BoardSetGraph,
    boardId: String,
    row: Int,
    column: Int,
    label: String,
    vocalization: String?,
    imageUrl: String?,
    recordingPath: String? = null,
    backgroundColor: String?,
    language: String?,
    mathMode: Boolean = false,
    hidden: Boolean = false,
    linkedBoardId: String?,
    action: String? = null,
    actions: List<String> = emptyList(),
    shape: ObfButtonShape = ObfButtonShape.Rounded
): BoardSetGraph {
    val board = graph.boardsById[boardId] ?: return graph
    val grid = board.grid ?: return graph
    val existingId = grid.order.getOrNull(row)?.getOrNull(column)
    val existingButton = existingId?.let { id -> board.buttons.firstOrNull { it.id == id } }
    val buttonId = existingButton?.id ?: workspaceId("btn")
    var imageId = existingButton?.imageId
    val normalizedUrl = imageUrl?.trim()?.ifBlank { null }
    val images = when {
        normalizedUrl == null -> {
            imageId = null
            board.images
        }
        imageId != null -> board.images.map {
            if (it.id == imageId) it.copy(url = normalizedUrl, path = null, data = null) else it
        }
        else -> {
            val createdImageId = workspaceId("img")
            imageId = createdImageId
            board.images + ObfImage(id = createdImageId, url = normalizedUrl)
        }
    }
    var soundId = existingButton?.soundId
    val normalizedRecordingPath = recordingPath?.trim()?.ifBlank { null }
    val sounds = when {
        normalizedRecordingPath == null -> {
            soundId = null
            board.sounds
        }
        soundId != null -> board.sounds.map {
            if (it.id == soundId) it.copy(path = normalizedRecordingPath, url = null, data = null) else it
        }
        else -> {
            val createdSoundId = workspaceId("sound")
            soundId = createdSoundId
            board.sounds + ObfSound(
                id = createdSoundId,
                contentType = "audio/wav",
                path = normalizedRecordingPath
            )
        }
    }
    val button = (existingButton ?: ObfButton(id = buttonId)).copy(
        label = label.trim(),
        vocalization = vocalization?.trim()?.ifBlank { null },
        backgroundColor = backgroundColor?.trim()?.ifBlank { null },
        locale = language?.trim()?.ifBlank { null },
        imageId = imageId,
        soundId = soundId,
        hidden = hidden,
        loadBoard = linkedBoardId?.let { targetId ->
            ObfLoadBoard(id = targetId, name = graph.boardsById[targetId]?.name)
        },
        action = action?.trim()?.ifBlank { null },
        actions = actions
    ).withMathMode(mathMode).withShape(shape)
    val buttons = if (existingButton == null) board.buttons + button else board.buttons.map {
        if (it.id == button.id) button else it
    }
    val existingSpan = grid.fieldSpanAt(row, column)
    val updatedGrid = grid.withFieldSpan(
        row = row,
        column = column,
        buttonId = buttonId,
        rowSpan = existingSpan.rows,
        columnSpan = existingSpan.columns
    ) ?: return graph
    val updatedBoard = board.copy(buttons = buttons, images = images, sounds = sounds, grid = updatedGrid)
    return graph.copy(boards = graph.boards.map { if (it.id == boardId) updatedBoard else it })
}

fun clearDraftCell(
    graph: BoardSetGraph,
    boardId: String,
    row: Int,
    column: Int
): BoardSetGraph {
    val board = graph.boardsById[boardId] ?: return graph
    val grid = board.grid ?: return graph
    val removedButtonId = grid.order.getOrNull(row)?.getOrNull(column)
    val order = grid.normalizedOrder().map { values ->
        values.map { value -> if (value == removedButtonId) null else value }
    }
    val removedImageId = board.buttons.firstOrNull { it.id == removedButtonId }?.imageId
    val removedSoundId = board.buttons.firstOrNull { it.id == removedButtonId }?.soundId
    val buttons = board.buttons.filterNot { it.id == removedButtonId }
    val images = if (removedImageId != null && buttons.none { it.imageId == removedImageId }) {
        board.images.filterNot { it.id == removedImageId }
    } else board.images
    val sounds = if (removedSoundId != null && buttons.none { it.soundId == removedSoundId }) {
        board.sounds.filterNot { it.id == removedSoundId }
    } else board.sounds
    val updatedBoard = board.copy(buttons = buttons, images = images, sounds = sounds, grid = grid.copy(order = order))
    return graph.copy(boards = graph.boards.map { if (it.id == boardId) updatedBoard else it })
}

private fun workspaceId(prefix: String): String =
    "${prefix}_${kotlin.time.Clock.System.now().toEpochMilliseconds()}_${kotlin.random.Random.nextInt(1000, 9999)}"
