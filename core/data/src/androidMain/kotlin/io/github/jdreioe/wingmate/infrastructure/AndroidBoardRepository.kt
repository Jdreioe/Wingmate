package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import io.github.jdreioe.wingmate.domain.BoardRepository
import io.github.jdreioe.wingmate.domain.PersistenceError
import io.github.jdreioe.wingmate.domain.PersistenceException
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

    override suspend fun saveBoards(boards: List<ObfBoard>) = updateBoards { existing ->
        if (boards.isEmpty()) return@updateBoards existing
        val savedIds = boards.map { it.id }.toSet()
        existing.filterNot { it.id in savedIds } + boards
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
                if (!preferences.edit().putString(BOARDS_KEY, encoded).commit()) {
                    throw PersistenceException(PersistenceError.Io)
                }
            }
        }
    }

    private fun decodeBoards(): List<ObfBoard> {
        val encoded = try {
            preferences.getString(BOARDS_KEY, null)
        } catch (error: ClassCastException) {
            throw PersistenceException(PersistenceError.CorruptOrUnsupported, error)
        } ?: return emptyList()
        return try {
            json.decodeFromString(ListSerializer(ObfBoard.serializer()), encoded)
        } catch (error: Exception) {
            quarantine(encoded)
            throw PersistenceException(PersistenceError.CorruptOrUnsupported, error)
        }
    }

    private fun quarantine(encoded: String) {
        if (preferences.contains(CORRUPT_BOARDS_KEY)) return
        preferences.edit().putString(CORRUPT_BOARDS_KEY, encoded).commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "wingmate_board_storage"
        const val BOARDS_KEY = "obf_boards_v1"
        const val CORRUPT_BOARDS_KEY = "obf_boards_v1_corrupt_backup"
    }
}
