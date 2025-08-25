package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.PhraseRepository
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class DesktopSqlPhraseRepository : PhraseRepository {
    private val log = LoggerFactory.getLogger("DesktopSqlPhraseRepository")

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
                    CREATE TABLE IF NOT EXISTS phrases (
                        id TEXT PRIMARY KEY,
                        text TEXT,
                        name TEXT,
                        background_color TEXT,
                        parent_id TEXT,
                        is_category INTEGER DEFAULT 0,
                        created_at INTEGER,
                        ordering INTEGER
                    )
                """.trimIndent())
            }
            // Migration: if the DB was created by an older version, some columns may be missing.
            try {
                val existing = mutableSetOf<String>()
                conn.createStatement().use { st2 ->
                    val rs = st2.executeQuery("PRAGMA table_info('phrases')")
                    while (rs.next()) {
                        existing.add(rs.getString("name"))
                    }
                }

                val migrations = mutableListOf<String>()
                if (!existing.contains("name")) migrations.add("ALTER TABLE phrases ADD COLUMN name TEXT")
                if (!existing.contains("background_color")) migrations.add("ALTER TABLE phrases ADD COLUMN background_color TEXT")
                if (!existing.contains("parent_id")) migrations.add("ALTER TABLE phrases ADD COLUMN parent_id TEXT")
                if (!existing.contains("is_category")) migrations.add("ALTER TABLE phrases ADD COLUMN is_category INTEGER DEFAULT 0")
                if (!existing.contains("created_at")) migrations.add("ALTER TABLE phrases ADD COLUMN created_at INTEGER")
                if (!existing.contains("ordering")) migrations.add("ALTER TABLE phrases ADD COLUMN ordering INTEGER")

                if (migrations.isNotEmpty()) {
                    log.info("Applying ${migrations.size} DB migrations to phrases table")
                    conn.autoCommit = false
                    try {
                        conn.createStatement().use { st3 ->
                            migrations.forEach { st3.executeUpdate(it) }
                        }

                        // If ordering was just added, populate it with a sensible default based on rowid
                        if (!existing.contains("ordering")) {
                            val ids = mutableListOf<String>()
                            conn.prepareStatement("SELECT id FROM phrases ORDER BY rowid ASC").use { ps ->
                                val rs = ps.executeQuery()
                                while (rs.next()) ids.add(rs.getString(1))
                            }
                            conn.prepareStatement("UPDATE phrases SET ordering = ? WHERE id = ?").use { ups ->
                                ids.forEachIndexed { idx, id ->
                                    ups.setInt(1, idx)
                                    ups.setString(2, id)
                                    ups.addBatch()
                                }
                                ups.executeBatch()
                            }
                        }

                        conn.commit()
                    } catch (t: Throwable) {
                        conn.rollback()
                        log.warn("Failed to apply DB migrations", t)
                    } finally {
                        conn.autoCommit = true
                    }
                }
            } catch (t: Throwable) {
                log.warn("Error checking/creating phrases table schema", t)
            }
        }
    }

    override suspend fun getAll(): List<Phrase> {
        log.info("Loading phrases from SQLite at {}", dbPath)
        val items = mutableListOf<Phrase>()
        connection().use { conn ->
            conn.prepareStatement("SELECT id, text, name, background_color, parent_id, is_category, created_at FROM phrases ORDER BY ordering ASC").use { ps ->
                val rs = ps.executeQuery()
                while (rs.next()) {
                    val p = Phrase(
                        id = rs.getString(1),
                        text = rs.getString(2),
                        name = rs.getString(3),
                        backgroundColor = rs.getString(4),
                        parentId = rs.getString(5),
                        isCategory = rs.getInt(6) != 0,
                        createdAt = rs.getLong(7)
                    )
                    items.add(p)
                    log.debug("Loaded phrase {} with bg='{}'", p.id, p.backgroundColor)
                }
            }
        }
        return items
    }
    override suspend fun add(phrase: Phrase): Phrase {
        val id = if (phrase.id.isBlank()) java.util.UUID.randomUUID().toString() else phrase.id
        val createdAt = if (phrase.createdAt == 0L) System.currentTimeMillis() else phrase.createdAt
        connection().use { conn ->
            conn.prepareStatement("SELECT COALESCE(MAX(ordering), -1) FROM phrases").use { ps ->
                val rs = ps.executeQuery()
                val max = if (rs.next()) rs.getInt(1) else -1
                val ord = max + 1
                conn.prepareStatement("INSERT INTO phrases(id, text, name, background_color, parent_id, is_category, created_at, ordering) VALUES (?,?,?,?,?,?,?,?)").use { ins ->
                    ins.setString(1, id)
                    ins.setString(2, phrase.text)
                    ins.setString(3, phrase.name)
                    ins.setString(4, phrase.backgroundColor)
                    ins.setString(5, phrase.parentId)
                    ins.setInt(6, if (phrase.isCategory) 1 else 0)
                    ins.setLong(7, createdAt)
                    ins.setInt(8, ord)
                    ins.executeUpdate()
                }
            }
        }
    log.info("Saved phrase {} with bg='{}'", id, phrase.backgroundColor)
        return Phrase(id = id, text = phrase.text, name = phrase.name, backgroundColor = phrase.backgroundColor, parentId = phrase.parentId, isCategory = phrase.isCategory, createdAt = createdAt)
    }

    override suspend fun update(phrase: Phrase): Phrase {
        connection().use { conn ->
            conn.prepareStatement("UPDATE phrases SET text = ?, name = ?, background_color = ?, parent_id = ?, is_category = ? WHERE id = ?").use { ps ->
                ps.setString(1, phrase.text)
                ps.setString(2, phrase.name)
                ps.setString(3, phrase.backgroundColor)
                ps.setString(4, phrase.parentId)
                ps.setInt(5, if (phrase.isCategory) 1 else 0)
                ps.setString(6, phrase.id)
                ps.executeUpdate()
            }
        }
        log.info("Updated phrase {}", phrase.id)
        return phrase
    }

    override suspend fun delete(id: String) {
        connection().use { conn ->
            conn.prepareStatement("DELETE FROM phrases WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
        }
        log.info("Deleted phrase {}", id)
    }

    override suspend fun move(fromIndex: Int, toIndex: Int) {
        connection().use { conn ->
            conn.autoCommit = false
            try {
                // load ids ordered
                val ids = mutableListOf<String>()
                conn.prepareStatement("SELECT id FROM phrases ORDER BY ordering ASC").use { ps ->
                    val rs = ps.executeQuery()
                    while (rs.next()) ids.add(rs.getString(1))
                }
                if (fromIndex < 0 || fromIndex >= ids.size) return
                val item = ids.removeAt(fromIndex)
                val insertIndex = toIndex.coerceIn(0, ids.size)
                ids.add(insertIndex, item)

                // write back ordering
                conn.prepareStatement("UPDATE phrases SET ordering = ? WHERE id = ?").use { ps ->
                    ids.forEachIndexed { idx, id ->
                        ps.setInt(1, idx)
                        ps.setString(2, id)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                conn.commit()
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            } finally {
                conn.autoCommit = true
            }
        }
    }
}
