package io.github.jdreioe.wingmate.infrastructure

import android.content.ContentValues
import android.content.Context
import io.github.jdreioe.wingmate.domain.CategoryItem
import io.github.jdreioe.wingmate.domain.CategoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class AndroidSqlCategoryRepository(private val context: Context) : CategoryRepository {
    private val helper by lazy { AndroidSqlOpenHelper(context) }

    override suspend fun getAll(): List<CategoryItem> = withContext(Dispatchers.IO) {
        val db = helper.readableDatabase
        // Removed SLF4J logger for cross-platform compatibility
        val cursor = db.query("categories", null, null, null, null, null, "ordering ASC")
        val list = mutableListOf<CategoryItem>()
        while (cursor.moveToNext()) {
            val id = cursor.getString(cursor.getColumnIndexOrThrow("id"))
            val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
            val selectedLanguage = cursor.getString(cursor.getColumnIndexOrThrow("selectedLanguage"))
            list += CategoryItem(id = id, name = name, selectedLanguage = selectedLanguage)
        }
        cursor.close()
        println("Loaded {} categories from SQLite: ${list.size}")
        return@withContext list
    }

    override suspend fun add(category: CategoryItem): CategoryItem = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val id = category.id.ifBlank { UUID.randomUUID().toString() }
        // Determine next ordering as max + 1
        val cur = db.rawQuery("SELECT COALESCE(MAX(ordering), -1) FROM categories", null)
        var next = -1
        if (cur.moveToFirst()) next = cur.getInt(0)
        cur.close()
        val values = ContentValues().apply {
            put("id", id)
            put("name", category.name)
            put("selectedLanguage", category.selectedLanguage)
            put("ordering", next + 1)
        }
        db.insert("categories", null, values)
        return@withContext category.copy(id = id)
    }

    override suspend fun update(category: CategoryItem): CategoryItem = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("name", category.name)
            put("selectedLanguage", category.selectedLanguage)
        }
        db.update("categories", values, "id = ?", arrayOf(category.id))
        return@withContext category
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.delete("categories", "id = ?", arrayOf(id))
        Unit
    }

    override suspend fun move(fromIndex: Int, toIndex: Int) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val cursor = db.query("categories", arrayOf("id"), null, null, null, null, "ordering ASC")
        val ids = mutableListOf<String>()
        while (cursor.moveToNext()) ids += cursor.getString(cursor.getColumnIndexOrThrow("id"))
        cursor.close()
        if (fromIndex !in ids.indices || toIndex !in ids.indices) return@withContext
        val id = ids.removeAt(fromIndex)
        ids.add(toIndex, id)
        db.beginTransaction()
        try {
            ids.forEachIndexed { idx, cid ->
                val cv = ContentValues().apply { put("ordering", idx) }
                db.update("categories", cv, "id = ?", arrayOf(cid))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
