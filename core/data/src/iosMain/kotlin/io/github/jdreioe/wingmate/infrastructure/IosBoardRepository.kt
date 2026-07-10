package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.BoardRepository
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import kotlinx.coroutines.Dispatchers
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
    private val key = "obf_boards_v1"

    private suspend fun loadAll(): MutableList<ObfBoard> = withContext(Dispatchers.Default) {
        val text = prefs.stringForKey(key) ?: return@withContext mutableListOf()
        runCatching {
            json.decodeFromString(ListSerializer(ObfBoard.serializer()), text)
        }.getOrNull()?.toMutableList() ?: mutableListOf()
    }

    private suspend fun saveAll(list: List<ObfBoard>) = withContext(Dispatchers.Default) {
        val text = json.encodeToString(ListSerializer(ObfBoard.serializer()), list)
        prefs.setObject(text, forKey = key)
        prefs.synchronize()
        Unit
    }

    override suspend fun getBoard(id: String): ObfBoard? {
        return loadAll().firstOrNull { it.id == id }
    }

    override suspend fun saveBoard(board: ObfBoard) {
        val list = loadAll()
        val idx = list.indexOfFirst { it.id == board.id }
        if (idx >= 0) {
            list[idx] = board
        } else {
            list.add(board)
        }
        saveAll(list)
    }

    override suspend fun listBoards(): List<ObfBoard> {
        return loadAll()
    }

    override suspend fun deleteBoard(id: String) {
        val list = loadAll().filterNot { it.id == id }
        saveAll(list)
        Unit
    }
}