package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.VoiceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer

class AndroidVoiceRepository(private val context: Context) : VoiceRepository {
    private val prefs by lazy { context.getSharedPreferences("wingmate_prefs", Context.MODE_PRIVATE) }
    private val json = Json { prettyPrint = true }

    override suspend fun getVoices(): List<Voice> = withContext(Dispatchers.IO) {
        try {
            val text = prefs.getString("voice_catalog", null)
            if (text.isNullOrBlank()) {
                emptyList()
            } else {
                json.decodeFromString(ListSerializer(Voice.serializer()), text)
            }
        } catch (t: Throwable) {
            println("Failed to load voice catalog from SharedPreferences: $t")
            emptyList()
        }
    }

    override suspend fun saveVoices(list: List<Voice>) = withContext(Dispatchers.IO) {
        try {
            val text = json.encodeToString(ListSerializer(Voice.serializer()), list)
            prefs.edit().putString("voice_catalog", text).apply()
            println("Saved voice catalog to SharedPreferences: ${list.size} voices")
        } catch (t: Throwable) {
            println("Failed to save voice catalog to SharedPreferences: $t")
        }
    }

    override suspend fun saveSelected(voice: Voice) = withContext(Dispatchers.IO) {
        // Removed SLF4J logger for cross-platform compatibility
        try {
            val text = json.encodeToString(Voice.serializer(), voice)
            prefs.edit().putString("selected_voice", text).apply()
            println("Saved selected voice to SharedPreferences: {}: ${voice.name}")
        } catch (t: Throwable) {
            println("Failed to save selected voice to SharedPreferences: ${t}")
            throw t
        }
    }

    override suspend fun getSelected(): Voice? = withContext(Dispatchers.IO) {
        // Removed SLF4J logger for cross-platform compatibility
        val text = prefs.getString("selected_voice", null)
        if (text.isNullOrBlank()) {
            println("No selected voice found in SharedPreferences")
            return@withContext null
        }
        return@withContext try {
            val v = json.decodeFromString(Voice.serializer(), text)
            println("Loaded selected voice from SharedPreferences: {}: ${v.name}")
            v
        } catch (t: Throwable) {
            println("Failed to decode selected voice from SharedPreferences: ${t}")
            null
        }
    }

}
