package io.github.jdreioe.wingmate.desktop

import io.github.jdreioe.wingmate.domain.PronunciationEntry
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SpeechPolicy
import io.github.jdreioe.wingmate.domain.applySpokenAliases
import io.github.jdreioe.wingmate.domain.obf.BoardActivationBehavior
import io.github.jdreioe.wingmate.domain.obf.BoardReturnBehavior
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.applyBoardReturnBehavior
import io.github.jdreioe.wingmate.domain.obf.fieldItems
import io.github.jdreioe.wingmate.domain.obf.joinSentenceText
import io.github.jdreioe.wingmate.domain.obf.pageSettingsOverrides
import io.github.jdreioe.wingmate.domain.obf.resolveBoardSettings
import io.github.jdreioe.wingmate.domain.obf.resolveObfLocalizedString
import io.github.jdreioe.wingmate.domain.obf.shouldAddBoardSelection
import io.github.jdreioe.wingmate.domain.obf.shouldSpeakSelectionImmediately
import io.github.jdreioe.wingmate.infrastructure.BoardImportResult
import io.github.jdreioe.wingmate.infrastructure.BoardImportService
import io.github.jdreioe.wingmate.infrastructure.ObfParser
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class DesktopBoardSet(val id: String, val name: String, val rootBoardId: String)

@Serializable
data class DesktopCell(
    val id: String,
    val row: Int,
    val column: Int,
    val rowSpan: Int,
    val columnSpan: Int,
    val label: String,
    val vocalization: String,
    val backgroundColor: String? = null,
    val image: String? = null,
)

@Serializable
data class DesktopBoardView(
    val boardSetId: String,
    val boardId: String,
    val title: String,
    val rows: Int,
    val columns: Int,
    val cells: List<DesktopCell>,
    val message: String,
    val showMessageBar: Boolean,
    val showSpeakButton: Boolean,
)

@Serializable
data class DesktopActivation(val view: DesktopBoardView, val speech: String? = null)

@Serializable
data class DesktopSettings(
    /** An iced theme name, or "system" to follow the desktop environment. */
    val theme: String = "system",
    /** What the chosen palette implies, so [Settings.forceDarkTheme] stays useful. */
    val prefersDark: Boolean? = null,
    val voice: String = "default",
    val speechRate: Float = 1f,
    val holdToSelectMillis: Long = 0,
    val dwellToSelectMillis: Long = 0,
)

/** Application-shaped Kotlin boundary. Rust receives view data, never domain objects. */
class DesktopCore(dataDirectory: String) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val store = DesktopStore(dataDirectory)
    private val media = DesktopMediaStorage(dataDirectory)
    private val importer = BoardImportService(ObfParser(), store, store, DesktopFileAccess(), media)
    private val editor = DesktopEditor(store)
    fun editorJson(value: String): String = runBlocking { editor.command(value) }

    private val backup = DesktopBackup(store, media)
    private var activeBoardSetId: String? = null
    private var activeBoardId: String? = null
    private var boardStack = emptyList<String>()
    private var sentence = emptyList<String>()
    private var heldSentence: List<String>? = null

    fun libraryJson(): String = runBlocking {
        json.encodeToString(store.listBoardSets().map { DesktopBoardSet(it.id, it.name, it.rootBoardId) })
    }

    fun recentsJson(): String = json.encodeToString(store.recentFiles())

    fun importFileJson(path: String): String = runBlocking {
        when (val result = importer.importBoardSetFromPathResult(path)) {
            is BoardImportResult.Success -> {
                store.remember(path)
                open(result.boardSet.id)
                activationJson()
            }
            is BoardImportResult.Cancelled -> errorJson("Import cancelled")
            is BoardImportResult.Failure -> errorJson(result.context)
        }
    }

    fun openJson(boardSetId: String): String = runBlocking {
        open(boardSetId)
        activationJson()
    }

    private suspend fun open(boardSetId: String) {
        val boardSet = store.getBoardSet(boardSetId) ?: error("Screen not found")
        activeBoardSetId = boardSet.id
        activeBoardId = boardSet.rootBoardId
        boardStack = emptyList()
        sentence = emptyList()
        heldSentence = null
    }

    fun activateJson(buttonId: String): String = runBlocking {
        val set = activeBoardSetId?.let { store.getBoardSet(it) } ?: error("No Screen is open")
        val board = activeBoardId?.let { store.getBoard(it) } ?: error("Page not found")
        val button = board.buttons.firstOrNull { it.id == buttonId } ?: error("Button not found")
        val settings = store.get()
        val resolved = resolvedSettings(board, set.screenSettings, settings)
        val actions = button.resolvedActions().map { it.lowercase() }
        var speech: String? = null
        when {
            ":clear" in actions -> sentence = emptyList()
            ":hold-message" in actions -> swapHeldMessage()
            ":backspace" in actions -> sentence = sentence.dropLast(1)
            ":speak" in actions -> speech = message(board)
            ":home" in actions -> {
                activeBoardId = set.rootBoardId
                boardStack = emptyList()
            }
            button.loadBoard?.id != null -> {
                val linkedBoardId = requireNotNull(button.loadBoard?.id)
                boardStack = boardStack + board.id
                activeBoardId = linkedBoardId
            }
            else -> {
                val text = localizedText(board, button)
                if (text.isNotBlank() && shouldAddBoardSelection(resolved.activationBehavior)) sentence += text
                if (text.isNotBlank() && shouldSpeakSelectionImmediately(settings.speechPolicy, resolved.activationBehavior)) speech = text
                val (next, stack) = applyBoardReturnBehavior(resolved.returnBehavior, activeBoardId, boardStack, set.rootBoardId)
                activeBoardId = next
                boardStack = stack
            }
        }
        json.encodeToString(DesktopActivation(view(), speech?.let { spoken(it) }))
    }

    fun backJson(): String = runBlocking {
        if (boardStack.isNotEmpty()) {
            activeBoardId = boardStack.last()
            boardStack = boardStack.dropLast(1)
        }
        activationJson()
    }

    fun clearJson(): String = runBlocking { sentence = emptyList(); activationJson() }
    fun holdJson(): String = runBlocking { swapHeldMessage(); activationJson() }
    fun speakJson(): String = runBlocking {
        val board = activeBoardId?.let { store.getBoard(it) } ?: error("Page not found")
        json.encodeToString(DesktopActivation(view(), message(board).takeIf { it.isNotBlank() }?.let { spoken(it) }))
    }

    fun settingsJson(): String = runBlocking {
        json.encodeToString(store.get().toDesktop(store.desktopTheme()))
    }
    fun updateSettingsJson(value: String): String = runBlocking {
        val update = json.decodeFromString<DesktopSettings>(value)
        val current = store.get()
        store.setDesktopTheme(update.theme)
        store.update(current.copy(
            forceDarkTheme = update.prefersDark,
            voice = update.voice,
            speechRate = update.speechRate.coerceIn(.5f, 2f),
            holdToSelectMillis = update.holdToSelectMillis.coerceIn(0, 2_000),
            dwellToSelectMillis = update.dwellToSelectMillis.coerceIn(0, 5_000),
        ))
        settingsJson()
    }
    fun pronunciationsJson(): String = runBlocking { json.encodeToString(store.getAll()) }
    fun addPronunciationJson(value: String): String = runBlocking {
        store.add(json.decodeFromString<PronunciationEntry>(value)); pronunciationsJson()
    }
    fun deletePronunciationJson(word: String): String = runBlocking { store.delete(word); pronunciationsJson() }
    fun exportBackupJson(path: String): String = runCatching { backup.export(path); "{\"ok\":true}" }.getOrElse(::errorJson)
    fun restoreBackupJson(path: String): String = runCatching {
        backup.restore(path)
        // The restored snapshot replaces the Screens the open ids came from.
        activeBoardSetId = null
        activeBoardId = null
        boardStack = emptyList()
        sentence = emptyList()
        "{\"ok\":true}"
    }.getOrElse(::errorJson)

    /** System TTS has no SSML, so text aliases are substituted before Rust speaks. */
    private suspend fun spoken(text: String) = applySpokenAliases(text, store.getAll())

    private suspend fun activationJson() = json.encodeToString(DesktopActivation(view()))

    private suspend fun view(): DesktopBoardView {
        val set = activeBoardSetId?.let { store.getBoardSet(it) } ?: error("No Screen is open")
        val board = activeBoardId?.let { store.getBoard(it) } ?: error("Page not found")
        val grid = board.grid ?: error("Page has no grid")
        val settings = store.get()
        val resolved = resolvedSettings(board, set.screenSettings, settings)
        val buttons = board.buttons.associateBy { it.id }
        val images = board.images.associateBy { it.id }
        val cells = grid.fieldItems().mapNotNull { field ->
            val button = field.buttonId?.let(buttons::get)?.takeUnless { it.hidden } ?: return@mapNotNull null
            DesktopCell(
                id = button.id, row = field.row, column = field.column,
                rowSpan = field.rowSpan, columnSpan = field.columnSpan,
                label = resolveObfLocalizedString(board.strings, settings.primaryLanguage, button.label).orEmpty(),
                vocalization = localizedText(board, button), backgroundColor = button.backgroundColor,
                image = button.imageId?.let(images::get)?.let { image ->
                    image.dataUrl ?: image.data?.let { "data:${image.contentType ?: "image/png"};base64,$it" }
                        ?: image.path?.let(media::resolve) ?: image.url
                },
            )
        }
        return DesktopBoardView(set.id, board.id, board.name.orEmpty(), grid.rows, grid.columns, cells,
            message(board), resolved.showMessageBar, resolved.showSpeakButton)
    }

    private fun localizedText(board: ObfBoard, button: ObfButton): String =
        resolveObfLocalizedString(board.strings, board.locale, button.vocalization)
            ?: resolveObfLocalizedString(board.strings, board.locale, button.label).orEmpty()

    private fun message(board: ObfBoard) = joinSentenceText(sentence, board.spellingMode)

    private fun swapHeldMessage() {
        val previous = sentence
        sentence = heldSentence.orEmpty()
        heldSentence = previous
    }

    private fun resolvedSettings(board: ObfBoard, screen: io.github.jdreioe.wingmate.domain.obf.BoardSettingsOverrides, settings: Settings) =
        resolveBoardSettings(settings.showLabels, settings.showSymbols, settings.labelAtTop,
            settings.boardShowMessageBar, settings.boardShowSpeakButton, settings.boardMessageBarEditable,
            settings.boardActivationBehavior, settings.boardReturnBehavior, screen, board.pageSettingsOverrides())

    private fun Settings.toDesktop(theme: String) = DesktopSettings(
        theme = theme,
        prefersDark = forceDarkTheme,
        voice = voice, speechRate = speechRate, holdToSelectMillis = holdToSelectMillis,
        dwellToSelectMillis = dwellToSelectMillis,
    )

    private fun errorJson(error: Throwable) = errorJson(error.message ?: "Operation failed")
    private fun errorJson(message: String) = json.encodeToString(mapOf("error" to message))
}
