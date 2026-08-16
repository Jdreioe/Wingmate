package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.VoiceRepository
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * iOS implementation that persists the selected voice using NSUserDefaults.
 * We do not cache the full voice catalog locally here; fetching from Azure is handled elsewhere.
 */
class IosVoiceRepository : VoiceRepository {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val listSerializer = ListSerializer(Voice.serializer())
    private val voices = IosPreferencesJsonStore(
        key = "voice_list",
        encode = { json.encodeToString(listSerializer, it) },
        decode = { json.decodeFromString(listSerializer, it) },
    )
    private val selected = IosPreferencesJsonStore(
        key = "selected_voice",
        encode = { json.encodeToString(Voice.serializer(), it) },
        decode = { json.decodeFromString(Voice.serializer(), it) },
    )

    override suspend fun getVoices(): List<Voice> = voices.read(::emptyList)

    override suspend fun saveVoices(list: List<Voice>) {
        voices.replace(list, ::emptyList)
    }

    override suspend fun saveSelected(voice: Voice) {
        selected.replace(voice) { voice }
    }

    override suspend fun getSelected(): Voice? = selected.readNullable()
}
