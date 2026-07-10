package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.BoardSetRepository
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

class DesktopSqlBoardSetRepository : BoardSetRepository {
    private val dbPath: Path = DesktopPaths.configDir().resolve("wingmate.db")

    private fun connection(): Connection {
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    init {
        connection().use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS board_sets (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        data TEXT NOT NULL,
                        updated_at INTEGER DEFAULT (strftime('%s', 'now') * 1000)
                    )
                    """.trimIndent()
                )
            }
        }
    }

    override suspend fun getBoardSet(id: String): ObfBoardSet? = withContext(Dispatchers.IO) {
        connection().use { conn ->
            conn.prepareStatement("SELECT data FROM board_sets WHERE id = ?").use { ps ->
                ps.setString(1, id)
                val rs = ps.executeQuery()
                if (rs.next()) {
                    val data = rs.getString(1)
                    runCatching { json.decodeFromString<ObfBoardSet>(data) }.getOrNull()
                } else {
                    null
                }
            }
        }
    }

    override suspend fun saveBoardSet(boardSet: ObfBoardSet): Unit = withContext(Dispatchers.IO) {
        val encoded = json.encodeToString(boardSet)
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO board_sets (id, name, data, updated_at)
                VALUES (?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, boardSet.id)
                ps.setString(2, boardSet.name)
                ps.setString(3, encoded)
                ps.setLong(4, boardSet.updatedAt)
                ps.executeUpdate()
            }
        }
    }

    override suspend fun listBoardSets(): List<ObfBoardSet> = withContext(Dispatchers.IO) {
        val result = mutableListOf<ObfBoardSet>()
        connection().use { conn ->
            conn.prepareStatement("SELECT data FROM board_sets ORDER BY updated_at DESC").use { ps ->
                val rs = ps.executeQuery()
                while (rs.next()) {
                    val data = rs.getString(1)
                    runCatching { json.decodeFromString<ObfBoardSet>(data) }
                        .onSuccess { result.add(it) }
                }
            }
        }
        result
    }

    override suspend fun deleteBoardSet(id: String): Unit = withContext(Dispatchers.IO) {
        connection().use { conn ->
            conn.prepareStatement("DELETE FROM board_sets WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
        }
    }
}
