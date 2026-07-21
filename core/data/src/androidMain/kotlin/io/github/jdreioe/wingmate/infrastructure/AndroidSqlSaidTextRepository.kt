package io.github.jdreioe.wingmate.infrastructure

import android.content.ContentValues
import android.content.Context
import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class AndroidSqlSaidTextRepository(private val context: Context) : SaidTextRepository {
    private val helper by lazy { AndroidSqlOpenHelper(context) }
    private val json = Json { prettyPrint = true }

    init {
        // ensure table exists
        val db = helper.writableDatabase
        db.execSQL("""
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
        """.trimIndent())
    }

    override suspend fun add(item: SaidText): SaidText = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("date", item.date)
            put("said_text", item.saidText)
            put("voice_name", item.voiceName)
            put("pitch", item.pitch)
            put("speed", item.speed)
            put("audio_file_path", item.audioFilePath)
            put("created_at", item.createdAt)
            put("position", item.position)
            put("primary_language", item.primaryLanguage)
        }
        val id = db.insert("said_texts", null, values)
        return@withContext item.copy(id = id.toInt())
    }

    override suspend fun list(): List<SaidText> = withContext(Dispatchers.IO) {
        val db = helper.readableDatabase
        val cursor = db.query("said_texts", null, null, null, null, null, "date DESC")
        val list = mutableListOf<SaidText>()
        while (cursor.moveToNext()) {
            val id = cursor.getInt(cursor.getColumnIndexOrThrow("id"))
            val date = cursor.getLong(cursor.getColumnIndexOrThrow("date"))
            val saidText = cursor.getString(cursor.getColumnIndexOrThrow("said_text"))
            val voiceName = cursor.getString(cursor.getColumnIndexOrThrow("voice_name"))
            val pitch = cursor.getDouble(cursor.getColumnIndexOrThrow("pitch"))
            val speed = cursor.getDouble(cursor.getColumnIndexOrThrow("speed"))
            val audio = cursor.getString(cursor.getColumnIndexOrThrow("audio_file_path"))
            val createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
            val position = cursor.getInt(cursor.getColumnIndexOrThrow("position"))
            val primaryLanguage = cursor.getString(cursor.getColumnIndexOrThrow("primary_language"))
            list += SaidText(id = id, date = date, saidText = saidText, voiceName = voiceName, pitch = pitch, speed = speed, audioFilePath = audio, createdAt = createdAt, position = position, primaryLanguage = primaryLanguage)
        }
        cursor.close()
        return@withContext list
    }

    override suspend fun deleteAll() = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.delete("said_texts", null, null)
        Unit
    }

    override suspend fun addAll(items: List<SaidText>) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            items.forEach { item ->
                val values = ContentValues().apply {
                    put("date", item.date)
                    put("said_text", item.saidText)
                    put("voice_name", item.voiceName)
                    put("pitch", item.pitch)
                    put("speed", item.speed)
                    put("audio_file_path", item.audioFilePath)
                    put("created_at", item.createdAt)
                    put("position", item.position)
                    put("primary_language", item.primaryLanguage)
                }
                db.insert("said_texts", null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
