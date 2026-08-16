package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class AndroidSqlSettingsRepository(private val context: Context) : SettingsRepository {
    private val helper by lazy { AndroidSqlOpenHelper(context) }
    private val repositoryDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

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

    override suspend fun get(): Settings = withContext(repositoryDispatcher) {
        val db = helper.readableDatabase
        // Removed SLF4J logger for cross-platform compatibility
        val cursor = db.query("ui_settings", arrayOf("data"), "id = 1", null, null, null, null)
        cursor.use {
            if (it.moveToFirst()) {
                val text = it.getString(it.getColumnIndexOrThrow("data"))
                return@withContext json.decodeFromString(Settings.serializer(), text)
            }
        }
        return@withContext Settings()
    }

    override suspend fun update(settings: Settings): Settings = withContext(repositoryDispatcher) {
        val db = helper.writableDatabase
        // Removed SLF4J logger for cross-platform compatibility
        val text = json.encodeToString(Settings.serializer(), settings)
        db.execSQL("INSERT OR REPLACE INTO ui_settings (id, data) VALUES (1, ?)", arrayOf(text))
        return@withContext settings
    }
}
