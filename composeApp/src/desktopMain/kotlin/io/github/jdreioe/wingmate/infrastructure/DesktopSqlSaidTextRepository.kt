package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager

class DesktopSqlSaidTextRepository : SaidTextRepository {
    private val log = LoggerFactory.getLogger("DesktopSqlSaidTextRepository")

    private val dbPath: Path by lazy { DesktopPaths.configDir().resolve("wingmate.db") }

    private fun connection(): Connection {
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
    }

    init {
        // ensure table exists
        connection().use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS said_texts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        date INTEGER,
                        said_text TEXT,
                        voice_name TEXT,
                        pitch REAL,
                        speed REAL,
                        audio_file_path TEXT,
                        created_at INTEGER,
                        position INTEGER,
                        primary_language TEXT
                    )
                    """.trimIndent()
                )
            }
        }
    }

    override suspend fun add(item: SaidText): SaidText = withContext(Dispatchers.IO) {
        connection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO said_texts (date, said_text, voice_name, pitch, speed, audio_file_path, created_at, position, primary_language) VALUES (?,?,?,?,?,?,?,?,?)",
                java.sql.Statement.RETURN_GENERATED_KEYS
            ).use { ps ->
                ps.setObject(1, item.date)
                ps.setString(2, item.saidText)
                ps.setString(3, item.voiceName)
                ps.setObject(4, item.pitch)
                ps.setObject(5, item.speed)
                ps.setString(6, item.audioFilePath)
                ps.setObject(7, item.createdAt)
                ps.setObject(8, item.position)
                ps.setString(9, item.primaryLanguage)
                ps.executeUpdate()
                val rs = ps.generatedKeys
                val id = if (rs.next()) rs.getInt(1) else null
                val saved = item.copy(id = id)
                log.info("Saved said text to SQLite: {}", item.saidText)
                return@withContext saved
            }
        }
    }

    override suspend fun list(): List<SaidText> = withContext(Dispatchers.IO) {
        connection().use { conn ->
            conn.prepareStatement("SELECT id, date, said_text, voice_name, pitch, speed, audio_file_path, created_at, position, primary_language FROM said_texts ORDER BY date DESC, id DESC").use { ps ->
                val rs = ps.executeQuery()
                val out = mutableListOf<SaidText>()
                while (rs.next()) {
                    out += SaidText(
                        id = rs.getInt(1),
                        date = rs.getLong(2).takeIf { !rs.wasNull() },
                        saidText = rs.getString(3),
                        voiceName = rs.getString(4),
                        pitch = rs.getDouble(5).takeIf { !rs.wasNull() },
                        speed = rs.getDouble(6).takeIf { !rs.wasNull() },
                        audioFilePath = rs.getString(7),
                        createdAt = rs.getLong(8).takeIf { !rs.wasNull() },
                        position = rs.getInt(9).takeIf { !rs.wasNull() },
                        primaryLanguage = rs.getString(10)
                    )
                }
                return@withContext out
            }
        }
    }
}
