package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import io.github.jdreioe.wingmate.domain.BoardSetRepository
import io.github.jdreioe.wingmate.domain.PersistenceError
import io.github.jdreioe.wingmate.domain.PersistenceException
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Persists the board-set library metadata on Android. */
class AndroidBoardSetRepository(context: Context) : BoardSetRepository {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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

    private suspend fun readBoardSets(): List<ObfBoardSet> = withContext(Dispatchers.IO) {
        mutex.withLock { decodeBoardSets() }
    }

    private suspend fun updateBoardSets(transform: (List<ObfBoardSet>) -> List<ObfBoardSet>) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val encoded = json.encodeToString(
                    ListSerializer(ObfBoardSet.serializer()),
                    transform(decodeBoardSets())
                )
                if (!preferences.edit().putString(BOARD_SETS_KEY, encoded).commit()) {
                    throw PersistenceException(PersistenceError.Io)
                }
            }
        }
    }

    private fun decodeBoardSets(): List<ObfBoardSet> {
        val encoded = try {
            preferences.getString(BOARD_SETS_KEY, null)
        } catch (error: ClassCastException) {
            throw PersistenceException(PersistenceError.CorruptOrUnsupported, error)
        } ?: return emptyList()
        return try {
            json.decodeFromString(ListSerializer(ObfBoardSet.serializer()), encoded)
        } catch (error: Exception) {
            quarantine(encoded)
            throw PersistenceException(PersistenceError.CorruptOrUnsupported, error)
        }
    }

    private fun quarantine(encoded: String) {
        if (preferences.contains(CORRUPT_BOARD_SETS_KEY)) return
        preferences.edit().putString(CORRUPT_BOARD_SETS_KEY, encoded).commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "wingmate_board_storage"
        const val BOARD_SETS_KEY = "obf_board_sets_v1"
        const val CORRUPT_BOARD_SETS_KEY = "obf_board_sets_v1_corrupt_backup"
    }
}
