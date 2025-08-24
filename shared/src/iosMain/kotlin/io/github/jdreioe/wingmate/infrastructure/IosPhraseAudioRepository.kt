package io.github.jdreioe.wingmate.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import platform.Foundation.NSUserDefaults

/**
 * Simple iOS-only repository that maps phraseId -> local audio file path.
 * Backed by NSUserDefaults storing a JSON map for durability.
 */
class IosPhraseAudioRepository {
    private val prefs by lazy { NSUserDefaults.standardUserDefaults() }
    private val key = "phrase_audio_v1"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val ser = MapSerializer(String.serializer(), String.serializer())

    private fun load(): MutableMap<String, String> {
        val text = prefs.stringForKey(key) ?: return mutableMapOf()
        return runCatching { json.decodeFromString(ser, text) }.getOrElse { mutableMapOf() }.toMutableMap()
    }

    private fun save(map: Map<String, String>) {
        val text = json.encodeToString(ser, map)
        prefs.setObject(text, forKey = key)
        prefs.synchronize()
    }

    fun getPath(phraseId: String): String? = load()[phraseId]

    fun hasRecording(phraseId: String): Boolean = getPath(phraseId) != null

    fun savePath(phraseId: String, path: String) {
        val map = load()
        map[phraseId] = path
        save(map)
    }

    fun deletePath(phraseId: String) {
        val map = load()
        map.remove(phraseId)
        save(map)
    }

    fun all(): Map<String, String> = load()
}
