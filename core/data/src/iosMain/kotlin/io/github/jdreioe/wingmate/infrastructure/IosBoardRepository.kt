package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.BoardRepository
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class IosBoardRepository : BoardRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
    private val serializer = ListSerializer(ObfBoard.serializer())
    private val store = IosPreferencesJsonStore(
        key = "obf_boards_v1",
        encode = { json.encodeToString(serializer, it) },
        decode = { json.decodeFromString(serializer, it) },
    )

    override suspend fun getBoard(id: String): ObfBoard? {
        return store.read(::emptyList).firstOrNull { it.id == id }
    }

    override suspend fun saveBoard(board: ObfBoard) {
        store.update(::emptyList) { boards -> boards.filterNot { it.id == board.id } + board }
    }

    override suspend fun saveBoards(boards: List<ObfBoard>) {
        if (boards.isEmpty()) return
        store.update(::emptyList) { existing ->
            val savedIds = boards.map { it.id }.toSet()
            existing.filterNot { it.id in savedIds } + boards
        }
    }

    override suspend fun listBoards(): List<ObfBoard> {
        return store.read(::emptyList)
    }

    override suspend fun deleteBoard(id: String) {
        store.update(::emptyList) { boards -> boards.filterNot { it.id == id } }
    }
}
