package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.PersistenceError
import io.github.jdreioe.wingmate.domain.PersistenceException
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSLock

/**
 * Simple iOS-only repository that maps phraseId -> local audio file path.
 * Backed by NSUserDefaults storing a JSON map for durability.
 */
class IosPhraseAudioRepository {
    private val prefs by lazy { NSUserDefaults.standardUserDefaults() }
    private val key = "phrase_audio_v1"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val ser = MapSerializer(String.serializer(), String.serializer())
    private val lock = NSLock()

    private fun load(): MutableMap<String, String> {
        val text = prefs.stringForKey(key)
        if (text == null) {
            if (prefs.objectForKey(key) != null) {
                throw PersistenceException(PersistenceError.CorruptOrUnsupported)
            }
            return mutableMapOf()
        }
        return try {
            json.decodeFromString(ser, text).toMutableMap()
        } catch (error: Exception) {
            if (prefs.objectForKey(CORRUPT_KEY) == null) {
                prefs.setObject(text, forKey = CORRUPT_KEY)
                prefs.synchronize()
            }
            throw PersistenceException(PersistenceError.CorruptOrUnsupported, error)
        }
    }

    private fun save(map: Map<String, String>) {
        val text = json.encodeToString(ser, map)
        val previous = prefs.objectForKey(key)
        prefs.setObject(text, forKey = key)
        if (!prefs.synchronize()) {
            if (previous == null) prefs.removeObjectForKey(key)
            else prefs.setObject(previous, forKey = key)
            prefs.synchronize()
            throw PersistenceException(PersistenceError.Io)
        }
    }

    fun getPath(phraseId: String): String? = locked { load()[phraseId] }

    fun hasRecording(phraseId: String): Boolean = getPath(phraseId) != null

    fun savePath(phraseId: String, path: String) {
        locked {
            val map = load()
            map[phraseId] = path
            save(map)
        }
    }

    fun deletePath(phraseId: String) {
        locked {
            val map = load()
            map.remove(phraseId)
            save(map)
        }
    }

    fun all(): Map<String, String> = locked { load() }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }

    private companion object {
        const val CORRUPT_KEY = "phrase_audio_v1_corrupt_backup"
    }
}
