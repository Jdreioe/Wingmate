package io.github.jdreioe.wingmate.infrastructure

import android.content.ContentValues
import android.content.Context
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class AndroidSqlConfigRepository(private val context: Context) : ConfigRepository {
    private val helper by lazy { AndroidSqlOpenHelper(context) }
    private val json = Json { prettyPrint = true }

    override suspend fun getSpeechConfig(): SpeechServiceConfig? = withContext(Dispatchers.IO) {
        val db = helper.readableDatabase
        // Removed SLF4J logger for cross-platform compatibility
        val cursor = db.query("configs", null, "id = ?", arrayOf("speech_config"), null, null, null)
        val cfg = if (cursor.moveToFirst()) {
            val text = cursor.getString(cursor.getColumnIndexOrThrow("json"))
            try {
                json.decodeFromString(SpeechServiceConfig.serializer(), text)
            } catch (t: Throwable) {
                println("Failed to decode speech config from SQLite: ${t}")
                null
            }
        } else null
        cursor.close()
        return@withContext cfg
    }

    override suspend fun saveSpeechConfig(config: SpeechServiceConfig) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        // Removed SLF4J logger for cross-platform compatibility
        val text = json.encodeToString(SpeechServiceConfig.serializer(), config)
        val values = ContentValues().apply {
            put("id", "speech_config")
            put("json", text)
        }
        db.insertWithOnConflict("configs", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        println("Saved speech config to SQLite: {}: ${config}")
    }
}
