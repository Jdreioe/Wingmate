package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfManifest
import io.github.jdreioe.wingmate.domain.obf.ObfManifestPaths
import io.github.jdreioe.wingmate.domain.obf.ZipBuilder
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ObzExporter(
    private val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
) {
    /**
     * Export a set of boards to an OBZ byte array.
     *
     * @param boards all boards in the set (root + linked)
     * @param rootBoardId the id of the root board
     * @param loadMedia optional callback to resolve media by image path -> bytes
     * @param soundBytes optional map of sound id -> bytes (pre-resolved)
     * @return OBZ ZIP bytes
     */
    suspend fun export(
        boards: List<ObfBoard>,
        rootBoardId: String,
        loadMedia: suspend (path: String) -> ByteArray? = { null },
        soundBytes: Map<String, ByteArray> = emptyMap()
    ): ByteArray {
        val rootBoard = boards.firstOrNull { it.id == rootBoardId }
            ?: error("root board $rootBoardId not found")

        val boardFiles = mutableMapOf<String, String>()  // board id -> file path
        val imageFiles = mutableMapOf<String, String>()   // image id -> file path
        val soundFiles = mutableMapOf<String, String>()   // sound id -> file path

        val entries = mutableMapOf<String, ByteArray>()

        // Write each board as .obf
        for (board in boards) {
            val path = "boards/${board.id}.obf"
            boardFiles[board.id] = path
            entries[path] = json.encodeToString(board).encodeToByteArray()
        }

        // Collect and write image files from each board's image list
        for (board in boards) {
            for (image in board.images) {
                val imgPath = image.path
                if (imgPath != null && image.id !in imageFiles) {
                    val filename = imgPath.substringAfterLast('/')
                    val storedPath = "images/${image.id}/$filename"
                    imageFiles[image.id] = storedPath
                    loadMedia(imgPath)?.let { bytes ->
                        entries[storedPath] = bytes
                    }
                }
            }
        }

        // Collect sound references from buttons
        for (board in boards) {
            for (button in board.buttons) {
                val soundId = button.soundId
                if (soundId != null && soundId !in soundFiles) {
                    val soundPath = "sounds/$soundId"
                    soundFiles[soundId] = soundPath
                    val bytes = soundBytes[soundId] ?: loadMedia(soundPath)
                    if (bytes != null) {
                        entries[soundPath] = bytes
                    }
                }
            }
        }

        // Root board path from boardFiles
        val rootPath = boardFiles[rootBoardId] ?: error("root path not found")

        val manifest = ObfManifest(
            format = "open-board-0.1",
            root = rootPath,
            paths = ObfManifestPaths(
                boards = boardFiles,
                images = imageFiles,
                sounds = soundFiles
            )
        )

        entries["manifest.json"] = json.encodeToString(manifest).encodeToByteArray()
        return ZipBuilder.build(entries)
    }
}
