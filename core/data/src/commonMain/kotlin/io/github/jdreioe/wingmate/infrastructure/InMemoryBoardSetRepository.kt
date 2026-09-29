package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.BoardSetRepository
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet

class InMemoryBoardSetRepository : BoardSetRepository {
    private val sets = mutableMapOf<String, ObfBoardSet>()

    override suspend fun getBoardSet(id: String): ObfBoardSet? {
        return sets[id]
    }

    override suspend fun saveBoardSet(boardSet: ObfBoardSet) {
        sets[boardSet.id] = boardSet
    }

    override suspend fun listBoardSets(): List<ObfBoardSet> {
        return sets.values.sortedByDescending { it.updatedAt }
    }

    override suspend fun deleteBoardSet(id: String) {
        sets.remove(id)
    }
}
