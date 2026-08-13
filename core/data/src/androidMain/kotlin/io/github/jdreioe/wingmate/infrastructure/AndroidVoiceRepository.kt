package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.VoiceRepository
import io.github.jdreioe.wingmate.domain.loggingClassName
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
            OperationalLogger.warn("voice_catalog.load", "failed", exceptionClass = t.loggingClassName())
            emptyList()
        }
    }

    override suspend fun saveVoices(list: List<Voice>) = withContext(Dispatchers.IO) {
        try {
            val text = json.encodeToString(ListSerializer(Voice.serializer()), list)
            prefs.edit().putString("voice_catalog", text).apply()
            OperationalLogger.debug("voice_catalog.save", "succeeded", count = list.size)
        } catch (t: Throwable) {
            OperationalLogger.warn("voice_catalog.save", "failed", exceptionClass = t.loggingClassName())
        }
    }

    override suspend fun saveSelected(voice: Voice) = withContext(Dispatchers.IO) {
        try {
            val text = json.encodeToString(Voice.serializer(), voice)
            prefs.edit().putString("selected_voice", text).apply()
            OperationalLogger.debug("voice_selection.save", "succeeded")
        } catch (t: Throwable) {
            OperationalLogger.warn("voice_selection.save", "failed", exceptionClass = t.loggingClassName())
            throw t
        }
    }

    override suspend fun getSelected(): Voice? = withContext(Dispatchers.IO) {
        val text = prefs.getString("selected_voice", null)
        if (text.isNullOrBlank()) {
            OperationalLogger.debug("voice_selection.load", "not_found")
            return@withContext null
        }
        return@withContext try {
            val v = json.decodeFromString(Voice.serializer(), text)
            OperationalLogger.debug("voice_selection.load", "succeeded")
            v
        } catch (t: Throwable) {
            OperationalLogger.warn("voice_selection.load", "failed", exceptionClass = t.loggingClassName())
            null
        }
    }

}
