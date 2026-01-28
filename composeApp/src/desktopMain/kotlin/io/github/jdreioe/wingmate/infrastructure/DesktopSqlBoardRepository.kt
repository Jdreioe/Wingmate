package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.BoardRepository
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * SQLite-based BoardRepository for persisting imported OBF/OBZ boards.
 * Stores the entire board as JSON in a TEXT column.
 */
class DesktopSqlBoardRepository : BoardRepository {
    private val log = LoggerFactory.getLogger("DesktopSqlBoardRepository")
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
        // Ensure table exists
        connection().use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS boards (
                        id TEXT PRIMARY KEY,
                        name TEXT,
                        data TEXT NOT NULL,
                        created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000)
                    )
                """.trimIndent())
            }
        }
        log.info("DesktopSqlBoardRepository initialized with DB at {}", dbPath)
    }
    
    override suspend fun getBoard(id: String): ObfBoard? = withContext(Dispatchers.IO) {
        log.info("Getting board with id: {}", id)
        connection().use { conn ->
            conn.prepareStatement("SELECT data FROM boards WHERE id = ?").use { ps ->
                ps.setString(1, id)
                val rs = ps.executeQuery()
                if (rs.next()) {
                    val text = rs.getString(1)
                    runCatching {
                        json.decodeFromString<ObfBoard>(text)
                    }.onSuccess {
                        log.info("Successfully loaded board: {}", it.name ?: it.id)
                    }.onFailure {
                        log.error("Failed to parse board JSON", it)
                    }.getOrNull()
                } else {
                    log.info("No board found with id: {}", id)
                    null
                }
            }
        }
    }
    
    override suspend fun saveBoard(board: ObfBoard) = withContext(Dispatchers.IO) {
        log.info("Saving board: {} (id={})", board.name ?: "unnamed", board.id)
        val text = json.encodeToString(board)
        connection().use { conn ->
            conn.prepareStatement("""
                INSERT OR REPLACE INTO boards (id, name, data, created_at) 
                VALUES (?, ?, ?, ?)
            """.trimIndent()).use { ps ->
                ps.setString(1, board.id)
                ps.setString(2, board.name ?: board.id)
                ps.setString(3, text)
                ps.setLong(4, System.currentTimeMillis())
                ps.executeUpdate()
                log.info("Successfully saved board to SQLite: {}", board.name ?: board.id)
            }
        }
    }
    
    override suspend fun listBoards(): List<ObfBoard> = withContext(Dispatchers.IO) {
        log.info("Listing all boards")
        val boards = mutableListOf<ObfBoard>()
        connection().use { conn ->
            conn.prepareStatement("SELECT data FROM boards ORDER BY created_at DESC").use { ps ->
                val rs = ps.executeQuery()
                while (rs.next()) {
                    val text = rs.getString(1)
                    runCatching {
                        json.decodeFromString<ObfBoard>(text)
                    }.onSuccess { board ->
                        boards.add(board)
                    }.onFailure {
                        log.error("Failed to parse board JSON", it)
                    }
                }
            }
        }
        log.info("Found {} boards in database", boards.size)
        boards
    }
    
    override suspend fun deleteBoard(id: String) = withContext(Dispatchers.IO) {
        log.info("Deleting board with id: {}", id)
        connection().use { conn ->
            conn.prepareStatement("DELETE FROM boards WHERE id = ?").use { ps ->
                ps.setString(1, id)
                val deleted = ps.executeUpdate()
                log.info("Deleted {} board(s)", deleted)
            }
        }
    }
}
