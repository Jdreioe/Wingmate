package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.VoiceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class AndroidSqlVoiceRepository(private val context: Context) : VoiceRepository {
    private val helper by lazy { AndroidSqlOpenHelper(context) }
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getVoices(): List<Voice> = withContext(Dispatchers.IO) {
        val db = helper.readableDatabase
        val cursor = db.query("voice_catalog", arrayOf("list"), "id = ?", arrayOf("1"), null, null, null)
        cursor.use {
            if (it.moveToFirst()) {
                val text = it.getString(it.getColumnIndexOrThrow("list"))
                json.decodeFromString(ListSerializer(Voice.serializer()), text)
            } else {
                emptyList()
            }
        }
    }

    override suspend fun saveVoices(list: List<Voice>) = withContext(Dispatchers.IO) {
        val jsonList = json.encodeToString(ListSerializer(Voice.serializer()), list)
        val db = helper.writableDatabase
        db.execSQL("INSERT OR REPLACE INTO voice_catalog (id, list) VALUES (1, ?)", arrayOf(jsonList))
    }

    override suspend fun saveSelected(voice: Voice) = withContext(Dispatchers.IO) {
        val text = json.encodeToString(Voice.serializer(), voice)
        val db = helper.writableDatabase
        db.execSQL("INSERT OR REPLACE INTO voices (id, data) VALUES (1, ?)", arrayOf(text))
    }

    override suspend fun getSelected(): Voice? = withContext(Dispatchers.IO) {
        val db = helper.readableDatabase
        val cursor = db.query("voices", arrayOf("data"), "id = ?", arrayOf("1"), null, null, null)
        cursor.use {
            if (it.moveToFirst()) {
                val text = it.getString(it.getColumnIndexOrThrow("data"))
                json.decodeFromString(Voice.serializer(), text)
            } else {
                null
            }
        }
    }
}
