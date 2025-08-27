package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.VoiceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager

class DesktopSqlVoiceRepository : VoiceRepository {
    private val dbPath: Path by lazy {
        val home = System.getProperty("user.home")
        val dir = Paths.get(home, ".config", "wingmate")
        if (!Files.exists(dir)) Files.createDirectories(dir)
        dir.resolve("wingmate.db")
    }

    private fun connection(): Connection {
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite:${'$'}{dbPath.toAbsolutePath()}")
    }

    private val json = Json { prettyPrint = true }

    init {
        // ensure table exists for persisting selected voice
        connection().use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS voices (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    data TEXT
                )
            """.trimIndent())
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS voice_catalog (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        list TEXT
                    )
                    """.trimIndent()
                )
            }
        }
    }

    override suspend fun getVoices(): List<Voice> = withContext(Dispatchers.IO) {
        val log = LoggerFactory.getLogger("DesktopSqlVoiceRepository")
        connection().use { conn ->
            conn.prepareStatement("SELECT list FROM voice_catalog WHERE id = 1").use { ps ->
                val rs = ps.executeQuery()
                if (rs.next()) {
                    val text = rs.getString(1)
                    return@withContext runCatching { json.decodeFromString(ListSerializer(Voice.serializer()), text) }
                        .onFailure { log.warn("Failed to decode voice catalog", it) }
                        .getOrDefault(emptyList())
                }
                return@withContext emptyList()
            }
        }
    }

    override suspend fun saveVoices(list: List<Voice>) = withContext(Dispatchers.IO) {
        val log = LoggerFactory.getLogger("DesktopSqlVoiceRepository")
        val text = json.encodeToString(ListSerializer(Voice.serializer()), list)
        connection().use { conn ->
            conn.prepareStatement("INSERT OR REPLACE INTO voice_catalog (id, list) VALUES (1, ?)").use { ps ->
                ps.setString(1, text)
                ps.executeUpdate()
                log.info("Saved voice catalog to SQLite ({} items)", list.size)
            }
        }
    }

    override suspend fun saveSelected(voice: Voice) = withContext(Dispatchers.IO) {
        val log = LoggerFactory.getLogger("DesktopSqlVoiceRepository")
        val text = json.encodeToString(Voice.serializer(), voice)
        connection().use { conn ->
            conn.prepareStatement("INSERT OR REPLACE INTO voices (id, data) VALUES (1, ?) ").use { ps ->
                ps.setString(1, text)
                ps.executeUpdate()
                log.info("Saved selected voice to SQLite: {}", voice.name)
            }
        }
    }

    override suspend fun getSelected(): Voice? = withContext(Dispatchers.IO) {
        val log = LoggerFactory.getLogger("DesktopSqlVoiceRepository")
        connection().use { conn ->
            conn.prepareStatement("SELECT data FROM voices WHERE id = 1").use { ps ->
                val rs = ps.executeQuery()
                if (rs.next()) {
                    val text = rs.getString(1)
                    val v = json.decodeFromString(Voice.serializer(), text)
                    log.info("Loaded selected voice from SQLite: {}", v.name)
                    return@withContext v
                }
                return@withContext null
            }
        }
    }
}
