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
            val element = json.parseToJsonElement(jsonContent)
            json.decodeFromJsonElement(ObfBoard.serializer(), stringifyObfIds(element))
        }
    }

    fun parseManifest(jsonContent: String): Result<ObfManifest> {
        return runCatching {
            val element = json.parseToJsonElement(jsonContent)
            json.decodeFromJsonElement(ObfManifest.serializer(), stringifyObfIds(element))
        }
    }

    /**
     * OBF/OBZ parsing guidelines require numeric IDs to be cast to strings.
     * Real-world boards often emit JSON numbers for id / image_id / sound_id / grid order cells.
     */
    internal fun stringifyObfIds(element: JsonElement): JsonElement {
        return when (element) {
            is JsonObject -> JsonObject(
                element.mapValues { (key, value) ->
                    when {
                        key in ID_KEYS -> stringifyIdValue(value)
                        key == "order" -> stringifyGridOrder(value)
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
