package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.VoiceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class AndroidVoiceRepository(private val context: Context) : VoiceRepository {
    private val prefs by lazy { context.getSharedPreferences("wingmate_prefs", Context.MODE_PRIVATE) }
    private val json = Json { prettyPrint = true }

    override suspend fun getVoices(): List<Voice> = withContext(Dispatchers.IO) {
        // No local catalog on Android; rely on AzureVoiceCatalog for listings
        emptyList()
    }

    override suspend fun saveSelected(voice: Voice) = withContext(Dispatchers.IO) {
        val log = LoggerFactory.getLogger("AndroidVoiceRepository")
        try {
            val text = json.encodeToString(Voice.serializer(), voice)
            prefs.edit().putString("selected_voice", text).apply()
            log.info("Saved selected voice to SharedPreferences: {}", voice.name)
        } catch (t: Throwable) {
            log.error("Failed to save selected voice to SharedPreferences", t)
            throw t
        }
    }

    override suspend fun getSelected(): Voice? = withContext(Dispatchers.IO) {
        val log = LoggerFactory.getLogger("AndroidVoiceRepository")
        val text = prefs.getString("selected_voice", null)
        if (text.isNullOrBlank()) {
            log.info("No selected voice found in SharedPreferences")
            return@withContext null
        }
        return@withContext try {
            val v = json.decodeFromString(Voice.serializer(), text)
            log.info("Loaded selected voice from SharedPreferences: {}", v.name)
            v
        } catch (t: Throwable) {
            log.error("Failed to decode selected voice from SharedPreferences", t)
            null
        }
    }
}
