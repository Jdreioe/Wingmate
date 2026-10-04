package io.github.jdreioe.wingmate.infrastructure

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
}

internal suspend fun awaitSpeechInitialization(
    initialization: Deferred<Boolean>,
    timeoutMillis: Long,
): Boolean = withTimeoutOrNull(timeoutMillis) { initialization.await() } == true
