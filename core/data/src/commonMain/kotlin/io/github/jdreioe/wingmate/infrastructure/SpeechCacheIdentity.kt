package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.PronunciationEntry
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.Voice
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.encodeUtf8

/** Versioned, unambiguous identity for synthesized speech bytes. */
internal object SpeechCacheIdentity {
    private const val VERSION = "v2"

    fun digest(vararg components: String?): String {
        val canonical = buildString {
            append(VERSION)
            components.forEach { component ->
                val value = component.orEmpty()
                append('|')
                append(value.length)
                append(':')
                append(value)
            }
        }
        return canonical.encodeUtf8().sha256().hex()
    }

    /**
     * Identity of [content] synthesized by [engine], shared by every platform's cache.
     * The pronunciation dictionary is part of it, so editing an entry re-synthesizes.
     */
    fun speech(
        content: String,
        engine: TtsEngine,
        voice: Voice,
        pitch: Double?,
        rate: Double?,
        dictionary: List<PronunciationEntry>,
    ): String = digest(
        content,
        engine.name,
        voice.name,
        voice.primaryLanguage,
        voice.selectedLanguage,
        pitch?.toString(),
        rate?.toString(),
        voice.mathMode.toString(),
        dictionary.joinToString("\u001f") { "${it.word}\u001e${it.phoneme}\u001e${it.alphabet}" },
    )
}

/**
 * Least-recently-used synthesized audio, capped at [maxBytes] in total.
 * Not thread-safe; callers guard it with their own lock.
 */
internal class SpeechAudioCache(private val maxBytes: Long) {
    private val entries = LinkedHashMap<String, ByteArray>()
    private var totalBytes = 0L

    operator fun get(key: String): ByteArray? {
        val audio = entries.remove(key) ?: return null
        entries[key] = audio
        return audio
    }

    operator fun contains(key: String): Boolean = key in entries

    operator fun set(key: String, audio: ByteArray) {
        entries.remove(key)?.let { totalBytes -= it.size }
        if (audio.size > maxBytes) return
        entries[key] = audio
        totalBytes += audio.size
        val iterator = entries.values.iterator()
        while (totalBytes > maxBytes) {
            totalBytes -= iterator.next().size
            iterator.remove()
        }
    }
}

internal suspend fun awaitSpeechInitialization(
    initialization: Deferred<Boolean>,
    timeoutMillis: Long,
): Boolean = withTimeoutOrNull(timeoutMillis) { initialization.await() } == true
