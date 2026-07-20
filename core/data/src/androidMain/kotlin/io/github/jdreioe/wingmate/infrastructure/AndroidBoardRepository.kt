package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import io.github.jdreioe.wingmate.domain.BoardRepository
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Persists complete OBF boards on Android instead of losing them on process exit. */
class AndroidBoardRepository(context: Context) : BoardRepository {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun getBoard(id: String): ObfBoard? = readBoards().firstOrNull { it.id == id }

    override suspend fun saveBoard(board: ObfBoard) = updateBoards { boards ->
        boards.filterNot { it.id == board.id } + board
    }

    override suspend fun listBoards(): List<ObfBoard> = readBoards()

    override suspend fun deleteBoard(id: String) = updateBoards { boards ->
        boards.filterNot { it.id == id }
    }

    private suspend fun readBoards(): List<ObfBoard> = withContext(Dispatchers.IO) {
        mutex.withLock { decodeBoards() }
    }

    private suspend fun updateBoards(transform: (List<ObfBoard>) -> List<ObfBoard>) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val encoded = json.encodeToString(ListSerializer(ObfBoard.serializer()), transform(decodeBoards()))
                check(preferences.edit().putString(BOARDS_KEY, encoded).commit()) {
                    "Unable to persist boards"
                }
            }
        }
    }

    private fun decodeBoards(): List<ObfBoard> {
        val encoded = preferences.getString(BOARDS_KEY, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(ObfBoard.serializer()), encoded)
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val PREFERENCES_NAME = "wingmate_board_storage"
        const val BOARDS_KEY = "obf_boards_v1"
    }
}
