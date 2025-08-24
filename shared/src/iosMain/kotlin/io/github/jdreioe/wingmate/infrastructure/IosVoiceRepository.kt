package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.VoiceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

/**
 * iOS implementation that persists the selected voice using NSUserDefaults.
 * We do not cache the full voice catalog locally here; fetching from Azure is handled elsewhere.
 */
class IosVoiceRepository : VoiceRepository {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val prefs by lazy { NSUserDefaults.standardUserDefaults() }
    private val keySelected = "selected_voice"
    private val keyList = "voice_list"

    override suspend fun getVoices(): List<Voice> = withContext(Dispatchers.Default) {
        val text = prefs.stringForKey(keyList) ?: return@withContext emptyList()
        return@withContext runCatching { json.decodeFromString(ListSerializer(Voice.serializer()), text) }.getOrNull() ?: emptyList()
    }

    override suspend fun saveVoices(list: List<Voice>) = withContext(Dispatchers.Default) {
        val text = json.encodeToString(ListSerializer(Voice.serializer()), list)
        prefs.setObject(text, forKey = keyList)
        prefs.synchronize()
        Unit
    }

    override suspend fun saveSelected(voice: Voice) = withContext(Dispatchers.Default) {
        val text = json.encodeToString(Voice.serializer(), voice)
        // Debug log to help trace persistence on iOS
        try {
            println("DEBUG: IosVoiceRepository.saveSelected() saving voice='${voice.name}' selectedLang='${voice.selectedLanguage}'")
        } catch (_: Throwable) { }
        prefs.setObject(text, forKey = keySelected)
        prefs.synchronize()
        Unit
    }

    override suspend fun getSelected(): Voice? = withContext(Dispatchers.Default) {
        val text = prefs.stringForKey(keySelected) ?: return@withContext null
        val v = runCatching { json.decodeFromString(Voice.serializer(), text) }.getOrNull()
        try {
            println("DEBUG: IosVoiceRepository.getSelected() loaded='${v?.name ?: "(none)"}' selectedLang='${v?.selectedLanguage ?: "-"}')")
        } catch (_: Throwable) { }
        return@withContext v
    }
}
