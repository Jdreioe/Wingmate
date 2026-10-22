package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.PronunciationEntry
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class SpeechCacheIdentityTest {
    @Test
    fun javaHashCollisionDoesNotReuseSpeechAudio() {
        check("Aa".hashCode() == "BB".hashCode())

        assertNotEquals(
            SpeechCacheIdentity.digest("Aa", "voice", "1.0"),
            SpeechCacheIdentity.digest("BB", "voice", "1.0"),
        )
    }

    @Test
    fun componentBoundariesArePartOfIdentity() {
        assertNotEquals(
            SpeechCacheIdentity.digest("ab", "c"),
            SpeechCacheIdentity.digest("a", "bc"),
        )
        assertEquals(
            SpeechCacheIdentity.digest("text", "voice"),
            SpeechCacheIdentity.digest("text", "voice"),
        )
    }

    @Test
    fun editingThePronunciationDictionaryChangesSpeechIdentity() {
        val voice = Voice(name = "en-US-JennyNeural", primaryLanguage = "en-US")
        fun identity(phoneme: String) = SpeechCacheIdentity.speech(
            "Hello", TtsEngine.AZURE_USER_RESOURCE, voice, pitch = null, rate = null,
            dictionary = listOf(PronunciationEntry(word = "Hello", phoneme = phoneme, alphabet = "ipa")),
        )

        assertEquals(identity("həˈloʊ"), identity("həˈloʊ"))
        assertNotEquals(identity("həˈloʊ"), identity("hɛˈloʊ"))
    }

    @Test
    fun audioCacheEvictsLeastRecentlyUsedAudioOverItsCap() {
        val cache = SpeechAudioCache(maxBytes = 4)
        cache["a"] = ByteArray(2)
        cache["b"] = ByteArray(2)
        cache["a"]
        cache["c"] = ByteArray(2)

        assertNull(cache["b"])
        assertEquals(2, cache["a"]?.size)
        assertEquals(2, cache["c"]?.size)

        cache["huge"] = ByteArray(5)
        assertNull(cache["huge"])
        assertEquals(2, cache["a"]?.size)
    }

    @Test
    fun initializationWaitsForDelayedSuccess() = runBlocking {
        val initialization = CompletableDeferred<Boolean>()
        launch {
            delay(10)
            initialization.complete(true)
        }

        assertEquals(true, awaitSpeechInitialization(initialization, timeoutMillis = 1_000))
    }

    @Test
    fun initializationFailureAndTimeoutAreRejected() = runBlocking {
        assertEquals(
            false,
            awaitSpeechInitialization(CompletableDeferred(false), timeoutMillis = 1_000),
        )
        assertEquals(
            false,
            awaitSpeechInitialization(CompletableDeferred(), timeoutMillis = 10),
        )
    }
}
