package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.ObfImage
import io.github.jdreioe.wingmate.domain.obf.ObfLicense
import io.github.jdreioe.wingmate.domain.obf.ObfManifest
import io.github.jdreioe.wingmate.domain.obf.ObfSound
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ObfParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun parseBoard(jsonContent: String): Result<ObfBoard> {
        return runCatching {
            val rawElement = json.parseToJsonElement(jsonContent)
            val cleaned = stringifyObfIds(rawElement)
            val board = json.decodeFromJsonElement(ObfBoard.serializer(), cleaned)
            attachExtensions(board, rawElement)
        }
    }

    fun parseManifest(jsonContent: String): Result<ObfManifest> {
        return runCatching {
            val rawElement = json.parseToJsonElement(jsonContent)
            val cleaned = stringifyObfIds(rawElement)
            val manifest = json.decodeFromJsonElement(ObfManifest.serializer(), cleaned)
            attachManifestExtensions(manifest, rawElement)
        }
    }

    private fun attachExtensions(board: ObfBoard, raw: JsonElement): ObfBoard {
        if (raw !is JsonObject) return board

        var result = board.copy(extensions = extractExtensions(raw))

        val rawButtons = raw["buttons"]?.jsonArray?.mapNotNull { it as? JsonObject }
        if (rawButtons != null && result.buttons.isNotEmpty()) {
            result = result.copy(
                buttons = result.buttons.map { button ->
                    val rawButton = findById(rawButtons, button.id) ?: return@map button
                    attachButtonExtensions(button, rawButton)
                }
            )
        }

        val rawImages = raw["images"]?.jsonArray?.mapNotNull { it as? JsonObject }
        if (rawImages != null && result.images.isNotEmpty()) {
            result = result.copy(
                images = result.images.map { image ->
                    val rawImage = findById(rawImages, image.id) ?: return@map image
                    attachImageExtensions(image, rawImage)
                }
            )
        }

        val rawSounds = raw["sounds"]?.jsonArray?.mapNotNull { it as? JsonObject }
        if (rawSounds != null && result.sounds.isNotEmpty()) {
            result = result.copy(
                sounds = result.sounds.map { sound ->
                    val rawSound = findById(rawSounds, sound.id) ?: return@map sound
                    attachSoundExtensions(sound, rawSound)
                }
            )
        }

        val currentLicense = result.license
        val rawLicense = raw["license"] as? JsonObject
        if (currentLicense != null && rawLicense != null) {
            result = result.copy(license = attachLicenseExtensions(currentLicense, rawLicense))
        }

        return result
    }

    private fun attachButtonExtensions(button: ObfButton, raw: JsonObject): ObfButton {
        var result = button.copy(extensions = extractExtensions(raw))
        val loadBoard = result.loadBoard
        val rawLoadBoard = raw["load_board"] as? JsonObject
        if (loadBoard != null && rawLoadBoard != null) {
            result = result.copy(
                loadBoard = loadBoard.copy(extensions = extractExtensions(rawLoadBoard))
            )
        }
        return result
    }

    private fun attachImageExtensions(image: ObfImage, raw: JsonObject): ObfImage {
        var result = image.copy(extensions = extractExtensions(raw))
        val license = result.license
        val rawLicense = raw["license"] as? JsonObject
        if (license != null && rawLicense != null) {
            result = result.copy(license = attachLicenseExtensions(license, rawLicense))
        }
        return result
    }

    private fun attachSoundExtensions(sound: ObfSound, raw: JsonObject): ObfSound {
        var result = sound.copy(extensions = extractExtensions(raw))
        val license = result.license
        val rawLicense = raw["license"] as? JsonObject
        if (license != null && rawLicense != null) {
            result = result.copy(license = attachLicenseExtensions(license, rawLicense))
        }
        return result
    }

    private fun attachLicenseExtensions(license: ObfLicense, raw: JsonObject): ObfLicense =
        license.copy(extensions = extractExtensions(raw))

    private fun attachManifestExtensions(manifest: ObfManifest, raw: JsonElement): ObfManifest {
        if (raw !is JsonObject) return manifest
        var result = manifest.copy(extensions = extractExtensions(raw))
        val rawPaths = raw["paths"] as? JsonObject
        if (rawPaths != null) {
            result = result.copy(
                paths = result.paths.copy(extensions = extractExtensions(rawPaths))
            )
        }
        return result
    }

    private fun extractExtensions(obj: JsonObject): Map<String, JsonElement> =
        obj.filterKeys { it.startsWith("ext_") }

    private fun findById(items: List<JsonObject>, id: String): JsonObject? =
        items.find { rawIdEquals(it["id"], id) }

    private fun rawIdEquals(rawId: JsonElement?, id: String): Boolean {
        val primitive = rawId as? JsonPrimitive ?: return false
        return primitive.contentOrNull == id || primitive.content == id
    }

    internal fun stringifyObfIds(element: JsonElement): JsonElement {
        return when (element) {
            is JsonObject -> JsonObject(
                element.mapValues { (key, value) ->
                    when {
                        key in ID_KEYS -> stringifyIdValue(value)
                        key == "order" -> stringifyGridOrder(value)
                        key.startsWith("ext_") -> value
                        else -> stringifyObfIds(value)
                    }
                }
            )
            is JsonArray -> JsonArray(element.map { stringifyObfIds(it) })
            else -> element
        }
    }

    private fun stringifyIdValue(value: JsonElement): JsonElement {
        return when (value) {
            is JsonNull -> value
            is JsonPrimitive -> {
                if (value.isString) value else JsonPrimitive(value.content)
            }
            else -> stringifyObfIds(value)
        }
    }

    private fun stringifyGridOrder(value: JsonElement): JsonElement {
        if (value !is JsonArray) return stringifyObfIds(value)
        return JsonArray(
            value.map { row ->
                if (row !is JsonArray) return@map stringifyObfIds(row)
                JsonArray(
                    row.map { cell ->
                        when (cell) {
                            is JsonNull -> cell
                            is JsonPrimitive -> {
                                if (cell.isString || cell.contentOrNull == null) cell
                                else JsonPrimitive(cell.content)
                            }
                            else -> stringifyObfIds(cell)
                        }
                    }
                )
            }
        )
    }

    private companion object {
        val ID_KEYS = setOf("id", "image_id", "sound_id")
    }
}
