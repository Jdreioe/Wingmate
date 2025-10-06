package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager

class DesktopSqlSettingsRepository : SettingsRepository {
    private val dbPath: Path = DesktopPaths.configDir().resolve("wingmate.db")

    private fun connection(): Connection {
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
    }

    private val json = Json { prettyPrint = true }

    init {
        connection().use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ui_settings (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    data TEXT
                )
            """.trimIndent())
            }
        }
    }

    override suspend fun get(): Settings = withContext(Dispatchers.IO) {
        val log = LoggerFactory.getLogger("DesktopSqlSettingsRepository")
        connection().use { conn ->
            conn.prepareStatement("SELECT data FROM ui_settings WHERE id = 1").use { ps ->
                val rs = ps.executeQuery()
                if (rs.next()) {
                    val text = rs.getString(1)
                    val s = json.decodeFromString(Settings.serializer(), text)
                    log.info("Loaded UI settings from SQLite: {}", s)
                    return@withContext s
                }
            }
        }
        // fallback default
        return@withContext Settings()
    }

    override suspend fun update(settings: Settings): Settings = withContext(Dispatchers.IO) {
        val log = LoggerFactory.getLogger("DesktopSqlSettingsRepository")
        val text = json.encodeToString(Settings.serializer(), settings)
        connection().use { conn ->
            conn.prepareStatement("INSERT OR REPLACE INTO ui_settings (id, data) VALUES (1, ?)").use { ps ->
                ps.setString(1, text)
                ps.executeUpdate()
                log.info("Saved UI settings to SQLite: {}", settings)
            }
        }
        return@withContext settings
    }
}
