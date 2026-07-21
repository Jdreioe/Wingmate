package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.BoardSetRepository
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import kotlinx.coroutines.delay

class InMemoryBoardSetRepository : BoardSetRepository {
    private val sets = mutableMapOf<String, ObfBoardSet>()

    override suspend fun getBoardSet(id: String): ObfBoardSet? {
        delay(10)
        return sets[id]
    }

    override suspend fun saveBoardSet(boardSet: ObfBoardSet) {
        delay(10)
        sets[boardSet.id] = boardSet
    }

    override suspend fun listBoardSets(): List<ObfBoardSet> {
        delay(10)
        return sets.values.sortedByDescending { it.updatedAt }
    }

    override suspend fun deleteBoardSet(id: String) {
        delay(10)
        sets.remove(id)
    }
}
