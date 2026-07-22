package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.BoardRepository
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

class IosBoardRepository : BoardRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
    private val prefs by lazy { NSUserDefaults.standardUserDefaults() }
    private val mutex = Mutex()
    private val key = "obf_boards_v1"

    private suspend fun loadAll(): List<ObfBoard> = withContext(Dispatchers.Default) {
        mutex.withLock { decodeAll() }
    }

    private suspend fun updateAll(transform: (List<ObfBoard>) -> List<ObfBoard>) {
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val text = json.encodeToString(ListSerializer(ObfBoard.serializer()), transform(decodeAll()))
                prefs.setObject(text, forKey = key)
                check(prefs.synchronize()) { "Unable to persist boards" }
            }
        }
    }

    private fun decodeAll(): List<ObfBoard> {
        val text = prefs.stringForKey(key) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(ObfBoard.serializer()), text)
        }.getOrDefault(emptyList())
    }

    override suspend fun getBoard(id: String): ObfBoard? {
        return loadAll().firstOrNull { it.id == id }
    }

    override suspend fun saveBoard(board: ObfBoard) {
        updateAll { boards -> boards.filterNot { it.id == board.id } + board }
    }

    override suspend fun listBoards(): List<ObfBoard> {
        return loadAll()
    }

    override suspend fun deleteBoard(id: String) {
        updateAll { boards -> boards.filterNot { it.id == id } }
    }
}
