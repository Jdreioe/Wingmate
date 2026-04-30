package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.BoardRepository
import io.github.jdreioe.wingmate.domain.obf.ObfBoard

class InMemoryBoardRepository : BoardRepository {
    private val boards = mutableMapOf<String, ObfBoard>()

    override suspend fun getBoard(id: String): ObfBoard? {
        return boards[id]
    }

    override suspend fun saveBoard(board: ObfBoard) {
        boards[board.id] = board
    }

    override suspend fun listBoards(): List<ObfBoard> {
        return boards.values.toList()
    }

    override suspend fun deleteBoard(id: String) {
        boards.remove(id)
    }
}
