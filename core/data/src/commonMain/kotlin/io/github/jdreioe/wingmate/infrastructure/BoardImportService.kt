package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.Base64Decoder
import io.github.jdreioe.wingmate.domain.BoardRepository
import io.github.jdreioe.wingmate.domain.BoardSetRepository
import io.github.jdreioe.wingmate.domain.FileStorage
import io.github.jdreioe.wingmate.domain.obf.BoardSetGraph
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import io.github.jdreioe.wingmate.domain.obf.ObfLoadBoard
import io.github.jdreioe.wingmate.domain.obf.ObfSound
import io.github.jdreioe.wingmate.platform.FilePicker
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Imports OBF/OBZ packages into the board-set system, persisting every board in the
 * graph and rewriting zipped media paths into app-private [FileStorage] locations.
 */
class BoardImportService(
    private val obfParser: ObfParser,
    private val boardRepository: BoardRepository,
    private val boardSetRepository: BoardSetRepository,
    private val filePicker: FilePicker,
    private val fileStorage: FileStorage? = null
) {
    /**
     * Opens a file picker and imports the selected OBF/OBZ as a new board set.
     * @return the created board set, or null if cancelled / failed
     */
    suspend fun importBoardSet(): ObfBoardSet? {
        val filePath = filePicker.pickFile("Select Board File", listOf("obf", "obz", "json"))
            ?: return null
        return importBoardSetFromPath(filePath)
    }

    /**
     * Legacy entry point used by onboarding. Prefer [importBoardSet].
     * @return true when a board set was created
     */
    suspend fun importBoards(isModern: Boolean = true): Boolean {
        return importBoardSet() != null
    }

    suspend fun importBoardSetFromPath(filePath: String): ObfBoardSet? {
        val isObz = filePath.lowercase().endsWith(".obz")
        val graph = if (isObz) {
            importObzGraph(filePath)
        } else {
            importSingleObfGraph(filePath)
        } ?: return null

        val canonical = graph.canonicalizeBoardLinks()
        canonical.boards.forEach { boardRepository.saveBoard(it) }
        boardSetRepository.saveBoardSet(canonical.boardSet)
        return canonical.boardSet
    }

    private suspend fun importSingleObfGraph(filePath: String): BoardSetGraph? {
        val content = filePicker.readFileAsText(filePath) ?: return null
        val board = obfParser.parseBoard(content).getOrNull() ?: return null
        return buildGraph(listOf(board to true), emptyMap())
    }

    private suspend fun importObzGraph(filePath: String): BoardSetGraph? {
        val entries = filePicker.readZipEntries(filePath) ?: return null
        val manifestContent = entries["manifest.json"]?.decodeToString() ?: return null
        val manifest = obfParser.parseManifest(manifestContent).getOrNull() ?: return null

        val mediaEntries = entries.filterKeys { key ->
            !key.endsWith(".json", ignoreCase = true) &&
                !key.endsWith(".obf", ignoreCase = true) &&
                key != "manifest.json"
        }

        val boards = mutableListOf<Pair<ObfBoard, Boolean>>()
        val rootPath = manifest.root
        val rootContent = entries[rootPath]?.decodeToString()
            ?: entries.entries.firstOrNull { it.key.equals(rootPath, ignoreCase = true) }?.value?.decodeToString()
        if (rootContent != null) {
            obfParser.parseBoard(rootContent).getOrNull()?.let { boards.add(it to true) }
        }
        if (boards.none { it.second }) return null

        manifest.paths.boards.forEach { (_, path) ->
            if (path == rootPath) return@forEach
            val content = entries[path]?.decodeToString()
                ?: entries.entries.firstOrNull { it.key.equals(path, ignoreCase = true) }?.value?.decodeToString()
            if (content != null) {
                obfParser.parseBoard(content).getOrNull()?.let { boards.add(it to false) }
            }
        }

        return buildGraph(boards, mediaEntries)
    }

    private suspend fun buildGraph(
        boards: List<Pair<ObfBoard, Boolean>>,
        zipMedia: Map<String, ByteArray>
    ): BoardSetGraph? {
        if (boards.isEmpty()) return null

        val now = Clock.System.now().toEpochMilliseconds()
        val setId = newId("set")
        val boardIdMap = linkedMapOf<String, String>()
        boards.forEach { (board, _) ->
            if (board.id.isNotEmpty()) {
                boardIdMap.putIfAbsent(board.id, newId("board"))
            }
        }

        val rootOriginalId = boards.first { it.second }.first.id
        val rootNewId = boardIdMap[rootOriginalId] ?: newId("board").also {
            boardIdMap[rootOriginalId] = it
        }

        val rewrittenBoards = boards.map { (board, _) ->
            val newBoardId = boardIdMap.getValue(board.id.ifEmpty { rootOriginalId })
            rewriteBoard(board, newBoardId, boardIdMap, setId, zipMedia)
        }

        val boardSet = ObfBoardSet(
            id = setId,
            name = rewrittenBoards.firstOrNull { it.id == rootNewId }?.name
                ?: rewrittenBoards.first().name
                ?: "Imported board set",
            rootBoardId = rootNewId,
            boardIds = rewrittenBoards.map { it.id }.distinct(),
            isLocked = false,
            createdAt = now,
            updatedAt = now
        )
        return BoardSetGraph(boardSet, rewrittenBoards)
    }

    private suspend fun rewriteBoard(
        board: ObfBoard,
        newBoardId: String,
        boardIdMap: Map<String, String>,
        setId: String,
        zipMedia: Map<String, ByteArray>
    ): ObfBoard {
        val imageIdMap = board.images.associate { it.id to it.id }
        val soundIdMap = board.sounds.associate { it.id to it.id }

        val images = board.images.map { image ->
            persistImage(image, setId, zipMedia)
        }
        val sounds = board.sounds.map { sound ->
            persistSound(sound, setId, zipMedia)
        }
        val buttons = board.buttons.map { button ->
            rewriteButton(button, boardIdMap, imageIdMap, soundIdMap)
        }
        val grid = board.grid
        return board.copy(
            id = newBoardId,
            buttons = buttons,
            images = images,
            sounds = sounds,
            grid = grid
        )
    }

    private fun rewriteButton(
        button: ObfButton,
        boardIdMap: Map<String, String>,
        imageIdMap: Map<String, String>,
        soundIdMap: Map<String, String>
    ): ObfButton {
        val loadBoard = button.loadBoard?.let { link ->
            val mappedId = link.id?.let { boardIdMap[it] } ?: link.id
            ObfLoadBoard(
                id = mappedId,
                name = link.name,
                url = link.url,
                path = link.path,
                dataUrl = link.dataUrl,
                extensions = link.extensions
            )
        }
        return button.copy(
            imageId = button.imageId?.let { imageIdMap[it] ?: it },
            soundId = button.soundId?.let { soundIdMap[it] ?: it },
            loadBoard = loadBoard
        )
    }

    private suspend fun persistImage(
        image: ObfImage,
        setId: String,
        zipMedia: Map<String, ByteArray>
    ): ObfImage {
        val storage = fileStorage ?: return image
        val imagePath = image.path
        val imageData = image.data
        val bytes = when {
            imagePath != null -> zipMedia[imagePath] ?: zipMedia.entries
                .firstOrNull { it.key.equals(imagePath, ignoreCase = true) || it.key.endsWith("/$imagePath") }
                ?.value
            imageData != null -> decodeDataUri(imageData)
            else -> null
        } ?: return image

        val extension = extensionFor(image.contentType, image.path, default = "bin")
        val storedPath = "boardsets/$setId/images/${sanitize(image.id)}.$extension"
        storage.saveBytes(storedPath, bytes)
        return image.copy(path = storedPath, data = null, url = image.url)
    }

    private suspend fun persistSound(
        sound: ObfSound,
        setId: String,
        zipMedia: Map<String, ByteArray>
    ): ObfSound {
        val storage = fileStorage ?: return sound
        val soundPath = sound.path
        val soundData = sound.data
        val bytes = when {
            soundPath != null -> zipMedia[soundPath] ?: zipMedia.entries
                .firstOrNull { it.key.equals(soundPath, ignoreCase = true) || it.key.endsWith("/$soundPath") }
                ?.value
            soundData != null -> decodeDataUri(soundData)
            else -> null
        } ?: return sound

        val extension = extensionFor(sound.contentType, sound.path, default = "mp3")
        val storedPath = "boardsets/$setId/sounds/${sanitize(sound.id)}.$extension"
        storage.saveBytes(storedPath, bytes)
        return sound.copy(path = storedPath, data = null, url = sound.url)
    }

    private fun decodeDataUri(data: String): ByteArray? {
        val payload = data.substringAfter("base64,", missingDelimiterValue = data)
        return Base64Decoder.decodeOrNull(payload)
    }

    private fun extensionFor(contentType: String?, path: String?, default: String): String {
        path?.substringAfterLast('.', missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() && it.length <= 5 }
            ?.let { return it.lowercase() }
        return when (contentType?.lowercase()) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/gif" -> "gif"
            "image/svg+xml" -> "svg"
            "image/webp" -> "webp"
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/ogg" -> "ogg"
            else -> default
        }
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { newId("media") }

    private fun newId(prefix: String): String {
        val alphabet = "0123456789abcdef"
        val suffix = buildString(8) {
            repeat(8) { append(alphabet[Random.nextInt(alphabet.length)]) }
        }
        return "${prefix}_$suffix"
    }
}

/** Minimal base64 decoder for data-URI payloads. */
private fun String.decodeBase64Bytes(): ByteArray {
    val table = IntArray(128) { -1 }
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    alphabet.forEachIndexed { index, c -> table[c.code] = index }

    val cleaned = filterNot { it.isWhitespace() }
    if (cleaned.isEmpty()) return ByteArray(0)
    require(cleaned.length % 4 == 0) { "Invalid base64 length" }

    val padding = when {
        cleaned.endsWith("==") -> 2
        cleaned.endsWith("=") -> 1
        else -> 0
    }
    val out = ByteArray(cleaned.length / 4 * 3 - padding)
    var outIndex = 0
    var i = 0
    while (i < cleaned.length) {
        val c0 = decodeBase64Char(table, cleaned[i])
        val c1 = decodeBase64Char(table, cleaned[i + 1])
        val c2 = if (cleaned[i + 2] == '=') 0 else decodeBase64Char(table, cleaned[i + 2])
        val c3 = if (cleaned[i + 3] == '=') 0 else decodeBase64Char(table, cleaned[i + 3])
        val triple = (c0 shl 18) or (c1 shl 12) or (c2 shl 6) or c3
        if (outIndex < out.size) out[outIndex++] = ((triple shr 16) and 0xFF).toByte()
        if (outIndex < out.size) out[outIndex++] = ((triple shr 8) and 0xFF).toByte()
        if (outIndex < out.size) out[outIndex++] = (triple and 0xFF).toByte()
        i += 4
    }
    return out
}

private fun decodeBase64Char(table: IntArray, char: Char): Int {
    val value = table.getOrElse(char.code) { -1 }
    require(value >= 0) { "Invalid base64 char: $char" }
    return value
}
