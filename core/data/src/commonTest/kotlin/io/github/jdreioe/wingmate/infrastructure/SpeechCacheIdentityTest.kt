package io.github.jdreioe.wingmate.infrastructure

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

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
