package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfManifest
import io.github.jdreioe.wingmate.domain.obf.ObfManifestPaths
import io.github.jdreioe.wingmate.domain.obf.ZipBuilder
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class ObzExporter(
    private val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
) {
    suspend fun export(
        boards: List<ObfBoard>,
        rootBoardId: String,
        loadMedia: suspend (path: String) -> ByteArray? = { null },
        soundBytes: Map<String, ByteArray> = emptyMap()
    ): ByteArray {
        val rootBoard = boards.firstOrNull { it.id == rootBoardId }
            ?: error("root board $rootBoardId not found")

        val boardFiles = mutableMapOf<String, String>()
        val imageFiles = mutableMapOf<String, String>()
        val soundFiles = mutableMapOf<String, String>()

        val entries = mutableMapOf<String, ByteArray>()

        for (board in boards) {
            val path = "boards/${board.id}.obf"
            boardFiles[board.id] = path
            val boardJson = json.encodeToJsonElement(ObfBoard.serializer(), board)
            entries[path] = json.encodeToString(serializeWithExtensions(boardJson, board)).encodeToByteArray()
        }

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
        val manifestJson = json.encodeToJsonElement(ObfManifest.serializer(), manifest)
        entries["manifest.json"] = json.encodeToString(serializeWithExtensions(manifestJson, manifest)).encodeToByteArray()

        return ZipBuilder.build(entries)
    }

    /**
     * Single-board OBF JSON with extension metadata merged back.
     * Standard fields always win over any colliding extension keys.
     */
    fun serializeBoard(board: ObfBoard): String {
        val boardJson = json.encodeToJsonElement(ObfBoard.serializer(), board)
        return json.encodeToString(serializeWithExtensions(boardJson, board))
    }

    private fun serializeWithExtensions(serialized: JsonElement, board: ObfBoard): JsonElement {
        if (serialized !is JsonObject) return serialized
        var result = mergeExtensions(serialized, board.extensions)
        result = injectById(result, "buttons", board.buttons.associateBy { it.id }) { button, raw ->
            var obj = mergeExtensions(raw, button.extensions)
            val loadBoard = button.loadBoard
            val rawLoadBoard = obj["load_board"] as? JsonObject
            if (loadBoard != null && rawLoadBoard != null && loadBoard.extensions.isNotEmpty()) {
                obj = JsonObject(
                    obj.toMap() + ("load_board" to mergeExtensions(rawLoadBoard, loadBoard.extensions))
                )
            }
            obj
        }
        result = injectById(result, "images", board.images.associateBy { it.id }) { image, raw ->
            var obj = mergeExtensions(raw, image.extensions)
            val license = image.license
            val rawLicense = obj["license"] as? JsonObject
            if (license != null && rawLicense != null && license.extensions.isNotEmpty()) {
                obj = JsonObject(
                    obj.toMap() + ("license" to mergeExtensions(rawLicense, license.extensions))
                )
            }
            obj
        }
        result = injectById(result, "sounds", board.sounds.associateBy { it.id }) { sound, raw ->
            var obj = mergeExtensions(raw, sound.extensions)
            val license = sound.license
            val rawLicense = obj["license"] as? JsonObject
            if (license != null && rawLicense != null && license.extensions.isNotEmpty()) {
                obj = JsonObject(
                    obj.toMap() + ("license" to mergeExtensions(rawLicense, license.extensions))
                )
            }
            obj
        }
        val boardLicense = board.license
        if (boardLicense != null && boardLicense.extensions.isNotEmpty()) {
            val rawLicense = result["license"] as? JsonObject
            if (rawLicense != null) {
                result = JsonObject(
                    result.toMap() + ("license" to mergeExtensions(rawLicense, boardLicense.extensions))
                )
            }
        }
        return result
    }

    private fun serializeWithExtensions(serialized: JsonElement, manifest: ObfManifest): JsonElement {
        if (serialized !is JsonObject) return serialized
        var result = mergeExtensions(serialized, manifest.extensions)
        if (manifest.paths.extensions.isNotEmpty()) {
            val rawPaths = result["paths"] as? JsonObject
            if (rawPaths != null) {
                result = JsonObject(
                    result.toMap() + ("paths" to mergeExtensions(rawPaths, manifest.paths.extensions))
                )
            }
        }
        return result
    }

    private fun <T> injectById(
        obj: JsonObject,
        key: String,
        byId: Map<String, T>,
        transform: (T, JsonObject) -> JsonObject
    ): JsonObject {
        if (byId.isEmpty()) return obj
        val rawArray = obj[key]?.jsonArray ?: return obj
        return JsonObject(
            obj.toMap() + (
                key to JsonArray(
                    rawArray.map { element ->
                        if (element !is JsonObject) return@map element
                        val id = element["id"]?.jsonPrimitive?.contentOrNull ?: return@map element
                        val item = byId[id] ?: return@map element
                        transform(item, element)
                    }
                )
            )
        )
    }

    /**
     * Merge extension metadata under standard fields so extensions never replace
     * known keys such as `id` or `format`. Only `ext_*` keys are emitted.
     */
    private fun mergeExtensions(base: JsonObject, extensions: Map<String, JsonElement>): JsonObject {
        if (extensions.isEmpty()) return base
        val safeExt = extensions.filterKeys { it.startsWith("ext_") }
        if (safeExt.isEmpty()) return base
        return JsonObject(safeExt + base.toMap())
    }
}
