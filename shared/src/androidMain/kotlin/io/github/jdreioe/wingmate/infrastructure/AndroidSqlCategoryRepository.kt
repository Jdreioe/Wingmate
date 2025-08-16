package io.github.jdreioe.wingmate.infrastructure

import android.content.ContentValues
import android.content.Context
import io.github.jdreioe.wingmate.domain.CategoryItem
import io.github.jdreioe.wingmate.domain.CategoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.UUID

class AndroidSqlCategoryRepository(private val context: Context) : CategoryRepository {
    private val helper by lazy { AndroidSqlOpenHelper(context) }

    override suspend fun getAll(): List<CategoryItem> = withContext(Dispatchers.IO) {
        val db = helper.readableDatabase
        val log = LoggerFactory.getLogger("AndroidSqlCategoryRepository")
        val cursor = db.query("categories", null, null, null, null, null, "ordering ASC")
        val list = mutableListOf<CategoryItem>()
        while (cursor.moveToNext()) {
            val id = cursor.getString(cursor.getColumnIndexOrThrow("id"))
            val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
            val selectedLanguage = cursor.getString(cursor.getColumnIndexOrThrow("selectedLanguage"))
            list += CategoryItem(id = id, name = name, selectedLanguage = selectedLanguage)
        }
        cursor.close()
        log.info("Loaded {} categories from SQLite", list.size)
        return@withContext list
    }

    override suspend fun add(category: CategoryItem): CategoryItem = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val id = category.id.ifBlank { UUID.randomUUID().toString() }
        val values = ContentValues().apply {
            put("id", id)
            put("name", category.name)
            put("selectedLanguage", category.selectedLanguage)
            put("ordering", 0)
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
}
