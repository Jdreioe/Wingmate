package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.CategoryItem
import io.github.jdreioe.wingmate.domain.CategoryRepository
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.random.Random

class IosCategoryRepository : CategoryRepository {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val serializer = ListSerializer(CategoryItem.serializer())
    private val store = IosPreferencesJsonStore(
        key = "categories_v1",
        encode = { json.encodeToString(serializer, it) },
        decode = { json.decodeFromString(serializer, it) },
    )

    override suspend fun getAll(): List<CategoryItem> = store.read(::emptyList)

    override suspend fun add(category: CategoryItem): CategoryItem {
        val c = category.copy(id = category.id.ifBlank { Random.nextInt().toString() })
        store.update(::emptyList) { it + c }
        return c
    }

    override suspend fun update(category: CategoryItem): CategoryItem {
        store.update(::emptyList) { existing ->
            existing.map { if (it.id == category.id) category else it }
        }
        return category
    }

    override suspend fun delete(id: String) {
        store.update(::emptyList) { it.filterNot { category -> category.id == id } }
    }

    override suspend fun move(fromIndex: Int, toIndex: Int) {
        store.update(::emptyList) { existing ->
            if (fromIndex !in existing.indices || toIndex !in existing.indices) return@update existing
            existing.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        }
    }
}
