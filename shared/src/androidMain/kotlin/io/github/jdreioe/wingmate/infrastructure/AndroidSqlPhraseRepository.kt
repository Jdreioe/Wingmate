package io.github.jdreioe.wingmate.infrastructure

import android.content.ContentValues
import android.content.Context
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.PhraseRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.UUID

class AndroidSqlPhraseRepository(private val context: Context) : PhraseRepository {
    private val helper by lazy { AndroidSqlOpenHelper(context) }

    override suspend fun getAll(): List<Phrase> = withContext(Dispatchers.IO) {
        val db = helper.readableDatabase
        val log = LoggerFactory.getLogger("AndroidSqlPhraseRepository")
        val cursor = db.query("phrases", null, null, null, null, null, "ordering ASC")
        val list = mutableListOf<Phrase>()
        while (cursor.moveToNext()) {
            val id = cursor.getString(cursor.getColumnIndexOrThrow("id"))
            val text = cursor.getString(cursor.getColumnIndexOrThrow("text"))
            val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
            val bg = cursor.getString(cursor.getColumnIndexOrThrow("background_color"))
            val parentId = cursor.getString(cursor.getColumnIndexOrThrow("parent_id"))
            val isCat = cursor.getInt(cursor.getColumnIndexOrThrow("is_category")) != 0
            val createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
            list += Phrase(id = id, text = text, name = name, backgroundColor = bg, parentId = parentId, isCategory = isCat, createdAt = createdAt)
        }
        cursor.close()
        log.info("Loaded {} phrases from SQLite", list.size)
        return@withContext list
    }

    override suspend fun add(phrase: Phrase): Phrase = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val id = phrase.id.ifBlank { UUID.randomUUID().toString() }
        val createdAt = if (phrase.createdAt == 0L) System.currentTimeMillis() else phrase.createdAt
        // compute ordering as max+1
        val maxCur = db.rawQuery("SELECT COALESCE(MAX(ordering), -1) FROM phrases", null)
        var ord = -1
        if (maxCur.moveToFirst()) ord = maxCur.getInt(0)
        maxCur.close()
        val values = ContentValues().apply {
            put("id", id)
            put("text", phrase.text)
            put("name", phrase.name)
            put("background_color", phrase.backgroundColor)
            put("parent_id", phrase.parentId)
            put("is_category", if (phrase.isCategory) 1 else 0)
            put("created_at", createdAt)
            put("ordering", ord + 1)
        }
        db.insert("phrases", null, values)
        return@withContext Phrase(id = id, text = phrase.text, name = phrase.name, backgroundColor = phrase.backgroundColor, parentId = phrase.parentId, isCategory = phrase.isCategory, createdAt = createdAt)
    }

    override suspend fun update(phrase: Phrase): Phrase = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("text", phrase.text)
            put("name", phrase.name)
            put("background_color", phrase.backgroundColor)
            put("parent_id", phrase.parentId)
            put("is_category", if (phrase.isCategory) 1 else 0)
        }
        db.update("phrases", values, "id = ?", arrayOf(phrase.id))
        return@withContext phrase
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.delete("phrases", "id = ?", arrayOf(id))
        Unit
    }

    override suspend fun move(fromIndex: Int, toIndex: Int) = withContext(Dispatchers.IO) {
        // Simple ordering swap implementation: read all ids and reorder
        val db = helper.writableDatabase
        val cursor = db.query("phrases", arrayOf("id"), null, null, null, null, "ordering ASC")
        val ids = mutableListOf<String>()
        while (cursor.moveToNext()) ids += cursor.getString(cursor.getColumnIndexOrThrow("id"))
        cursor.close()
        if (fromIndex < 0 || toIndex < 0 || fromIndex >= ids.size || toIndex >= ids.size) return@withContext
        val id = ids.removeAt(fromIndex)
        ids.add(toIndex, id)
        db.beginTransaction()
        try {
            ids.forEachIndexed { idx, pid ->
                val vals = ContentValues().apply { put("ordering", idx) }
                db.update("phrases", vals, "id = ?", arrayOf(pid))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
