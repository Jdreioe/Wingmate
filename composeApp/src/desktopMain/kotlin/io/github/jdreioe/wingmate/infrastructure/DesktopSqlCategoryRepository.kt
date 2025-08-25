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
                        created_at INTEGER
                    )
                """.trimIndent())
            }
        }
    }

    override suspend fun getAll(): List<CategoryItem> {
        val items = mutableListOf<CategoryItem>()
        connection().use { conn ->
            conn.prepareStatement("SELECT id, name FROM categories ORDER BY name COLLATE NOCASE ASC").use { ps ->
                val rs = ps.executeQuery()
                while (rs.next()) {
                    items.add(CategoryItem(id = rs.getString(1), name = rs.getString(2), selectedLanguage = rs.getString(3)))
                }
            }
        }
        return items
    }

    override suspend fun add(category: CategoryItem): CategoryItem {
        val id = if (category.id.isBlank()) java.util.UUID.randomUUID().toString() else category.id
        val createdAt = System.currentTimeMillis()
        connection().use { conn ->
            conn.prepareStatement("INSERT INTO categories(id, name, selected_language, created_at) VALUES (?,?,?,?)").use { ps ->
                ps.setString(1, id)
                ps.setString(2, category.name)
                ps.setString(3, category.selectedLanguage)
                ps.setLong(4, createdAt)
                ps.executeUpdate()
            }
        }
        return CategoryItem(id = id, name = category.name, selectedLanguage = category.selectedLanguage)
    }

    override suspend fun update(category: CategoryItem): CategoryItem {
        connection().use { conn ->
            conn.prepareStatement("UPDATE categories SET name = ?, selected_language = ? WHERE id = ?").use { ps ->
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
}
