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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

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
            val boardJson = json.encodeToJsonElement(board)
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
        val manifestJson = json.encodeToJsonElement(manifest)
        entries["manifest.json"] = json.encodeToString(serializeWithExtensions(manifestJson, manifest)).encodeToByteArray()

        return ZipBuilder.build(entries)
    }

    private fun serializeWithExtensions(serialized: JsonElement, board: ObfBoard): JsonElement {
        if (serialized !is JsonObject) return serialized
        var result = serialized
        if (board.extensions.isNotEmpty()) {
            result = JsonObject(result.toMap() + board.extensions)
        }
        result = injectNestedExtensions(result, "buttons", board.buttons.map { it.id to it.extensions })
        result = injectNestedExtensions(result, "images", board.images.map { it.id to it.extensions })
        result = injectNestedExtensions(result, "sounds", board.sounds.map { it.id to it.extensions })
        if (board.license != null && board.license.extensions.isNotEmpty()) {
            val rawLicense = result["license"]?.jsonObject
            if (rawLicense != null) {
                result = JsonObject(result.toMap() + (
                    "license" to JsonObject(rawLicense.toMap() + board.license.extensions)
                ))
            }
        }
        return result
    }

    private fun serializeWithExtensions(serialized: JsonElement, manifest: ObfManifest): JsonElement {
        if (serialized !is JsonObject) return serialized
        return if (manifest.extensions.isNotEmpty()) {
            JsonObject(serialized.toMap() + manifest.extensions)
        } else serialized
    }

    private fun injectNestedExtensions(
        obj: JsonObject,
        key: String,
        extensions: List<Pair<String, Map<String, JsonElement>>>
    ): JsonObject {
        if (extensions.all { it.second.isEmpty() }) return obj
        val rawArray = obj[key]?.jsonArray ?: return obj
        val idToExt = extensions.filter { it.second.isNotEmpty() }.toMap()
        return JsonObject(obj.toMap() + (
            key to JsonArray(rawArray.map { element ->
                if (element !is JsonObject) return@map element
                val id = element["id"]?.jsonPrimitive?.contentOrNull ?: return@map element
                val ext = idToExt[id] ?: return@map element
                JsonObject(element.toMap() + ext)
            })
        ))
    }
}
