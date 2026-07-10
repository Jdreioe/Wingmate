package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfManifest
import kotlinx.serialization.json.Json

class ObfParser {
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun parseBoard(jsonContent: String): Result<ObfBoard> {
        return runCatching {
            json.decodeFromString<ObfBoard>(jsonContent)
        }
    }

    fun parseManifest(jsonContent: String): Result<ObfManifest> {
        return runCatching {
            json.decodeFromString<ObfManifest>(jsonContent)
        }
    }
}
