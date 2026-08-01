package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import io.github.jdreioe.wingmate.domain.obf.ObfManifest
import io.github.jdreioe.wingmate.domain.obf.ObfManifestPaths
import io.github.jdreioe.wingmate.domain.obf.ObfSound
import io.github.jdreioe.wingmate.domain.Base64Decoder
import io.github.jdreioe.wingmate.domain.obf.ZipBuilder
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

sealed interface ObzExportResult {
    data class Success(val bytes: ByteArray) : ObzExportResult
    data class Failure(
        val code: ObzExportErrorCode,
        val context: String,
        val resources: List<String> = emptyList()
    ) : ObzExportResult
}

enum class ObzExportErrorCode {
    ROOT_NOT_FOUND,
    DUPLICATE_ID,
    UNSAFE_ID,
    UNRESOLVED_MEDIA,
    ZIP_FAILED
}

class ObzExporter(
    private val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
) {
    suspend fun exportResult(
        boards: List<ObfBoard>,
        rootBoardId: String,
        loadMedia: suspend (path: String) -> ByteArray? = { null },
        manifestExtensions: Map<String, JsonElement> = emptyMap()
    ): ObzExportResult {
        val rootBoard = boards.firstOrNull { it.id == rootBoardId }
            ?: return ObzExportResult.Failure(ObzExportErrorCode.ROOT_NOT_FOUND, "Root board '$rootBoardId' was not found")

        val duplicateBoardIds = duplicates(boards.map { it.id })
        val duplicateImageIds = duplicates(boards.flatMap { board -> board.images.map { it.id } })
        val duplicateSoundIds = duplicates(boards.flatMap { board -> board.sounds.map { it.id } })
        val duplicateIds = duplicateBoardIds + duplicateImageIds + duplicateSoundIds
        if (duplicateIds.isNotEmpty()) {
            return ObzExportResult.Failure(
                ObzExportErrorCode.DUPLICATE_ID,
                "Package-wide IDs must be unique",
                duplicateIds.sorted()
            )
        }
        val unsafeIds = (boards.map { it.id } + boards.flatMap { it.images.map(ObfImage::id) } + boards.flatMap { it.sounds.map(ObfSound::id) })
            .filterNot(::isSafeId)
        if (unsafeIds.isNotEmpty()) {
            return ObzExportResult.Failure(ObzExportErrorCode.UNSAFE_ID, "Unsafe archive IDs", unsafeIds.distinct())
        }

        val boardFiles = boards.associate { it.id to "boards/${it.id}.obf" }
        val imageFiles = mutableMapOf<String, String>()
        val soundFiles = mutableMapOf<String, String>()
        val entries = mutableMapOf<String, ByteArray>()
        val unresolved = mutableListOf<String>()

        val rewrittenBoards = boards.map { board ->
            val images = board.images.map { image ->
                rewriteImage(image, loadMedia, entries, imageFiles, unresolved)
            }
            val sounds = board.sounds.map { sound ->
                rewriteSound(sound, loadMedia, entries, soundFiles, unresolved)
            }
            val buttons = board.buttons.map { button ->
                val link = button.loadBoard
                val localPath = link?.id?.let(boardFiles::get)
                if (link != null && localPath != null) {
                    button.copy(loadBoard = link.copy(path = localPath))
                } else button
            }
            board.copy(images = images, sounds = sounds, buttons = buttons)
        }
        if (unresolved.isNotEmpty()) {
            return ObzExportResult.Failure(
                ObzExportErrorCode.UNRESOLVED_MEDIA,
                "Local media could not be packaged",
                unresolved.distinct()
            )
        }

        rewrittenBoards.forEach { board ->
            val path = boardFiles.getValue(board.id)
            val boardJson = json.encodeToJsonElement(ObfBoard.serializer(), board)
            entries[path] = json.encodeToString(serializeWithExtensions(boardJson, board)).encodeToByteArray()
        }

        val rootPath = boardFiles.getValue(rootBoard.id)

        val manifest = ObfManifest(
            format = "open-board-0.1",
            root = rootPath,
            paths = ObfManifestPaths(
                boards = boardFiles,
                images = imageFiles,
                sounds = soundFiles
            ),
            extensions = manifestExtensions
        )
        val manifestJson = json.encodeToJsonElement(ObfManifest.serializer(), manifest)
        entries["manifest.json"] = json.encodeToString(serializeWithExtensions(manifestJson, manifest)).encodeToByteArray()

        return ZipBuilder.build(entries.toList()).fold(
            onSuccess = { ObzExportResult.Success(it) },
            onFailure = { ObzExportResult.Failure(ObzExportErrorCode.ZIP_FAILED, it.message ?: "Could not build OBZ") }
        )
    }

    suspend fun export(
        boards: List<ObfBoard>,
        rootBoardId: String,
        loadMedia: suspend (path: String) -> ByteArray? = { null },
        soundBytes: Map<String, ByteArray> = emptyMap(),
        manifestExtensions: Map<String, JsonElement> = emptyMap()
    ): ByteArray = when (val result = exportResult(boards, rootBoardId, { path ->
        loadMedia(path) ?: soundBytes[path.substringAfterLast('/')]
    }, manifestExtensions)) {
        is ObzExportResult.Success -> result.bytes
        is ObzExportResult.Failure -> error("${result.code}: ${result.context}: ${result.resources.joinToString()}")
    }

    private suspend fun rewriteImage(
        image: ObfImage,
        loadMedia: suspend (String) -> ByteArray?,
        entries: MutableMap<String, ByteArray>,
        manifestPaths: MutableMap<String, String>,
        unresolved: MutableList<String>
    ): ObfImage {
        if (validInlineData(image.data)) return image.copy(path = null)
        if (!image.dataUrl.isNullOrBlank()) return image.copy(path = null)
        val path = image.path
        if (!path.isNullOrBlank()) {
            val bytes = runCatching { loadMedia(path) }.getOrNull()
            if (bytes != null) {
                val archivePath = "images/${image.id}.${extensionFor(image.contentType, path, "bin")}"
                entries[archivePath] = bytes
                manifestPaths[image.id] = archivePath
                return image.copy(path = archivePath, data = null, dataUrl = null, url = null)
            }
            if (image.dataUrl.isNullOrBlank() && image.url.isNullOrBlank() && image.symbol == null) {
                unresolved += "image:${image.id}:$path"
            }
            return image.copy(path = null)
        }
        return image
    }

    private suspend fun rewriteSound(
        sound: ObfSound,
        loadMedia: suspend (String) -> ByteArray?,
        entries: MutableMap<String, ByteArray>,
        manifestPaths: MutableMap<String, String>,
        unresolved: MutableList<String>
    ): ObfSound {
        if (validInlineData(sound.data)) return sound.copy(path = null)
        if (!sound.dataUrl.isNullOrBlank()) return sound.copy(path = null)
        val path = sound.path
        if (!path.isNullOrBlank()) {
            val bytes = runCatching { loadMedia(path) }.getOrNull()
            if (bytes != null) {
                val archivePath = "sounds/${sound.id}.${extensionFor(sound.contentType, path, "bin")}"
                entries[archivePath] = bytes
                manifestPaths[sound.id] = archivePath
                return sound.copy(path = archivePath, data = null, dataUrl = null, url = null)
            }
            if (sound.dataUrl.isNullOrBlank() && sound.url.isNullOrBlank()) {
                unresolved += "sound:${sound.id}:$path"
            }
            return sound.copy(path = null)
        }
        return sound
    }

    private fun validInlineData(data: String?): Boolean {
        if (data.isNullOrBlank()) return false
        val payload = data.substringAfter("base64,", data)
        return Base64Decoder.decodeOrNull(payload) != null
    }

    private fun extensionFor(contentType: String?, path: String?, default: String): String = when (contentType?.lowercase()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/gif" -> "gif"
        "image/svg+xml" -> "svg"
        "image/webp" -> "webp"
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/wav", "audio/x-wav" -> "wav"
        "audio/ogg" -> "ogg"
        else -> path?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]{1,5}")) } ?: default
    }

    private fun duplicates(ids: List<String>): Set<String> =
        ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys

    private fun isSafeId(id: String): Boolean = id.isNotBlank() && id != "." && id != ".." &&
        id.none { it == '/' || it == '\\' || it == ':' || it.code < 32 }

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
            if (loadBoard != null && rawLoadBoard != null) {
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
            if (license != null && rawLicense != null) {
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
            if (license != null && rawLicense != null) {
                obj = JsonObject(
                    obj.toMap() + ("license" to mergeExtensions(rawLicense, license.extensions))
                )
            }
            obj
        }
        val boardLicense = board.license
        if (boardLicense != null) {
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
        val rawPaths = result["paths"] as? JsonObject
        if (rawPaths != null) {
            result = JsonObject(
                result.toMap() + ("paths" to mergeExtensions(rawPaths, manifest.paths.extensions))
            )
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
        val publicBase = JsonObject(base.toMap() - INTERNAL_EXTENSIONS_KEY)
        if (extensions.isEmpty()) return publicBase
        val safeExt = extensions.filterKeys { it.startsWith("ext_") }
        if (safeExt.isEmpty()) return publicBase
        return JsonObject(safeExt + publicBase.toMap())
    }

    private companion object {
        const val INTERNAL_EXTENSIONS_KEY = "extensions"
    }
}
