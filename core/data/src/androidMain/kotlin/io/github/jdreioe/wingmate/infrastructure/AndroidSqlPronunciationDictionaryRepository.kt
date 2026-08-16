package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository
import io.github.jdreioe.wingmate.domain.PronunciationEntry
import io.github.jdreioe.wingmate.domain.PersistenceError
import io.github.jdreioe.wingmate.domain.PersistenceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class AndroidSqlPronunciationDictionaryRepository(
    private val context: Context
) : PronunciationDictionaryRepository {
    private val helper by lazy { AndroidSqlOpenHelper(context) }
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    init {
        helper.writableDatabase.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pronunciation_dictionary (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                data TEXT
            )
            """.trimIndent()
        )
        helper.writableDatabase.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pronunciation_dictionary_corrupt (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                data TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override suspend fun getAll(): List<PronunciationEntry> = withContext(Dispatchers.IO) {
        mutex.withLock { persistenceOperation { loadAll() } }
    }

    override suspend fun add(entry: PronunciationEntry) = withContext(Dispatchers.IO) {
        mutex.withLock {
            persistenceOperation {
                val list = loadAll().toMutableList()
                list.removeAll { it.word.equals(entry.word, ignoreCase = true) }
                list.add(entry.copy(word = entry.word.trim(), phoneme = entry.phoneme.trim()))
                saveAll(list)
            }
        }
    }

    override suspend fun delete(word: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            persistenceOperation {
                val list = loadAll().toMutableList()
                list.removeAll { it.word.equals(word, ignoreCase = true) }
                saveAll(list)
            }
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            persistenceOperation {
                loadAll()
                saveAll(emptyList())
            }
        }
    }

    private fun loadAll(): List<PronunciationEntry> {
        val db = helper.readableDatabase
        val cursor = db.query(
            "pronunciation_dictionary",
            arrayOf("data"),
            "id = 1",
            null,
            null,
            null,
            null
        )
        return try {
            if (!cursor.moveToFirst()) return emptyList()
            val text = cursor.getString(cursor.getColumnIndexOrThrow("data")) ?: return emptyList()
            try {
                json.decodeFromString(ListSerializer(PronunciationEntry.serializer()), text)
                    .sortedBy { it.word.lowercase() }
            } catch (error: kotlinx.serialization.SerializationException) {
                quarantine(text)
                throw PersistenceException(PersistenceError.CorruptOrUnsupported, error)
            }
        } finally {
            cursor.close()
        }
    }

    private fun saveAll(list: List<PronunciationEntry>) {
        val db = helper.writableDatabase
        val text = json.encodeToString(ListSerializer(PronunciationEntry.serializer()), list)
        db.execSQL(
            "INSERT OR REPLACE INTO pronunciation_dictionary (id, data) VALUES (1, ?)",
            arrayOf(text)
        )
    }

    private fun quarantine(text: String) {
        runCatching {
            helper.writableDatabase.execSQL(
                "INSERT OR IGNORE INTO pronunciation_dictionary_corrupt (id, data) VALUES (1, ?)",
                arrayOf(text),
            )
        }
    }

    private inline fun <T> persistenceOperation(block: () -> T): T = try {
        block()
    } catch (error: PersistenceException) {
        throw error
    } catch (error: kotlinx.serialization.SerializationException) {
        throw PersistenceException(PersistenceError.CorruptOrUnsupported, error)
    } catch (error: Exception) {
        throw PersistenceException(PersistenceError.Io, error)
    }
}
