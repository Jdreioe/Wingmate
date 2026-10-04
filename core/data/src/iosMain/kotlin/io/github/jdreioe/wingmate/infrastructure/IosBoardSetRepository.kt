package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.BoardSetRepository
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Persists the board-set library metadata across iOS app launches. */
class IosBoardSetRepository : BoardSetRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(ObfBoardSet.serializer())
    private val store = IosPreferencesJsonStore(
        key = BOARD_SETS_KEY,
        encode = { json.encodeToString(serializer, it) },
        decode = { json.decodeFromString(serializer, it) },
    )

    override suspend fun getBoardSet(id: String): ObfBoardSet? =
        store.read(::emptyList).firstOrNull { it.id == id }

    override suspend fun saveBoardSet(boardSet: ObfBoardSet) {
        store.update(::emptyList) { boardSets ->
            boardSets.filterNot { it.id == boardSet.id } + boardSet
        }
    }

    override suspend fun listBoardSets(): List<ObfBoardSet> =
        store.read(::emptyList).sortedByDescending { it.updatedAt }

    override suspend fun deleteBoardSet(id: String) {
        store.update(::emptyList) { boardSets ->
            boardSets.filterNot { it.id == id }
        }
    }

    private companion object {
        const val BOARD_SETS_KEY = "obf_board_sets_v1"
    }
}
