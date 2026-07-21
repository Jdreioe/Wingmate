package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository
import io.github.jdreioe.wingmate.domain.PronunciationEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class AndroidSqlPronunciationDictionaryRepository(
    private val context: Context
) : PronunciationDictionaryRepository {
    private val helper by lazy { AndroidSqlOpenHelper(context) }
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
    }

    override suspend fun getAll(): List<PronunciationEntry> = withContext(Dispatchers.IO) {
        loadAll()
    }

    override suspend fun add(entry: PronunciationEntry) = withContext(Dispatchers.IO) {
        val list = loadAll().toMutableList()
        list.removeAll { it.word.equals(entry.word, ignoreCase = true) }
        list.add(entry.copy(word = entry.word.trim(), phoneme = entry.phoneme.trim()))
        saveAll(list)
    }

    override suspend fun delete(word: String) = withContext(Dispatchers.IO) {
        val list = loadAll().toMutableList()
        list.removeAll { it.word.equals(word, ignoreCase = true) }
        saveAll(list)
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        saveAll(emptyList())
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
            json.decodeFromString(ListSerializer(PronunciationEntry.serializer()), text)
                .sortedBy { it.word.lowercase() }
        } catch (_: Throwable) {
            emptyList()
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
}
