package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class AndroidSqlSettingsRepository(private val context: Context) : SettingsRepository {
    private val helper by lazy { AndroidSqlOpenHelper(context) }
    private val json = Json { prettyPrint = true }

    init {
        // ensure ui_settings table exists (AndroidSqlOpenHelper currently doesn't create it)
        val db = helper.writableDatabase
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS ui_settings (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                data TEXT
            )
        """.trimIndent())
    }

    override suspend fun get(): Settings = withContext(Dispatchers.IO) {
        val db = helper.readableDatabase
        val log = LoggerFactory.getLogger("AndroidSqlSettingsRepository")
        val cursor = db.query("ui_settings", arrayOf("data"), "id = 1", null, null, null, null)
        if (cursor.moveToFirst()) {
            val text = cursor.getString(cursor.getColumnIndexOrThrow("data"))
            cursor.close()
            return@withContext try {
                val s = json.decodeFromString(Settings.serializer(), text)
                log.info("Loaded UI settings from SQLite: {}", s)
                s
            } catch (t: Throwable) {
                log.warn("Failed to decode UI settings from SQLite", t)
                Settings()
            }
        }
        cursor.close()
        return@withContext Settings()
    }

    override suspend fun update(settings: Settings): Settings = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val log = LoggerFactory.getLogger("AndroidSqlSettingsRepository")
        val text = json.encodeToString(Settings.serializer(), settings)
        db.execSQL("INSERT OR REPLACE INTO ui_settings (id, data) VALUES (1, ?)", arrayOf(text))
        log.info("Saved UI settings to SQLite: {}", settings)
        return@withContext settings
    }
}
