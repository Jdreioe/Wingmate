package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository
import io.github.jdreioe.wingmate.domain.PronunciationEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

class DesktopSqlPronunciationDictionaryRepository : PronunciationDictionaryRepository {
    private val dbPath: Path = DesktopPaths.configDir().resolve("wingmate.db")
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    private fun connection(): Connection {
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
    }

    init {
        connection().use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS pronunciation_dictionary (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        data TEXT
                    )
                    """.trimIndent()
                )
            }
        }
    }

    override suspend fun getAll(): List<PronunciationEntry> = withContext(Dispatchers.IO) {
        loadAll()
    }

    override suspend fun add(entry: PronunciationEntry) = withContext(Dispatchers.IO) {
        val list = loadAll().toMutableList()
        list.removeAll { it.word.equals(entry.word, ignoreCase = true) }
        list.add(entry.copy(word = entry.word.trim(), phoneme = entry.phoneme.trim()))
        saveAll(list)
    }

    override suspend fun delete(word: String) = withContext(Dispatchers.IO) {
        val list = loadAll().toMutableList()
        list.removeAll { it.word.equals(word, ignoreCase = true) }
        saveAll(list)
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        saveAll(emptyList())
    }

    private fun loadAll(): List<PronunciationEntry> {
        connection().use { conn ->
            conn.prepareStatement("SELECT data FROM pronunciation_dictionary WHERE id = 1").use { ps ->
                val rs = ps.executeQuery()
                if (!rs.next()) return emptyList()
                val text = rs.getString(1) ?: return emptyList()
                return try {
                    json.decodeFromString(ListSerializer(PronunciationEntry.serializer()), text)
                        .sortedBy { it.word.lowercase() }
                } catch (_: Throwable) {
                    emptyList()
                }
            }
        }
    }

    private fun saveAll(list: List<PronunciationEntry>) {
        val text = json.encodeToString(ListSerializer(PronunciationEntry.serializer()), list)
        connection().use { conn ->
            conn.prepareStatement(
                "INSERT OR REPLACE INTO pronunciation_dictionary (id, data) VALUES (1, ?)"
            ).use { ps ->
                ps.setString(1, text)
                ps.executeUpdate()
            }
        }
    }
}
