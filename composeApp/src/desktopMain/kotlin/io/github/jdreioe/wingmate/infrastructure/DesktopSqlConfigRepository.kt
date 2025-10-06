package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager

class DesktopSqlConfigRepository : ConfigRepository {
    private val dbPath: Path = DesktopPaths.configDir().resolve("wingmate.db")

    private fun connection(): Connection {
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
    }

    private val json = Json { prettyPrint = true }

    init {
        // ensure table exists
        connection().use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS configs (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    data TEXT
                )
            """.trimIndent())
            }
        }
    }

    override suspend fun getSpeechConfig(): SpeechServiceConfig? = withContext(Dispatchers.IO) {
        val log = LoggerFactory.getLogger("DesktopSqlConfigRepository")
        log.info("Getting speech config from SQLite at {}", dbPath)
        connection().use { conn ->
            conn.prepareStatement("SELECT data FROM configs WHERE id = 1").use { ps ->
                val rs = ps.executeQuery()
                if (rs.next()) {
                    val text = rs.getString(1)
                    val cfg = json.decodeFromString(SpeechServiceConfig.serializer(), text)
                    log.info("Successfully retrieved speech config from SQLite: {}", cfg)
                    return@withContext cfg
                } else {
                    log.info("No speech config found in SQLite")
                    return@withContext null
                }
            }
        }
    }

    override suspend fun saveSpeechConfig(config: SpeechServiceConfig) = withContext(Dispatchers.IO) {
        val log = LoggerFactory.getLogger("DesktopSqlConfigRepository")
        val text = json.encodeToString(SpeechServiceConfig.serializer(), config)
        connection().use { conn ->
            conn.prepareStatement("INSERT OR REPLACE INTO configs (id, data) VALUES (1, ?) ").use { ps ->
                ps.setString(1, text)
                ps.executeUpdate()
                log.info("Successfully saved speech config to SQLite: {}", config)
            }
        }
    }
}
