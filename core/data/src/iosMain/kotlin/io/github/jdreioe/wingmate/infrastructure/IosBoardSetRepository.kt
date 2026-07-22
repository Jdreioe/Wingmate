package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.BoardSetRepository
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

/** Persists the board-set library metadata across iOS app launches. */
class IosBoardSetRepository : BoardSetRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val preferences by lazy { NSUserDefaults.standardUserDefaults() }
    private val mutex = Mutex()

    override suspend fun getBoardSet(id: String): ObfBoardSet? =
        readBoardSets().firstOrNull { it.id == id }

    override suspend fun saveBoardSet(boardSet: ObfBoardSet) = updateBoardSets { boardSets ->
        boardSets.filterNot { it.id == boardSet.id } + boardSet
    }

    override suspend fun listBoardSets(): List<ObfBoardSet> =
        readBoardSets().sortedByDescending { it.updatedAt }

    override suspend fun deleteBoardSet(id: String) = updateBoardSets { boardSets ->
        boardSets.filterNot { it.id == id }
    }

    private suspend fun readBoardSets(): List<ObfBoardSet> = withContext(Dispatchers.Default) {
        mutex.withLock { decodeBoardSets() }
    }

    private suspend fun updateBoardSets(transform: (List<ObfBoardSet>) -> List<ObfBoardSet>) {
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val encoded = json.encodeToString(
                    ListSerializer(ObfBoardSet.serializer()),
                    transform(decodeBoardSets())
                )
                preferences.setObject(encoded, forKey = BOARD_SETS_KEY)
                check(preferences.synchronize()) { "Unable to persist board sets" }
            }
        }
    }

    private fun decodeBoardSets(): List<ObfBoardSet> {
        val encoded = preferences.stringForKey(BOARD_SETS_KEY) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(ObfBoardSet.serializer()), encoded)
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val BOARD_SETS_KEY = "obf_board_sets_v1"
    }
}
