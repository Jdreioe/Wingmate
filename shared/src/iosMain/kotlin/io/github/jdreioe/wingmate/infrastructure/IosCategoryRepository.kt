package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.CategoryItem
import io.github.jdreioe.wingmate.domain.CategoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults
import kotlin.random.Random

class IosCategoryRepository : CategoryRepository {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val prefs by lazy { NSUserDefaults.standardUserDefaults() }
    private val key = "categories_v1"

    private suspend fun loadAll(): MutableList<CategoryItem> = withContext(Dispatchers.Default) {
        val text = prefs.stringForKey(key) ?: return@withContext mutableListOf()
        runCatching { json.decodeFromString(ListSerializer(CategoryItem.serializer()), text) }
            .getOrNull()?.toMutableList() ?: mutableListOf()
    }

    private suspend fun saveAll(list: List<CategoryItem>) = withContext(Dispatchers.Default) {
        val text = json.encodeToString(ListSerializer(CategoryItem.serializer()), list)
        prefs.setObject(text, forKey = key)
        prefs.synchronize()
        Unit
    }

    override suspend fun getAll(): List<CategoryItem> = loadAll()

    override suspend fun add(category: CategoryItem): CategoryItem {
        val list = loadAll()
        val c = category.copy(id = category.id.ifBlank { Random.nextInt().toString() })
        list.add(c)
        saveAll(list)
        return c
    }

    override suspend fun update(category: CategoryItem): CategoryItem {
        val list = loadAll()
        val idx = list.indexOfFirst { it.id == category.id }
        if (idx >= 0) {
            list[idx] = category
            saveAll(list)
        }
        return category
    }

    override suspend fun delete(id: String) {
        val list = loadAll()
        val newList = list.filterNot { it.id == id }
        saveAll(newList)
        Unit
    }
}
