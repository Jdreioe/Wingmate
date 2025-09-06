package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.CategoryItem
import io.github.jdreioe.wingmate.domain.CategoryRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

class InMemoryCategoryRepository : CategoryRepository {
    private val mutex = Mutex()
    private val categories = mutableListOf<CategoryItem>()

    override suspend fun getAll(): List<CategoryItem> = mutex.withLock { 
        val result = categories.toList()
        println("[DEBUG] InMemoryCategoryRepository.getAll() returning ${result.size} items: ${result.map { it.name to it.id }}")
        result
    }

    override suspend fun add(category: CategoryItem): CategoryItem = mutex.withLock {
        val id = if (category.id.isBlank()) "cat_${Random.nextInt(100000)}" else category.id
        val added = category.copy(id = id)
        categories.add(added)
        println("[DEBUG] InMemoryCategoryRepository.add() added category: $added")
        added
    }

    override suspend fun update(category: CategoryItem): CategoryItem = mutex.withLock {
        val idx = categories.indexOfFirst { it.id == category.id }
        if (idx >= 0) categories[idx] = category
        category
    }

    override suspend fun delete(id: String) { mutex.withLock { categories.removeAll { it.id == id } } }

    override suspend fun move(fromIndex: Int, toIndex: Int) { mutex.withLock {
        if (fromIndex !in categories.indices) return
        val item = categories.removeAt(fromIndex)
        val insert = toIndex.coerceIn(0, categories.size)
        categories.add(insert, item)
    } }
}
