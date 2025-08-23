package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.VoiceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class AndroidSqlVoiceRepository(private val context: Context) : VoiceRepository {
    private val helper by lazy { AndroidSqlOpenHelper(context) }
    private val json = Json { prettyPrint = true }

    override suspend fun getVoices(): List<Voice> = withContext(Dispatchers.IO) {
        val db = helper.readableDatabase
        val cursor = db.query("voices", arrayOf("data"), "id = ?", arrayOf("2"), null, null, null)
        val list = if (cursor.moveToFirst()) {
            val text = cursor.getString(cursor.getColumnIndexOrThrow("data"))
            runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(ListSerializer(Voice.serializer()), text) }.getOrNull() ?: emptyList()
        } else emptyList()
        cursor.close()
        list
    }

    override suspend fun saveVoices(list: List<Voice>) = withContext(Dispatchers.IO) {
        val jsonList = Json { prettyPrint = false }.encodeToString(ListSerializer(Voice.serializer()), list)
        val db = helper.writableDatabase
        db.execSQL("INSERT OR REPLACE INTO voices (id, data) VALUES (2, ?)", arrayOf(jsonList))
    }

    override suspend fun saveSelected(voice: Voice) = withContext(Dispatchers.IO) {
        val log = LoggerFactory.getLogger("AndroidSqlVoiceRepository")
        val text = json.encodeToString(Voice.serializer(), voice)
        val db = helper.writableDatabase
        db.execSQL("INSERT OR REPLACE INTO voices (id, data) VALUES (1, ?)", arrayOf(text))
        log.info("Saved selected voice to SQLite: {}", voice.name)
    }

    override suspend fun getSelected(): Voice? = withContext(Dispatchers.IO) {
        val db = helper.readableDatabase
        val log = LoggerFactory.getLogger("AndroidSqlVoiceRepository")
        val cursor = db.query("voices", arrayOf("data"), "id = ?", arrayOf("1"), null, null, null)
        val v = if (cursor.moveToFirst()) {
            val text = cursor.getString(cursor.getColumnIndexOrThrow("data"))
            try {
                json.decodeFromString(Voice.serializer(), text)
            } catch (t: Throwable) {
                log.warn("Failed to decode selected voice", t)
                null
            }
        } else null
        cursor.close()
        return@withContext v
    }
}
