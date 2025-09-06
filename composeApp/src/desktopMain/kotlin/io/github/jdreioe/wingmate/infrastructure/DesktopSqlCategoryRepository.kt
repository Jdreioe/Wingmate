package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.CategoryItem
import io.github.jdreioe.wingmate.domain.CategoryRepository
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class DesktopSqlCategoryRepository : CategoryRepository {
    private val log = LoggerFactory.getLogger("DesktopSqlCategoryRepository")
    private val dbPath: Path = Path.of(System.getProperty("user.home"), ".config", "wingmate", "wingmate.db")

    private fun connection() = run {
        Files.createDirectories(dbPath.parent)
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
    }

    init {
        connection().use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS categories (
                        id TEXT PRIMARY KEY,
                        name TEXT,
                        selected_language TEXT,
                        selectedLanguage TEXT,
                        created_at INTEGER,
                        ordering INTEGER
                    )
                """.trimIndent())
            }
            // Migration: ensure ordering column exists and populate if null
            try {
                connection().use { conn2 ->
                    val existing = mutableSetOf<String>()
                    conn2.createStatement().use { st2 ->
                        val rs = st2.executeQuery("PRAGMA table_info('categories')")
                        while (rs.next()) existing.add(rs.getString("name"))
                    }
                    if (!existing.contains("ordering")) {
                        conn2.createStatement().use { it.executeUpdate("ALTER TABLE categories ADD COLUMN ordering INTEGER") }
                        // Assign ordering sequentially by name for determinism
                        val ids = mutableListOf<String>()
                        conn2.prepareStatement("SELECT id FROM categories ORDER BY name COLLATE NOCASE ASC").use { ps ->
                            val rs = ps.executeQuery()
                            while (rs.next()) ids += rs.getString(1)
                        }
                        conn2.prepareStatement("UPDATE categories SET ordering = ? WHERE id = ?").use { ups ->
                            ids.forEachIndexed { idx, id ->
                                ups.setInt(1, idx)
                                ups.setString(2, id)
                                ups.addBatch()
                            }
                            ups.executeBatch()
                        }
                    }
                    // If new camelCase column missing, add and backfill from legacy snake case
                    if (!existing.contains("selectedLanguage")) {
                        conn2.createStatement().use { it.executeUpdate("ALTER TABLE categories ADD COLUMN selectedLanguage TEXT") }
                        conn2.createStatement().use { it.executeUpdate("UPDATE categories SET selectedLanguage = selected_language WHERE selected_language IS NOT NULL AND (selectedLanguage IS NULL OR selectedLanguage = '')") }
                    }
                }
            } catch (_: Throwable) { }
        }
    }

    override suspend fun getAll(): List<CategoryItem> {
        log.info("[DEBUG] DesktopSqlCategoryRepository.getAll() called")
        val items = mutableListOf<CategoryItem>()
        try {
            connection().use { conn ->
                conn.prepareStatement("SELECT id, name, selectedLanguage FROM categories ORDER BY ordering ASC, name COLLATE NOCASE ASC").use { ps ->
                    val rs = ps.executeQuery()
                    while (rs.next()) {
                        items.add(CategoryItem(id = rs.getString(1), name = rs.getString(2), selectedLanguage = rs.getString(3)))
                    }
                }
            }
            log.info("[DEBUG] DesktopSqlCategoryRepository.getAll() returning ${items.size} items: ${items.map { it.name to it.id }}")
        } catch (e: Exception) {
            log.error("[DEBUG] DesktopSqlCategoryRepository.getAll() failed with exception", e)
        }
        return items
    }

    override suspend fun add(category: CategoryItem): CategoryItem {
        val id = if (category.id.isBlank()) java.util.UUID.randomUUID().toString() else category.id
        val createdAt = System.currentTimeMillis()
        connection().use { conn ->
            // Determine next ordering
            var next = 0
            conn.prepareStatement("SELECT COALESCE(MAX(ordering), -1) FROM categories").use { ps ->
                val rs = ps.executeQuery(); if (rs.next()) next = rs.getInt(1) + 1
            }
            conn.prepareStatement("INSERT INTO categories(id, name, selectedLanguage, created_at, ordering) VALUES (?,?,?,?,?)").use { ps ->
                ps.setString(1, id)
                ps.setString(2, category.name)
                ps.setString(3, category.selectedLanguage)
                ps.setLong(4, createdAt)
                ps.setInt(5, next)
                ps.executeUpdate()
            }
        }
        val result = CategoryItem(id = id, name = category.name, selectedLanguage = category.selectedLanguage)
        log.info("[DEBUG] DesktopSqlCategoryRepository.add() added category: $result")
        return result
    }

    override suspend fun update(category: CategoryItem): CategoryItem {
        connection().use { conn ->
            conn.prepareStatement("UPDATE categories SET name = ?, selectedLanguage = ? WHERE id = ?").use { ps ->
                ps.setString(1, category.name)
                ps.setString(2, category.selectedLanguage)
                ps.setString(3, category.id)
                ps.executeUpdate()
            }
        }
        return category
    }

    override suspend fun delete(id: String) {
        connection().use { conn ->
            conn.prepareStatement("DELETE FROM categories WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
        }
    }

    override suspend fun move(fromIndex: Int, toIndex: Int) {
        connection().use { conn ->
            val ids = mutableListOf<String>()
            conn.prepareStatement("SELECT id FROM categories ORDER BY ordering ASC, name COLLATE NOCASE ASC").use { ps ->
                val rs = ps.executeQuery()
                while (rs.next()) ids += rs.getString(1)
            }
            if (fromIndex !in ids.indices || toIndex !in ids.indices) return
            val id = ids.removeAt(fromIndex)
            ids.add(toIndex, id)
            conn.autoCommit = false
            try {
                conn.prepareStatement("UPDATE categories SET ordering = ? WHERE id = ?").use { ps ->
                    ids.forEachIndexed { idx, cid ->
                        ps.setInt(1, idx)
                        ps.setString(2, cid)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                conn.commit()
            } catch (t: Throwable) {
                conn.rollback(); throw t
            } finally { conn.autoCommit = true }
        }
    }
}
