package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.PhraseRepository
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class DesktopSqlPhraseRepository : PhraseRepository {
    private val log = LoggerFactory.getLogger("DesktopSqlPhraseRepository")

    private val dbPath: Path = DesktopPaths.configDir().resolve("wingmate.db")

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
                        image_url TEXT,
                        parent_id TEXT,
                        linked_board_id TEXT,
                        created_at INTEGER,
                        ordering INTEGER
                    )
                """.trimIndent())
            }
            // Ensure categories table exists so we can migrate legacy category phrases
            try {
                conn.createStatement().use { stCat ->
                    stCat.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS categories (
                            id TEXT PRIMARY KEY,
                            name TEXT,
                            selected_language TEXT,
                            created_at INTEGER,
                            ordering INTEGER
                        )
                        """.trimIndent()
                    )
                }
            } catch (t: Throwable) {
                log.warn("Failed ensuring categories table prior to migration", t)
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
                if (!existing.contains("image_url")) migrations.add("ALTER TABLE phrases ADD COLUMN image_url TEXT")
                if (!existing.contains("parent_id")) migrations.add("ALTER TABLE phrases ADD COLUMN parent_id TEXT")
                if (!existing.contains("linked_board_id")) migrations.add("ALTER TABLE phrases ADD COLUMN linked_board_id TEXT")
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
            // Legacy migration: move any phrases flagged as categories into real categories table
            try {
                conn.autoCommit = false
                val legacy = mutableListOf<Triple<String, String?, Long>>()
                conn.prepareStatement("SELECT id, COALESCE(NULLIF(name,''), NULLIF(text,'')) as display_name, created_at FROM phrases WHERE is_category = 1").use { ps ->
                    val rs = ps.executeQuery()
                    while (rs.next()) legacy += Triple(rs.getString(1), rs.getString(2), rs.getLong(3))
                }
                if (legacy.isNotEmpty()) {
                    log.info("Migrating {} legacy category phrases", legacy.size)
                    // Determine current max ordering in categories
                    var nextOrd = 0
                    conn.prepareStatement("SELECT COALESCE(MAX(ordering), -1) FROM categories").use { ps ->
                        val rs = ps.executeQuery(); if (rs.next()) nextOrd = rs.getInt(1) + 1
                    }
                    // Detect which language column exists
                    val catCols = mutableSetOf<String>()
                    conn.createStatement().use { stCols ->
                        val rs = stCols.executeQuery("PRAGMA table_info('categories')")
                        while (rs.next()) catCols.add(rs.getString("name"))
                    }
                    val useSnake = catCols.contains("selected_language") && !catCols.contains("selectedLanguage")
                    val insertSql = if (useSnake) {
                        "INSERT OR IGNORE INTO categories(id, name, selected_language, created_at, ordering) VALUES (?,?,?,?,?)"
                    } else {
                        // ensure camel column exists for forward schema
                        if (!catCols.contains("selectedLanguage")) {
                            runCatching { conn.createStatement().use { it.executeUpdate("ALTER TABLE categories ADD COLUMN selectedLanguage TEXT") } }
                        }
                        "INSERT OR IGNORE INTO categories(id, name, selectedLanguage, created_at, ordering) VALUES (?,?,?,?,?)"
                    }
                    conn.prepareStatement(insertSql).use { ins ->
                        legacy.forEachIndexed { idx, (id, name, createdAt) ->
                            ins.setString(1, id)
                            ins.setString(2, name ?: id)
                            ins.setString(3, null)
                            ins.setLong(4, if (createdAt == 0L) System.currentTimeMillis() else createdAt)
                            ins.setInt(5, nextOrd + idx)
                            ins.addBatch()
                        }
                        ins.executeBatch()
                    }
                    conn.prepareStatement("DELETE FROM phrases WHERE is_category = 1").use { it.executeUpdate() }
                    conn.commit()
                }
                conn.autoCommit = true
            } catch (t: Throwable) {
                try { conn.rollback() } catch (_: Throwable) {}
                log.warn("Failed migrating legacy category phrases", t)
            } finally {
                try { conn.autoCommit = true } catch (_: Throwable) {}
            }
        }
    }

    override suspend fun getAll(): List<Phrase> {
        log.info("Loading phrases from SQLite at {}", dbPath)
        val items = mutableListOf<Phrase>()
        connection().use { conn ->
            conn.prepareStatement("SELECT id, text, name, background_color, image_url, parent_id, linked_board_id, created_at FROM phrases ORDER BY ordering ASC").use { ps ->
                val rs = ps.executeQuery()
                while (rs.next()) {
                    val p = Phrase(
                        id = rs.getString(1),
                        text = rs.getString(2),
                        name = rs.getString(3),
                        backgroundColor = rs.getString(4),
                        imageUrl = rs.getString(5),
                        parentId = rs.getString(6),
                        linkedBoardId = rs.getString(7),
                        createdAt = rs.getLong(8)
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
                conn.prepareStatement("INSERT INTO phrases(id, text, name, background_color, image_url, parent_id, linked_board_id, created_at, ordering) VALUES (?,?,?,?,?,?,?,?,?)").use { ins ->
                    ins.setString(1, id)
                    ins.setString(2, phrase.text)
                    ins.setString(3, phrase.name)
                    ins.setString(4, phrase.backgroundColor)
                    ins.setString(5, phrase.imageUrl)
                    ins.setString(6, phrase.parentId)
                    ins.setString(7, phrase.linkedBoardId)
                    ins.setLong(8, createdAt)
                    ins.setInt(9, ord)
                    ins.executeUpdate()
                }
            }
        }
    log.info("Saved phrase {} with bg='{}'", id, phrase.backgroundColor)
        return Phrase(id = id, text = phrase.text, name = phrase.name, backgroundColor = phrase.backgroundColor, imageUrl = phrase.imageUrl, parentId = phrase.parentId, linkedBoardId = phrase.linkedBoardId, createdAt = createdAt)
    }

    override suspend fun update(phrase: Phrase): Phrase {
        connection().use { conn ->
            conn.prepareStatement("UPDATE phrases SET text = ?, name = ?, background_color = ?, image_url = ?, parent_id = ?, linked_board_id = ? WHERE id = ?").use { ps ->
                ps.setString(1, phrase.text)
                ps.setString(2, phrase.name)
                ps.setString(3, phrase.backgroundColor)
                ps.setString(4, phrase.imageUrl)
                ps.setString(5, phrase.parentId)
                ps.setString(6, phrase.linkedBoardId)
                ps.setString(7, phrase.id)
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
