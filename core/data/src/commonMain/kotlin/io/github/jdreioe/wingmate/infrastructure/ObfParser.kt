package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfManifest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

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

        val boardExt = raw.filterKeys { it.startsWith("ext_") }
        var result = board.copy(
            extensions = if (boardExt.isEmpty()) emptyMap()
            else boardExt.mapValues { it.value }
        )

        val rawButtons = raw["buttons"]?.jsonArray?.map { it.jsonObject }
        if (rawButtons != null && result.buttons.isNotEmpty()) {
            result = result.copy(
                buttons = result.buttons.map { button ->
                    val rawButton = rawButtons.find {
                        it["id"]?.jsonPrimitive?.contentOrNull == button.id
                    }
                    if (rawButton != null) {
                        val ext = rawButton.filterKeys { it.startsWith("ext_") }
                        if (ext.isNotEmpty()) button.copy(extensions = ext.mapValues { it.value })
                        else button
                    } else button
                }
            )
        }

        val rawImages = raw["images"]?.jsonArray?.map { it.jsonObject }
        if (rawImages != null && result.images.isNotEmpty()) {
            result = result.copy(
                images = result.images.map { image ->
                    val rawImage = rawImages.find {
                        it["id"]?.jsonPrimitive?.contentOrNull == image.id
                    }
                    if (rawImage != null) {
                        val ext = rawImage.filterKeys { it.startsWith("ext_") }
                        if (ext.isNotEmpty()) image.copy(extensions = ext.mapValues { it.value })
                        else image
                    } else image
                }
            )
        }

        val rawSounds = raw["sounds"]?.jsonArray?.map { it.jsonObject }
        if (rawSounds != null && result.sounds.isNotEmpty()) {
            result = result.copy(
                sounds = result.sounds.map { sound ->
                    val rawSound = rawSounds.find {
                        it["id"]?.jsonPrimitive?.contentOrNull == sound.id
                    }
                    if (rawSound != null) {
                        val ext = rawSound.filterKeys { it.startsWith("ext_") }
                        if (ext.isNotEmpty()) sound.copy(extensions = ext.mapValues { it.value })
                        else sound
                    } else sound
                }
            )
        }

        if (result.license != null) {
            val rawLicense = raw["license"]?.jsonObject
            if (rawLicense != null) {
                val ext = rawLicense.filterKeys { it.startsWith("ext_") }
                if (ext.isNotEmpty()) {
                    result = result.copy(
                        license = result.license.copy(extensions = ext.mapValues { it.value })
                    )
                }
            }
        }

        return result
    }

    private fun attachManifestExtensions(manifest: ObfManifest, raw: JsonElement): ObfManifest {
        if (raw !is JsonObject) return manifest
        val ext = raw.filterKeys { it.startsWith("ext_") }
        if (ext.isEmpty()) return manifest
        return manifest.copy(extensions = ext.mapValues { it.value })
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
